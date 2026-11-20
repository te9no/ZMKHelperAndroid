import "./style.css";
import { getArtifact, loadBuilds as loadCachedBuilds, putArtifact, saveBuilds } from "./cache";
import { extractFirmware, packageBlueboot } from "./firmware";
import { GitHubClient, parseRepo } from "./github";
import { deviceLogin, oauthAvailable } from "./oauth";
import type { Artifact, Build, Firmware } from "./types";
import { CdcConsole, webUsbAvailable } from "./usb";

declare global {
  interface Window {
    showDirectoryPicker?: (options?: { mode?: "read" | "readwrite" }) => Promise<FileSystemDirectoryHandle>;
  }
}

const MAX_LOCAL_SIZE = 64 * 1024 * 1024;
const app = document.querySelector<HTMLDivElement>("#app")!;

let token = localStorage.getItem("github-token") || "";
let viewer = localStorage.getItem("github-viewer") || "";
let repoText = localStorage.getItem("repo") || "";
let branch = localStorage.getItem("branch") || "";
let branches: string[] = [];
let listKey = "";
let listRepo = "";
let builds: Build[] = [];
let firmwareFiles: Firmware[] = [];
let selectedBuild: Build | undefined;
let selectedArtifact: Artifact | undefined;
let selectedFirmware: Firmware | undefined;
let busy = false;
let status = "ローカルファイルを選ぶか、GitHub repositoryを読み込んでください。";
let usbLog = "CDC deviceを接続すると、115200 baudのログをここに表示します。";
let usbDeviceName = "";
const cdcConsole = new CdcConsole();

render();
restoreCachedBuilds();
registerServiceWorker();

