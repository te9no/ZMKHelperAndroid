import type { Build } from "./types";

const DB_NAME = "zmk-helper-web";
const DB_VERSION = 1;
const BLOBS = "artifact-blobs";
const BUILDS_TTL = 30 * 60 * 1000;

interface BuildCacheEntry {
  savedAt: number;
  builds: Build[];
}

function openDb(): Promise<IDBDatabase> {
  return new Promise((resolve, reject) => {
    const request = indexedDB.open(DB_NAME, DB_VERSION);
    request.onupgradeneeded = () => {
      if (!request.result.objectStoreNames.contains(BLOBS)) request.result.createObjectStore(BLOBS);
    };
    request.onsuccess = () => resolve(request.result);
    request.onerror = () => reject(request.error);
  });
}

export function saveBuilds(key: string, builds: Build[]): void {
  const entry: BuildCacheEntry = { savedAt: Date.now(), builds };
  localStorage.setItem(`builds:${key}`, JSON.stringify(entry));
}

export function loadBuilds(key: string, allowStale = false): Build[] {
  try {
    const raw = localStorage.getItem(`builds:${key}`);
    if (!raw) return [];
    const entry = JSON.parse(raw) as BuildCacheEntry;
    if (!allowStale && Date.now() - entry.savedAt > BUILDS_TTL) return [];
    return entry.builds;
  } catch {
    return [];
  }
}

export async function putArtifact(id: number, blob: Blob): Promise<void> {
  const db = await openDb();
  await new Promise<void>((resolve, reject) => {
    const transaction = db.transaction(BLOBS, "readwrite");
    transaction.objectStore(BLOBS).put(blob, id);
    transaction.oncomplete = () => resolve();
    transaction.onerror = () => reject(transaction.error);
  });
  db.close();
}

export async function getArtifact(id: number): Promise<Blob | undefined> {
  const db = await openDb();
  const value = await new Promise<Blob | undefined>((resolve, reject) => {
    const request = db.transaction(BLOBS, "readonly").objectStore(BLOBS).get(id);
    request.onsuccess = () => resolve(request.result as Blob | undefined);
    request.onerror = () => reject(request.error);
  });
  db.close();
  return value;
}
