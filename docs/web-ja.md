# Web / PWA版ガイド

Web版はAndroidとiPadのブラウザで、GitHub Actionsまたは端末内のZMKファームウェアを選択・変換・保存するためのPWAです。BLE OTAは現時点では対象外です。

## 対応機能

- GitHub OAuth Device FlowまたはGitHub tokenでログイン
- public/private repositoryのブランチと成功したActions buildを取得
- build単位でartifactを表示
- artifact ZIPを端末内にキャッシュし、UF2/BIN/HEX/ZIPを展開
- 端末内のUF2/ZIPをrepositoryなしで選択
- UF2をBlueboot用ZIPへ端末内で変換
- Android Chromiumでは選択したUF2ドライブへ直接保存
- iPadなどDirectory Picker非対応環境ではUF2をダウンロードし、ファイルAppでブートローダードライブへ移動
- Android ChromeのWebUSBでCDC debug logを表示
- Android ChromeのWebUSBで1200 baud bootloader triggerを送信
- ホーム画面へインストール可能

## AndroidでのUF2更新

1. Web版をChromeで開きます。
2. GitHub buildまたは`端末からUF2 / ZIPを選択`で対象UF2を選びます。
3. キーボードをブートローダーモードにします。
4. `UF2ドライブへ保存`を押します。
5. Androidのフォルダ選択画面で`XIAO-SENSE`、`XIAO-BOOT`、`BOOT`、`UF2`などのブートローダードライブを選択します。
6. 保存完了後、キーボードが再起動するまでケーブルを外さないでください。

ブラウザはUSBドライブを無断で監視できないため、Androidネイティブ版のような接続直後の完全自動書き込みではありません。

CDC対応ファームウェアでは、画面下部の`CDC Debug Console`から115200 baudログへ接続できます。`1200 baudでBootloader起動`を選択すると、対象CDC deviceへ1200 baudとDTR切替を送信します。ブートローダードライブが表示された後は、UF2保存ボタンからフォルダを選択してください。

## iPadでのUF2更新

1. SafariでWeb版を開き、UF2を選択します。
2. キーボードをUSB-C経由で接続し、ブートローダーモードにします。
3. `ファイルをダウンロード`を押します。
4. SafariのダウンロードまたはファイルAppからUF2を表示します。
5. `場所`に表示されたブートローダードライブへUF2を移動またはコピーします。

iPad、キーボード、ハブの組み合わせによっては給電能力が不足します。その場合は給電対応USBハブを使用してください。

iPad SafariはWebUSBに対応していないため、CDC debug consoleと1200 baud triggerは利用できません。

## GitHubログイン

配信環境にOAuth proxyが設定されている場合は、メニューの`GitHubでログイン`を使用できます。表示されたコードはクリップボードへコピーされます。

OAuth proxy未設定時はfine-grained personal access tokenをメニューから入力できます。private repositoryの場合は対象repositoryのActionsとContentsを読み取れる権限が必要です。Tokenはブラウザのlocal storageへ保存されるため、共有端末では利用後に`ログアウト / token削除`を実行してください。

## キャッシュ

- build一覧は30分間再利用します。
- 一度取得したartifact ZIPはブラウザのIndexedDBへ保存します。
- ブラウザのサイトデータを削除するとキャッシュとtokenも削除されます。
- キャッシュ済みartifactでも、別のartifactを取得する場合やGitHub側を再読込する場合は通信が発生します。

## 開発

```powershell
cd web
npm install
npm run dev
```

production buildとテスト:

```powershell
npm test
npm run build
```

OAuth proxyの配信方法は[`web/worker/README.md`](../web/worker/README.md)を参照してください。