function render(): void {
  const consoleOpen = document.querySelector<HTMLDetailsElement>(".usb-panel")?.open ?? false;
  app.innerHTML = `
    <div class="ambient ambient-one"></div><div class="ambient ambient-two"></div>
    <header class="topbar">
      <button class="icon-button" id="menu-button" aria-controls="drawer" aria-expanded="false" aria-label="設定を開く">☰</button>
      <div class="brand"><img class="app-mark" src="./icons/icon-192.png" alt="" width="40" height="40"><div><strong>ZMK Firmware Helper</strong></div></div>
      <span class="connection ${token ? "online" : ""}">${token ? `GitHub: ${escapeHtml(viewer || "signed in")}` : "未ログイン"}</span>
    </header>

    <main>
      <header class="workspace-title"><h1>ファームウェア更新</h1><p>キーボードのボードと左右を確認して、対象のファイルを選んでください。</p></header>

      <div class="step-grid">
        <section class="panel source-panel">
          <div class="step-heading"><span>01</span><div><h2>GitHubからビルドを選択</h2><p>リポジトリを入力し、必要ならブランチで絞り込んでください。</p></div></div>
          <label class="field"><span>リポジトリ</span><input id="repo" value="${escapeHtml(repoText)}" placeholder="owner/repository" autocomplete="off" /></label>
          <div class="source-actions">
            <label class="field grow"><span>ブランチ</span>
              <select id="branch" ${branches.length ? "" : "disabled"}>
                <option value="">すべてのブランチ</option>
                ${branches.map((item) => `<option ${item === branch ? "selected" : ""}>${escapeHtml(item)}</option>`).join("")}
              </select>
            </label>
            <button class="secondary align-bottom" id="load-branches" ${busy ? "disabled" : ""}>一覧を取得</button>
          </div>
          <button class="primary full" id="load-builds" ${busy ? "disabled" : ""}>成功したビルドを読み込む</button>
          <div class="divider"><span>またはログインせずに</span></div>
          <button type="button" class="file-drop" id="choose-local" ${busy ? "disabled" : ""}>
            <span class="drop-icon">＋</span><strong>端末からUF2 / ZIPを選択</strong><small>最大64 MiB・処理は端末内で完結</small>
          </button>
          <input id="local-file" type="file" accept=".uf2,.zip,application/zip,application/octet-stream" hidden />
        </section>

        <section class="panel builds-panel">
          <div class="step-heading"><span>02</span><div><h2>ビルドと成果物</h2><p>${builds.length ? `ビルド ${builds.length} 件` : "ビルド単位でArtifactを表示します。"}</p></div></div>
          <div class="build-list">
            ${builds.length ? builds.map(buildCard).join("") : emptyState("GitHubからビルドを読み込むと、ここに一覧が表示されます。")}
          </div>
        </section>

        <section class="panel output-panel">
          <div class="step-heading"><span>03</span><div><h2>ファームウェアを保存</h2><p>対象ボード・左右・shieldを必ず確認してください。</p></div></div>
          ${selectedFirmware ? firmwareCard(selectedFirmware) : emptyState("Artifactまたは端末からファームウェアを選択してください。")}
          ${firmwareFiles.length > 1 ? `<div class="firmware-list">${firmwareFiles.map(firmwareRow).join("")}</div>` : ""}
          <div class="warning"><strong>書き込み前の確認</strong><span>異なるボード向けUF2を書き込むと起動しなくなる可能性があります。</span></div>
          <div class="output-actions">
            <button class="secondary" id="convert" ${selectedFirmware?.name.toLowerCase().endsWith(".uf2") && !busy ? "" : "disabled"}>Blueboot ZIPへ変換</button>
            <button class="primary" id="save" ${selectedFirmware && !busy ? "" : "disabled"}>${saveButtonLabel()}</button>
          </div>
        </section>
      </div>

      <details class="panel usb-panel" ${consoleOpen ? "open" : ""}><summary>USB 診断・CDC ログ</summary>
        <div class="step-heading"><span>USB</span><div><h2>CDC Debug Console</h2><p>${webUsbAvailable() ? "Android Chrome / WebUSB" : "このブラウザではWebUSBを利用できません"}</p></div></div>
        <div class="usb-layout">
          <pre id="usb-log">${escapeHtml(usbLog)}</pre>
          <div class="usb-controls">
            <span class="usb-device">${usbDeviceName ? `CONNECTED · ${escapeHtml(usbDeviceName)}` : "NO CDC DEVICE"}</span>
            <button class="secondary" id="connect-cdc" ${webUsbAvailable() && !busy ? "" : "disabled"}>115200 baudで接続</button>
            <button class="secondary" id="trigger-cdc" ${webUsbAvailable() && !busy ? "" : "disabled"}>1200 baudでBootloader起動</button>
            <button class="text-button" id="disconnect-cdc" ${usbDeviceName ? "" : "disabled"}>切断</button>
            <button class="text-button" id="clear-cdc">ログを消去</button>
          </div>
        </div>
      </details>

      <section class="status ${busy ? "working" : ""}" aria-live="polite">
        <span class="status-light"></span><div><p>${escapeHtml(status)}</p></div>
      </section>
    </main>

    <aside class="drawer" id="drawer" inert aria-hidden="true">
      <div class="drawer-head"><div><small>ACCOUNT & SETTINGS</small><h2>Menu</h2></div><button class="icon-button" id="close-menu" aria-label="設定を閉じる">×</button></div>
      <div class="account-card"><span class="avatar">${escapeHtml((viewer || "G").slice(0, 1).toUpperCase())}</span><div><strong>${escapeHtml(viewer || "Guest")}</strong><small>${token ? "GitHub connected" : "Public API rate limit applies"}</small></div></div>
      <button class="primary full" id="oauth-login" ${busy || !oauthAvailable() ? "disabled" : ""}>GitHubでログイン</button>
      ${!oauthAvailable() ? `<p class="hint">この配信ではOAuth proxyが未設定です。下のtoken入力を利用できます。</p>` : ""}
      <label class="field"><span>GitHub token</span><input id="token" type="password" value="${escapeHtml(token)}" placeholder="github_pat_..." autocomplete="off" /></label>
      <button class="secondary full" id="save-token">Tokenを端末に保存</button>
      <button class="text-button full" id="logout" ${token ? "" : "disabled"}>ログアウト / token削除</button>
      <div class="privacy"><strong>Privacy</strong><p>Tokenと設定はこのブラウザ内に保存され、GitHub API以外には送信されません。共有端末では利用後に削除してください。</p></div>
    </aside><div class="scrim" id="scrim"></div>

    <dialog id="code-dialog"><div class="dialog-content"><p class="eyebrow">GITHUB DEVICE FLOW</p><h2>GitHubで認証</h2><p>次のコードをコピーしてGitHubへ進んでください。</p><button class="login-code" id="copy-code"></button><a class="primary link-button" id="open-github" target="_blank" rel="noopener">GitHubを開く</a><button class="text-button" id="cancel-code">閉じる</button></div></dialog>
  `;
  bindEvents();
}

