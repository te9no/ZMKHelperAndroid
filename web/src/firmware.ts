import { strToU8, unzipSync, zipSync } from "fflate";
import type { Firmware } from "./types";

const UF2_BLOCK_SIZE = 512;
const MAGIC_START_0 = 0x0a324655;
const MAGIC_START_1 = 0x9e5d5157;
const MAGIC_END = 0x0ab16f30;
const FLAG_NO_FLASH = 1;
const UICR_START = 0x10000000;

export async function extractFirmware(archive: Blob, source: string): Promise<Firmware[]> {
  const zip = unzipSync(new Uint8Array(await archive.arrayBuffer()));
  return Object.entries(zip)
    .filter(([path]) => /\.(uf2|bin|hex|zip)$/i.test(path) && !path.endsWith("/"))
    .map(([path, bytes]) => ({
      name: path.split("/").pop() || path,
      path,
      blob: new Blob([bytes as BlobPart]),
      source,
    }));
}

export async function packageBlueboot(firmware: Firmware): Promise<Firmware> {
  if (!firmware.name.toLowerCase().endsWith(".uf2")) throw new Error("Blueboot変換にはUF2ファイルが必要です");
  const application = uf2ToBin(new Uint8Array(await firmware.blob.arrayBuffer()), firmware.name);
  const base = firmware.name.replace(/\.uf2$/i, "");
  const dat = initPacket(application);
  const manifest = JSON.stringify({ manifest: { application: { bin_file: `${base}.bin`, dat_file: `${base}.dat` } } });
  const bytes = zipSync({
    [`${base}.bin`]: application,
    [`${base}.dat`]: dat,
    "manifest.json": strToU8(manifest),
  });
  return {
    name: `${base}-blueboot.zip`,
    path: `${base}-blueboot.zip`,
    blob: new Blob([bytes as BlobPart], { type: "application/zip" }),
    source: `Blueboot package from ${firmware.name}`,
  };
}

export function uf2ToBin(data: Uint8Array, name = "firmware.uf2"): Uint8Array {
  if (!data.length || data.length % UF2_BLOCK_SIZE !== 0) throw new Error(`${name}は完全なUF2イメージではありません`);
  const image = new Map<number, number>();
  const view = new DataView(data.buffer, data.byteOffset, data.byteLength);
  for (let offset = 0; offset < data.length; offset += UF2_BLOCK_SIZE) {
    if (view.getUint32(offset, true) !== MAGIC_START_0 || view.getUint32(offset + 4, true) !== MAGIC_START_1 || view.getUint32(offset + 508, true) !== MAGIC_END) {
      throw new Error(`${name}のUF2 block ${offset}が不正です`);
    }
    const flags = view.getUint32(offset + 8, true);
    if ((flags & FLAG_NO_FLASH) !== 0) continue;
    const target = view.getUint32(offset + 12, true);
    const size = view.getUint32(offset + 16, true);
    if (size > 476) throw new Error(`${name}に大きすぎるUF2 payloadがあります`);
    for (let index = 0; index < size; index++) {
      const address = target + index;
      if (address >= UICR_START) continue;
      const value = data[offset + 32 + index];
      if (value === undefined) throw new Error(`${name}のUF2 payloadが途中で終了しています`);
      const previous = image.get(address);
      if (previous !== undefined && previous !== value) throw new Error(`${name}の0x${address.toString(16)}に競合するデータがあります`);
      image.set(address, value);
    }
  }
  const addresses = [...image.keys()].sort((a, b) => a - b);
  if (!addresses.length) throw new Error(`${name}に書き込み可能なデータがありません`);
  const start = addresses[0]!;
  const end = addresses[addresses.length - 1]!;
  const output = new Uint8Array(Math.ceil((end - start + 1) / 4) * 4).fill(0xff);
  for (const [address, value] of image) output[address - start] = value;
  return output;
}

function initPacket(application: Uint8Array): Uint8Array {
  const output = new Uint8Array(14);
  const view = new DataView(output.buffer);
  view.setUint16(0, 0x0052, true);
  view.setUint16(2, 0xffff, true);
  view.setUint32(4, 0xffffffff, true);
  view.setUint16(8, 1, true);
  view.setUint16(10, 0x0123, true);
  view.setUint16(12, crc16(application), true);
  return output;
}

function crc16(data: Uint8Array): number {
  let crc = 0xffff;
  for (const byte of data) {
    crc = ((crc >> 8) & 0x00ff) | ((crc << 8) & 0xff00);
    crc ^= byte;
    crc ^= (crc & 0x00ff) >> 4;
    crc ^= (crc << 8) << 4;
    crc ^= ((crc & 0x00ff) << 4) << 1;
  }
  return crc & 0xffff;
}
