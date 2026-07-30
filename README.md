# ZMK Helper Android

Android 端末から ZMK キーボードのファームウェアを更新するためのアプリです。

GitHub Actions の成果物を読み込み、生成された `.uf2` / `.bin` / `.hex` / BLE OTA 用 `.zip` を選択して、USB ブートローダーまたは BLE OTA で書き込みます。

## できること

- GitHub repo を `owner/repo` または `https://github.com/owner/repo` 形式で登録
- GitHub OAuth Device Flow でログイン
- private repo / Actions artifact download / rate limit 回避に対応
- ブランチをリストから選択
- GitHub Actions の successful run を読み込み
- artifact をビルド単位でグループ表示
- artifact 内の複数ファームウェアから対象ファイルを選択
- artifact ZIP、展開済みファームウェア、build artifact 一覧をキャッシュ
- USB mass storage bootloader への UF2 書き込み
- BLE OTA DFU ZIP の書き込み
- UF2 から Blueboot 用 BLE OTA ZIP をアプリ内で生成
- 書き込み進捗表示

## 基本の流れ

1. アプリを開く
2. `Menu` または左端スワイプでメニューを開く
3. 必要なら `Login with GitHub` でログイン
4. GitHub repo URL または `owner/repo` を入力
5. 必要なら `Select Branch` でブランチを選択
6. `Load successful Actions builds` を押す
7. `Select Artifact` で使うビルド成果物を選択
8. `Select Firmware` で対象ファームウェアを選択
9. USB 書き込みまたは BLE OTA を実行

## GitHub ログイン

public repo だけなら未ログインでも一部操作できますが、Actions artifact のダウンロードや private repo ではログインが必要です。

1. `Menu` を開く
2. `Login with GitHub` を押す
3. 表示されたコードは自動でクリップボードにコピーされる
4. 開いた GitHub の device login 画面でコードを入力
5. 認可が完了すると token がアプリに保存される

token を消したい場合は `Clear GitHub token` を使います。

## ブランチとビルドの選択

`Select Branch` は GitHub の branch API からブランチ一覧を読み込みます。

branch API が使えない場合は、最近の GitHub Actions run からブランチ名を推定して表示します。

`Load successful Actions builds` は successful run の artifact を読み込みます。artifact は GitHub Actions の run ごとにグループ表示されます。

同じ repo / branch の build artifact 一覧はキャッシュされるため、次回起動時は前回の一覧がすぐ表示されます。GitHub から最新状態を取り直したい場合は、もう一度 `Load successful Actions builds` を押します。

## USB ブートローダー書き込み

USB mass storage bootloader に `.uf2` をコピーする更新方法です。

1. artifact と `.uf2` ファイルを選択
2. `Start write mode and wait for bootloader` を押す
3. キーボードをブートローダーモードにする
4. Android が新しい removable drive を認識するまで待つ
5. 初回のみ、Android のフォルダ選択でブートローダードライブを選ぶ
6. アプリが選択済みファームウェアを書き込む

書き込みモード中は、既に接続されている removable drive は無視します。書き込みモードを有効にした後、新しく接続・マウントされた drive をブートローダードライブ候補として扱います。

候補名に以下が含まれる場合はブートローダーらしい drive として優先します。

- `XIAO`
- `BOOT`
- `UF2`
- `RP2040`
- `nRF52`
- `nice!nano`

Android の制限により、アプリは未許可の USB mass storage に直接書き込めません。初回は Storage Access Framework のフォルダ権限が必要です。`Register bootloader folder` はメニュー内に残してありますが、通常は書き込みモード中にブートローダードライブを検知したタイミングで選択すれば十分です。

## BLE OTA 書き込み

BLE OTA は Nordic / Adafruit DFU ZIP を使う更新方法です。`.uf2` をそのまま BLE 送信する機能ではありません。

1. artifact と BLE OTA 用 `.zip` を選択
2. キーボードを BLE DFU モードにする
3. `Scan BLE OTA devices` を押す
4. 表示された `AdaDFU` / DFU デバイスを選択
5. `Write selected ZIP over BLE OTA` を押す

保存済み BLE デバイスは、OTA 開始前に再スキャンして選び直す必要があります。DFU モードでは BLE アドレスや advertising 状態が変わる場合があり、古い選択のまま接続すると `GATT CONN TIMEOUT (147)` になりやすいためです。

途中で転送が失敗した場合、進捗が出ていた状態なら同じ `Write selected ZIP over BLE OTA` で再試行できます。接続前にタイムアウトした場合は、キーボードを DFU モードにしたまま、もう一度スキャンして現在見えている DFU デバイスを選択してください。

BLE OTA は速度優先の設定です。

- MTU `517` を要求
- Packet Receipt Notification は無効
- DFU retry は `5` 回

電波状態が悪いと失敗しやすくなります。失敗する場合は、スマホとキーボードを近づけ、他の BLE 接続を減らし、キーボードを DFU モードに入れ直してから再試行してください。