function bindEvents(): void {
  on("menu-button", "click", () => toggleDrawer(true));
  on("drawer", "keydown", (event) => { if ((event as KeyboardEvent).key === "Escape") toggleDrawer(false); });
  on("close-menu", "click", () => toggleDrawer(false));
  on("scrim", "click", () => toggleDrawer(false));
  on("choose-local", "click", () => document.getElementById("local-file")?.click());
  on("local-file", "change", localFileChanged);
  on("repo", "input", (event) => {
    repoText = (event.target as HTMLInputElement).value;
    localStorage.setItem("repo", repoText);
  });
  on("branch", "change", (event) => {
    branch = (event.target as HTMLSelectElement).value;
    localStorage.setItem("branch", branch);
  });
  on("load-branches", "click", loadBranches);
  on("load-builds", "click", () => loadBuilds(false));
  on("convert", "click", convertBlueboot);
  on("save", "click", saveFirmware);
  on("oauth-login", "click", login);
  on("save-token", "click", saveToken);
  on("logout", "click", logout);
  on("connect-cdc", "click", connectCdc);
  on("trigger-cdc", "click", triggerCdc);
  on("disconnect-cdc", "click", disconnectCdc);
  on("clear-cdc", "click", clearCdc);
  document.querySelectorAll<HTMLButtonElement>("[data-load-run]").forEach((button) => {
    button.onclick = () => run("Artifact一覧を取得しています…", async () => {
      const build = builds.find((item) => item.runId === Number(button.dataset.loadRun));
      if (!build) return;
      build.artifacts = await new GitHubClient(token).artifacts(parseRepo(listRepo), build.runId);
      saveBuilds(listKey, builds);
      status = build.artifacts.length ? "対象のArtifactを選択してください。" : "有効なArtifactはありません。";
    });
  });
  document.querySelectorAll<HTMLButtonElement>("[data-artifact]").forEach((button) => button.addEventListener("click", selectArtifact));
  document.querySelectorAll<HTMLButtonElement>("[data-firmware]").forEach((button) => button.addEventListener("click", selectFirmware));
}

function on(id: string, event: string, handler: EventListener): void {
  document.getElementById(id)?.addEventListener(event, handler);
}

async function loadBranches(): Promise<void> {
  await run("ブランチ一覧を読み込んでいます…", async () => {
    const repo = parseRepo(repoText);
    branches = await new GitHubClient(token).branches(repo);
    status = `${branches.length} branchesを読み込みました。`;
  });
}

async function loadBuilds(force: boolean): Promise<void> {
  await run("成功したActionsビルドを読み込んでいます…", async () => {
    const repo = parseRepo(repoText);
    const key = cacheKey();
    const cached = loadCachedBuilds(key, force);
    if (cached.length && !force) {
      listKey = key; listRepo = repo.owner + "/" + repo.repo;
      builds = cached;
      status = `キャッシュから${builds.length} buildsを表示しました。`;
      return;
    }
    builds = await new GitHubClient(token).builds(repo, branch);
    listKey = key; listRepo = repo.owner + "/" + repo.repo;
    saveBuilds(key, builds);
    status = `${builds.length} buildsをGitHubから読み込み、キャッシュしました。`;
  });
}

