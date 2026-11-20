// @vitest-environment happy-dom
import { expect, it, vi } from "vitest";
import { getArtifact } from "./cache";
import { extractFirmware } from "./firmware";

vi.mock("./cache", () => ({
  loadBuilds: () => [{ runId: 1, title: "Build", branch: "main", sha: "abc", createdAt: "",
    artifacts: [{ id: 1, name: "Left", size: 1 }, { id: 2, name: "Right", size: 1 }] }],
  getArtifact: vi.fn(), putArtifact: vi.fn(), saveBuilds: vi.fn(),
}));
vi.mock("./firmware", () => ({ extractFirmware: vi.fn(), packageBlueboot: vi.fn() }));

it("preserves the selected artifact and downloadable UF2 after a failed switch", async () => {
  document.body.innerHTML = '<div id="app"></div>';
  localStorage.setItem("repo", "demo/keyboard");
  const blob = new Blob(["left firmware"]);
  vi.mocked(getArtifact).mockResolvedValueOnce(blob).mockRejectedValueOnce(new Error("offline"));
  vi.mocked(extractFirmware).mockResolvedValue([{ name: "left.uf2", path: "left.uf2", blob, source: "Left" }]);
  await import("./main");
  const click = (selector: string) => document.querySelector<HTMLButtonElement>(selector)!.click();
  click('[data-artifact="1"]');
  await vi.waitFor(() => expect(document.querySelector(".selected-firmware strong")?.textContent).toBe("left.uf2"));
  click('[data-artifact="2"]');
  await vi.waitFor(() => expect(document.querySelector(".status")?.textContent).toContain("offline"));
  expect(document.querySelector(".artifact.selected")?.getAttribute("data-artifact")).toBe("1");
  const url = vi.spyOn(URL, "createObjectURL").mockReturnValue("blob:test");
  vi.spyOn(HTMLAnchorElement.prototype, "click").mockImplementation(() => {});
  click("#save");
  expect(url).toHaveBeenCalledWith(blob);
});

it("opens settings with focus inside and returns focus on Escape", async () => {
  vi.resetModules();
  document.body.innerHTML = '<div id="app"></div>';
  await import("./main");
  const menu = document.querySelector<HTMLButtonElement>("#menu-button")!;
  const drawer = document.querySelector("#drawer")!;
  expect(drawer.hasAttribute("inert")).toBe(true);
  menu.click();
  expect(document.activeElement?.id).toBe("close-menu");
  expect(document.querySelector("main")?.hasAttribute("inert")).toBe(true);
  drawer.dispatchEvent(new KeyboardEvent("keydown", { key: "Escape", bubbles: true }));
  expect(drawer.hasAttribute("inert")).toBe(true);
  expect(document.activeElement).toBe(menu);
  const picker = vi.spyOn(document.querySelector<HTMLInputElement>("#local-file")!, "click");
  document.querySelector<HTMLButtonElement>("button#choose-local")!.click();
  expect(picker).toHaveBeenCalledOnce();
});
