import { describe, expect, it } from "vitest";
import { unzipSync } from "fflate";
import { packageBlueboot, uf2ToBin } from "./firmware";

function uf2Block(address: number, payload: number[]): Uint8Array {
  const block = new Uint8Array(512);
  const view = new DataView(block.buffer);
  view.setUint32(0, 0x0a324655, true);
  view.setUint32(4, 0x9e5d5157, true);
  view.setUint32(12, address, true);
  view.setUint32(16, payload.length, true);
  view.setUint32(20, 0, true);
  view.setUint32(24, 1, true);
  block.set(payload, 32);
  view.setUint32(508, 0x0ab16f30, true);
  return block;
}

describe("Blueboot packaging", () => {
  it("converts a UF2 image into an aligned application binary", () => {
    expect([...uf2ToBin(uf2Block(0x26000, [1, 2, 3]), "test.uf2")]).toEqual([1, 2, 3, 0xff]);
  });

  it("creates a Nordic-style ZIP with bin, dat, and manifest", async () => {
    const uf2 = uf2Block(0x26000, [1, 2, 3, 4]);
    const result = await packageBlueboot({
      name: "keyboard.uf2",
      path: "keyboard.uf2",
      blob: new Blob([uf2.slice().buffer]),
      source: "test",
    });
    const zip = unzipSync(new Uint8Array(await result.blob.arrayBuffer()));
    expect(Object.keys(zip).sort()).toEqual(["keyboard.bin", "keyboard.dat", "manifest.json"]);
    expect([...zip["keyboard.bin"]!]).toEqual([1, 2, 3, 4]);
    expect(new TextDecoder().decode(zip["manifest.json"])).toContain('"bin_file":"keyboard.bin"');
  });

  it("rejects malformed UF2 input", () => {
    expect(() => uf2ToBin(new Uint8Array(512), "bad.uf2")).toThrow("不正");
  });
});