async function selectArtifact(event: Event): Promise<void> {
  const button = event.currentTarget as HTMLButtonElement;
  const runId = Number(button.dataset.run);
  const artifactId = Number(button.dataset.artifact);
  const build = builds.find((build) => build.runId === runId);
  const artifact = build?.artifacts?.find((artifact) => artifact.id === artifactId);
  if (!build || !artifact) return;

  await run(`${artifact.name}を準備しています…`, async () => {
    let archive = await getArtifact(artifactId);
    let cached = true;
    if (!archive) {
      archive = await new GitHubClient(token).artifact(parseRepo(listRepo), artifactId);
      await putArtifact(artifactId, archive);
      cached = false;
    }
    const files = await extractFirmware(archive, artifact.name);
    if (!files.length) throw new Error("Artifact内にUF2、BIN、HEX、ZIPがありません");
    selectedBuild = build;
    selectedArtifact = artifact;
    firmwareFiles = files;
    selectedFirmware = files[0];
    status = `${artifact.name}: ${firmwareFiles.length} files${cached ? "（キャッシュ）" : ""}`;
  });
}

async function localFileChanged(event: Event): Promise<void> {
  const input = event.target as HTMLInputElement;
  const file = input.files?.[0];
  if (!file) return;
  await run(`${file.name}を読み込んでいます…`, async () => {
    if (!/\.(uf2|zip)$/i.test(file.name)) throw new Error("選択できるのはUF2またはZIPファイルです");
    if (!file.size || file.size > MAX_LOCAL_SIZE) throw new Error("ファイルは1 byte以上64 MiB以下にしてください");
    const firmware: Firmware = { name: file.name, path: file.name, blob: file, source: "端末内ファイル" };
    firmwareFiles = [firmware];
    selectedFirmware = firmware;
    selectedBuild = undefined;
    selectedArtifact = undefined;
    status = `${file.name}を選択しました。`;
  });
}

function selectFirmware(event: Event): void {
  const index = Number((event.currentTarget as HTMLButtonElement).dataset.firmware);
  selectedFirmware = firmwareFiles[index];
  status = selectedFirmware ? `${selectedFirmware.name}を選択しました。` : status;
  render();
}

async function convertBlueboot(): Promise<void> {
  if (!selectedFirmware) return;
  await run("UF2をBlueboot OTA ZIPへ変換しています…", async () => {
    const converted = await packageBlueboot(selectedFirmware!);
    firmwareFiles = [converted, ...firmwareFiles];
    selectedFirmware = converted;
    status = `${converted.name}を端末内で生成しました。`;
  });
}

async function saveFirmware(): Promise<void> {
  if (!selectedFirmware) return;
  await run(`${selectedFirmware.name}を保存しています…`, async () => {
    if (window.showDirectoryPicker && selectedFirmware!.name.toLowerCase().endsWith(".uf2")) {
      const directory = await window.showDirectoryPicker({ mode: "readwrite" });
      const handle = await directory.getFileHandle(selectedFirmware!.name, { create: true });
      const writable = await handle.createWritable();
      await writable.write(selectedFirmware!.blob);
      await writable.close();
      status = `${selectedFirmware!.name}を選択したドライブへ保存しました。キーボードの再起動を待ってください。`;
      return;
    }
    download(selectedFirmware!);
    status = `${selectedFirmware!.name}のダウンロードを開始しました。ファイルAppからUF2ドライブへ保存してください。`;
  });
}

async function login(): Promise<void> {
  await run("GitHubログインを開始しています…", async () => {
    token = await deviceLogin(async (code, url) => {
      const dialog = document.querySelector<HTMLDialogElement>("#code-dialog")!;
      const codeButton = document.querySelector<HTMLButtonElement>("#copy-code")!;
      const link = document.querySelector<HTMLAnchorElement>("#open-github")!;
      codeButton.textContent = code;
      codeButton.onclick = () => navigator.clipboard.writeText(code);
      link.href = url;
      document.querySelector<HTMLButtonElement>("#cancel-code")!.onclick = () => dialog.close();
      dialog.showModal();
      await navigator.clipboard.writeText(code).catch(() => undefined);
    });
    viewer = await new GitHubClient(token).viewer();
    localStorage.setItem("github-token", token);
    localStorage.setItem("github-viewer", viewer);
    document.querySelector<HTMLDialogElement>("#code-dialog")?.close();
    status = `${viewer}としてGitHubにログインしました。`;
  });
}

