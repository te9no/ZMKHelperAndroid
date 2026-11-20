export interface RepoRef {
  owner: string;
  repo: string;
}

export interface Artifact {
  id: number;
  name: string;
  size: number;
}

export interface Build {
  runId: number;
  title: string;
  branch: string;
  sha: string;
  createdAt: string;
  htmlUrl: string;
  artifacts?: Artifact[];
}

export interface Firmware {
  name: string;
  path: string;
  blob: Blob;
  source: string;
}

