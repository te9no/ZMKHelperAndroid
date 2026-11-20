import type { Artifact, Build, RepoRef } from "./types";

const API = "https://api.github.com";

interface WorkflowRunResponse {
  workflow_runs: Array<{
    id: number;
    display_title?: string;
    name?: string;
    head_branch?: string;
    head_sha?: string;
    created_at?: string;
    html_url?: string;
  }>;
}

interface ArtifactsResponse {
  artifacts: Array<{
    id: number;
    name: string;
    size_in_bytes: number;
    expired: boolean;
  }>;
}

export function parseRepo(value: string): RepoRef {
  const trimmed = value.trim().replace(/\/$/, "").replace(/\.git$/, "");
  const match = trimmed.match(/^(?:https?:\/\/github\.com\/)?([^/\s]+)\/([^/\s]+)$/i);
  if (!match?.[1] || !match[2]) throw new Error("Repositoryは owner/repo またはGitHub URLで入力してください");
  return { owner: match[1], repo: match[2] };
}

export class GitHubClient {
  constructor(private readonly token: string) {}

  async viewer(): Promise<string> {
    const user = await this.get<{ login: string }>("/user");
    return user.login;
  }

  async branches(repo: RepoRef): Promise<string[]> {
    const result: string[] = [];
    for (let page = 1; page <= 3; page++) {
      const rows = await this.get<Array<{ name: string }>>(
        `/repos/${repo.owner}/${repo.repo}/branches?per_page=100&page=${page}`,
      );
      result.push(...rows.map((row) => row.name));
      if (rows.length < 100) break;
    }
    return result;
  }

  async builds(repo: RepoRef, branch: string): Promise<Build[]> {
    const branchQuery = branch ? `&branch=${encodeURIComponent(branch)}` : "";
    const runs = await this.get<WorkflowRunResponse>(
      `/repos/${repo.owner}/${repo.repo}/actions/runs?status=success&per_page=30${branchQuery}`,
    );
    return runs.workflow_runs.map((run) => ({
      runId: run.id, title: run.display_title || run.name || `Run ${run.id}`,
      branch: run.head_branch || "unknown", sha: run.head_sha || "",
      createdAt: run.created_at || "", htmlUrl: run.html_url || "",
    }));
  }

  async artifacts(repo: RepoRef, runId: number): Promise<Artifact[]> {
    const response = await this.get<ArtifactsResponse>(
      `/repos/${repo.owner}/${repo.repo}/actions/runs/${runId}/artifacts?per_page=100`,
    );
    return response.artifacts.filter((a) => !a.expired)
      .map((a) => ({ id: a.id, name: a.name, size: a.size_in_bytes }));
  }
  async artifact(repo: RepoRef, artifactId: number): Promise<Blob> {
    if (!this.token) throw new Error("Artifactの取得にはGitHubログインまたはtokenが必要です");
    const response = await fetch(`${API}/repos/${repo.owner}/${repo.repo}/actions/artifacts/${artifactId}/zip`, {
      headers: this.headers(),
    });
    if (!response.ok) throw await this.error(response);
    return response.blob();
  }

  private async get<T>(path: string): Promise<T> {
    const response = await fetch(`${API}${path}`, { headers: this.headers() });
    if (!response.ok) throw await this.error(response);
    return response.json() as Promise<T>;
  }

  private headers(): HeadersInit {
    const headers: Record<string, string> = {
      Accept: "application/vnd.github+json",
      "X-GitHub-Api-Version": "2022-11-28",
    };
    if (this.token) headers.Authorization = `Bearer ${this.token}`;
    return headers;
  }

  private async error(response: Response): Promise<Error> {
    const body = await response.text();
    if (response.status === 401) return new Error("GitHub認証の期限が切れています。再ログインしてください");
    if (response.status === 403) return new Error("GitHub APIのrate limitまたはアクセス権限を確認してください");
    if (response.status === 404) return new Error("Repositoryが見つからないか、アクセス権限がありません");
    return new Error(`GitHub API ${response.status}: ${body.slice(0, 300)}`);
  }
}