async function saveToken(): Promise<void> {
  const value = (document.querySelector<HTMLInputElement>("#token")?.value || "").trim();
  await run("GitHub tokenを確認しています…", async () => {
    if (!value) throw new Error("GitHub tokenを入力してください");
    viewer = await new GitHubClient(value).viewer();
    token = value;
    localStorage.setItem("github-token", token);
    localStorage.setItem("github-viewer", viewer);
    status = `${viewer}のtokenをこのブラウザに保存しました。`;
    toggleDrawer(false);
  });
}

function logout(): void {
  token = "";
  viewer = "";
  localStorage.removeItem("github-token");
  localStorage.removeItem("github-viewer");
  status = "GitHub tokenを削除しました。";
  render();
}

async function connectCdc(): Promise<void> {
  await run("CDC deviceを選択してください…", async () => {
    usbDeviceName = await cdcConsole.connect(115200, appendUsbLog);
    usbLog += `\n[connected: ${usbDeviceName} @ 115200 baud]\n`;
    status = `${usbDeviceName}のCDC debug consoleへ接続しました。`;
  });
}

async function triggerCdc(): Promise<void> {
  await run("1200 baudで起動するCDC deviceを選択してください…", async () => {
    const name = await cdcConsole.triggerBootloader();
    usbDeviceName = "";
    usbLog += `\n[1200 baud bootloader trigger sent: ${name}]\n`;
    status = `${name}へ1200 baud triggerを送信しました。ブートローダードライブが表示されたらUF2を保存してください。`;
  });
}

async function disconnectCdc(): Promise<void> {
  await cdcConsole.disconnect();
  usbDeviceName = "";
  usbLog += "\n[disconnected]\n";
  status = "CDC deviceを切断しました。";
  render();
}

function clearCdc(): void {
  usbLog = "";
  const output = document.querySelector<HTMLElement>("#usb-log");
  if (output) output.textContent = "";
}

function appendUsbLog(text: string): void {
  usbLog = (usbLog + text).slice(-50000);
  const output = document.querySelector<HTMLElement>("#usb-log");
  if (output) {
    output.textContent = usbLog;
    output.scrollTop = output.scrollHeight;
  }
}

async function run(message: string, action: () => Promise<void>): Promise<void> {
  if (busy) return;
  busy = true;
  status = message;
  render();
  try {
    await action();
  } catch (error) {
    status = error instanceof Error ? error.message : String(error);
  } finally {
    busy = false;
    render();
  }
}

function restoreCachedBuilds(): void {
  try {
    listKey = cacheKey(); listRepo = repoText;
    builds = loadCachedBuilds(listKey, true);
    if (builds.length) {
      status = `前回の${builds.length} buildsをキャッシュから復元しました。`;
      render();
    }
  } catch {
    // Repository may not be valid until the user edits it.
  }
}

function cacheKey(): string {
  const repo = parseRepo(repoText);
  return `${repo.owner}/${repo.repo}:${branch}`;
}

