import { afterEach, expect, it, vi } from "vitest";
import { GitHubClient } from "./github";

afterEach(() => vi.unstubAllGlobals());

it("lists builds in one request and loads only the requested run's live artifacts", async () => {
  const fetch = vi.fn()
    .mockResolvedValueOnce(Response.json({ workflow_runs: [{ id: 1 }, { id: 2 }] }))
    .mockResolvedValueOnce(Response.json({ artifacts: [
      { id: 3, name: "left", size_in_bytes: 100, expired: false },
      { id: 4, name: "old", size_in_bytes: 100, expired: true },
    ] }));
  vi.stubGlobal("fetch", fetch);
  const client = new GitHubClient("");
  const repo = { owner: "demo", repo: "keyboard" };
  const builds = await client.builds(repo, "feat/new");
  expect(fetch).toHaveBeenCalledTimes(1);
  expect(builds.map(b => b.runId)).toEqual([1, 2]);
  expect(builds[1]?.artifacts).toBeUndefined();
  expect(await client.artifacts(repo, 2)).toEqual([{ id: 3, name: "left", size: 100 }]);
  expect(fetch.mock.calls[1]?.[0]).toContain("/actions/runs/2/artifacts");
});