## Blueboot UF2 変換

[`zmk-feature-blueboot`](https://github.com/te9no/zmk-feature-blueboot) を使うキーボード向けに、選択した UF2 から BLE OTA ZIP をアプリ内で作れます。

1. artifact から対象 `.uf2` を選択
2. `Convert selected UF2 to BLE OTA ZIP` を押す
3. 生成された `*-blueboot.zip` が自動選択される
4. キーボードを Blueboot / BLE DFU モードにする
5. `Scan BLE OTA devices` でデバイスを選択
6. `Write selected ZIP over BLE OTA` を押す

アプリ内変換のデフォルト値は以下です。

- SoftDevice requirement: `0x0123`
- Device type: `0x0052`

これは S140 7.3.0 / nRF52840 想定です。別の互換値が必要なボードでは、ファームウェアビルド側で正しい DFU ZIP を生成してください。

## キャッシュ

アプリは以下をキャッシュします。

- repo / branch ごとの build artifact 一覧
- GitHub artifact ZIP
- artifact から展開したファームウェアファイル
- Blueboot 変換で作成した ZIP

同じ artifact を選び直した場合は、まず展開済みファイルを使い、なければ cached ZIP を使い、それもなければ GitHub から再ダウンロードします。

選択中の artifact と firmware はアプリ設定に保存されます。USB 接続、外部キーボード入力、Activity resume などで選択が最新 artifact に戻らないようにしています。

## トラブルシュート

### `Artifact download failed: HTTP 401`

GitHub Actions artifact のダウンロードには認証が必要です。

1. `Menu` を開く
2. `Clear GitHub token`
3. `Login with GitHub`
4. 対象 repo へのアクセスを許可
5. もう一度 build を読み込む

private repo の場合、OAuth token に repo へのアクセス権が必要です。

### `HTTP 400 device flow must be explicitly enabled`

GitHub OAuth App 側で Device Flow が無効です。

GitHub Developer settings で対象 OAuth App を開き、Device Flow を有効にしてください。この設定はユーザーごとではなく OAuth App ごとの設定です。

### `Unable to resolve host github.com`

Android 端末から GitHub の DNS 解決ができていません。

確認する項目:

- Wi-Fi / mobile data
- captive portal login
- Android Private DNS
- VPN
- ad blocker
- network 切り替え

アプリ内の `Run GitHub network diagnostic` で `github.com` / `api.github.com` の DNS と HTTPS 到達性を確認できます。

### ブランチ一覧が出ない

repo 名、token 権限、private repo へのアクセスを確認してください。

branch API が失敗した場合、アプリは recent Actions run からブランチ候補を推定します。それも空の場合は、対象 repo に Actions run がない、または token にアクセス権がありません。

### USB device attached, but drive が見えない

Android が removable volume を公開するまで遅れることがあります。アプリは約 10 秒間 polling します。

改善しない場合:

- write mode を一度やり直す
- キーボードをブートローダーモードに入れ直す
- USB ケーブルを変える
- USB OTG adapter を確認する
- Android のファイル選択画面でブートローダードライブが見えるか確認する

### BLE OTA が遅い / 失敗する

BLE は端末、距離、周辺電波、キーボード側 bootloader 実装の影響を強く受けます。

対処:

- スマホとキーボードを近づける
- キーボードを DFU モードに入れ直す
- `Scan BLE OTA devices` で現在見えている DFU デバイスを選び直す
- 他の BLE 機器との接続を減らす
- 途中まで進んで失敗した場合は、キーボードを DFU モードのまま `Write selected ZIP over BLE OTA` を再実行する

## 開発ビルド

Android Studio でこのディレクトリを開き、`app` module をビルドします。

Gradle wrapper で debug APK を作る場合:

```powershell
.\gradlew.bat assembleDebug
```

Windows で `JAVA_HOME` がない場合は、Android Studio 同梱 JBR を一時的に使えます。

```powershell
$env:JAVA_HOME='C:\Program Files\Android\Android Studio\jbr'
$env:PATH="$env:JAVA_HOME\bin;$env:PATH"
.\gradlew.bat assembleDebug
```

## GitHub Actions release

`.github/workflows/android-release.yml` で署名済み release APK を作成します。

- `workflow_dispatch`: 署名済み release APK を workflow artifact として作成
- `v*` tag push: GitHub Release を作成し、APK を添付

必要な repository secrets:

- `ANDROID_KEYSTORE_BASE64`
- `ANDROID_KEYSTORE_PASSWORD`
- `ANDROID_KEY_ALIAS`
- `ANDROID_KEY_PASSWORD`

例:

```powershell
git tag v0.1.0
git push origin v0.1.0
```

keystore はローカルで作成し、base64 encode した値を `ANDROID_KEYSTORE_BASE64` に保存します。`.jks` / `.keystore` は commit しないでください。