function buildCard(build: Build): string {
  return `<article class="build-card">
    <div class="build-meta"><div><strong>${escapeHtml(build.title)}</strong><span>${escapeHtml(build.branch)} · ${escapeHtml(build.sha.slice(0, 7))}</span></div><time>${formatDate(build.createdAt)}</time></div>
    <div class="artifacts">${!build.artifacts ? `<button class="secondary" data-load-run="${build.runId}" ${busy ? "disabled" : ""}>Artifactを表示</button>` : !build.artifacts.length ? "有効なArtifactはありません" : build.artifacts.map((artifact) => `<button ${busy ? "disabled" : ""} data-run="${build.runId}" aria-pressed="${selectedArtifact?.id === artifact.id}" data-artifact="${artifact.id}" class="artifact ${selectedArtifact?.id === artifact.id ? "selected" : ""}"><span><strong>${escapeHtml(artifact.name)}</strong><small>${formatBytes(artifact.size)}</small></span><b aria-hidden="true">${selectedArtifact?.id === artifact.id ? "✓" : "→"}</b></button>`).join("")}</div>
  </article>`;
}

function firmwareCard(firmware: Firmware): string {
  return `<article class="selected-firmware"><span class="file-type">${escapeHtml(extension(firmware.name))}</span><div><small>選択中のファームウェア</small><strong>${escapeHtml(firmware.name)}</strong><span>${escapeHtml(firmware.source)} · ${formatBytes(firmware.blob.size)}</span></div><i>✓</i></article>`;
}

function firmwareRow(firmware: Firmware, index: number): string {
  return `<button class="firmware-row ${firmware === selectedFirmware ? "selected" : ""}" aria-pressed="${firmware === selectedFirmware}" data-firmware="${index}"><span>${escapeHtml(firmware.name)}</span><small>${formatBytes(firmware.blob.size)}</small></button>`;
}

function emptyState(text: string): string {
  return `<div class="empty"><span>⌁</span><p>${escapeHtml(text)}</p></div>`;
}

function saveButtonLabel(): string {
  if (!selectedFirmware) return "ファームウェアを選択";
  if (window.showDirectoryPicker && selectedFirmware.name.toLowerCase().endsWith(".uf2")) return "UF2ドライブへ保存";
  return "ファイルをダウンロード";
}

function download(firmware: Firmware): void {
  const type = firmware.name.toLowerCase().endsWith(".zip") ? "application/zip" : "application/octet-stream";
  const file = new File([firmware.blob], firmware.name, { type });
  const url = URL.createObjectURL(file);
  const anchor = document.createElement("a");
  anchor.href = url;
  anchor.download = firmware.name;
  document.body.append(anchor);
  anchor.click();
  anchor.remove();
  setTimeout(() => URL.revokeObjectURL(url), 1000);
}

function toggleDrawer(open: boolean): void {
  document.querySelector("#drawer")?.toggleAttribute("inert", !open);
  document.querySelectorAll("main, .topbar").forEach((element) => element.toggleAttribute("inert", open));
  document.querySelector("#menu-button")?.setAttribute("aria-expanded", String(open));
  document.querySelector("#drawer")?.classList.toggle("open", open);
  document.querySelector("#scrim")?.classList.toggle("open", open);
  document.querySelector("#drawer")?.setAttribute("aria-hidden", String(!open));
  document.querySelector<HTMLElement>(open ? "#close-menu" : "#menu-button")?.focus();
}

function formatDate(value: string): string {
  if (!value) return "unknown time";
  return new Intl.DateTimeFormat("ja-JP", { month: "2-digit", day: "2-digit", hour: "2-digit", minute: "2-digit" }).format(new Date(value));
}

function formatBytes(value: number): string {
  if (value < 1024) return `${value} B`;
  if (value < 1024 * 1024) return `${(value / 1024).toFixed(1)} KiB`;
  return `${(value / 1024 / 1024).toFixed(1)} MiB`;
}

function extension(name: string): string {
  return name.split(".").pop()?.toUpperCase() || "FILE";
}

function escapeHtml(value: string): string {
  return value.replace(/[&<>'"]/g, (character) => ({ "&": "&amp;", "<": "&lt;", ">": "&gt;", "'": "&#39;", '"': "&quot;" })[character]!);
}

function registerServiceWorker(): void {
  if ("serviceWorker" in navigator && import.meta.env.PROD) {
    navigator.serviceWorker.register(`${import.meta.env.BASE_URL}sw.js`, { scope: "./" }).catch(() => undefined);
  }
}
