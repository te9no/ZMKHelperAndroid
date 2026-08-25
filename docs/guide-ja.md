# ZMK Helper Android ユーザーガイド

[README](../README.md) | [English](guide-en.md) | [開発者向け](development.md)

## 1. 更新方式を選ぶ

| 更新方法 | 選択するファイル | キーボード側の条件 |
| --- | --- | --- |
| USB UF2 | `.uf2` | USB mass storage bootloader |
| BLE OTA | Nordic/Adafruit DFU形式の`.zip` | BLE DFU対応bootloader |
| Blueboot OTA | `.uf2`からアプリで変換した`.zip` | `zmk-feature-blueboot`互換環境 |

`.bin`と`.hex`もartifact内に表示されますが、UF2ドライブへのコピーやBLE DFUにそのまま使用できるとは限りません。通常のUF2 bootloaderでは`.uf2`、BLE OTAでは対応するDFU `.zip`を使用してください。

## 2. インストールと権限

1. [Releases](https://github.com/te9no/ZMKHelperAndroid/releases)から最新の`zmk-helper-*-release.apk`を取得します。
2. AndroidでAPKを開き、必要なら使用したブラウザーまたはファイルアプリに「不明なアプリのインストール」を許可します。
3. ZMK Helperをインストールして起動します。

更新時に設定とキャッシュを残す場合は、旧版をアンインストールせず上書きインストールしてください。

機能を初めて使用したとき、Androidから権限を求められます。

- USBアクセス: CDC serial interfaceへの接続
- Bluetooth scan/connect: BLE DFUデバイスの検索と接続
- 通知: BLE OTAをforeground serviceで継続して進捗表示
- フォルダアクセス: UF2 bootloaderドライブへの書き込み

拒否した権限はAndroidの「設定 > アプリ > ZMK Helper > 権限」から変更できます。

## 3. GitHubへログインする

public repositoryの一覧は未ログインでも取得できる場合がありますが、GitHub Actions artifactのダウンロードとprivate repositoryには認証が必要です。

1. `Menu`または画面左端から右へのスワイプでサイドメニューを開きます。
2. `Login with GitHub`を押します。
3. device codeが表示され、クリップボードへ自動コピーされます。
4. 開いたGitHubのdevice activation画面でログインし、必要なら二要素認証を完了します。
5. codeを入力してアクセスを承認します。
6. アプリへ戻り、`GitHub login complete`が表示されるまで待ちます。

tokenはAndroidのアプリ専用設定に保存され、画面には表示されません。認証をやり直す場合は`Clear GitHub token`の後に再ログインします。

`HTTP 400 device flow must be explicitly enabled`が表示される場合、OAuth App管理者がGitHub Developer settingsでDevice Flowを有効にする必要があります。OAuth client IDはアプリ登録者が管理し、利用者ごとには発行しません。

## 4. Repositoryとファームウェアを選ぶ

画面は`01 Firmware source`、`02 Choose firmware`、`03 Update keyboard`の順に操作します。

前の手順が完了するまで、利用できないボタンは薄く表示されます。たとえばbuildを取得するまで`Select Artifact`、artifactを展開するまで`Select Firmware`、UF2またはDFU ZIPを選ぶまでwriteボタンは有効になりません。

### 4.1 Repositoryとbranch

1. `GitHub repo URL or owner/repo`へ`owner/repo`または`https://github.com/owner/repo`を入力します。
2. `Save repo`を押します。
3. `Select Branch`を押し、対象branchを選択します。
4. branchを限定しない場合は`All branches`を選びます。
5. `Load successful Actions builds`を押します。

branch APIが失敗した場合、アプリは最近のActions runからbranch名を推定します。private repositoryでは、ログインしたGitHubアカウントにrepositoryへのアクセス権が必要です。

### 4.2 Artifactとfirmware

1. `Select Artifact`を押します。
2. workflow runごとにグループ化された一覧からartifactを選びます。
3. branch、commit、build日時、artifact名を確認します。
4. `Select Firmware`を押し、対象keyboardのファイルを選びます。
5. `03 Update keyboard`内の選択情報を再確認します。

artifactとfirmwareの要約欄自体もタップできます。左右分割keyboardではleft/right、central/peripheralを間違えないようにしてください。書き込み完了後やUSB接続後も選択情報は保持されます。

## 5. USB UF2で更新する

### 準備

- 対象keyboard用の`.uf2`を選択する
- USB host/OTG対応Android端末を使用する
- 充電専用ではないデータ通信対応ケーブルを使用する
- keyboardをbootloader modeへ移行する方法を確認する

bootloaderの起動方法はboardによって異なります。一般的にはresetのダブルタップ、BOOTボタン、またはZMKのbootloader behaviorを使用します。

### 自動書き込み

1. `03 Update keyboard`で`USB UF2`を選びます。
2. `Wait for bootloader and write`を押します。
3. 押した後でkeyboardをbootloader modeにします。
4. アプリが新しく接続・マウントされたremovable driveを検知します。
5. 初回または保存済み権限が無効な場合、Androidのフォルダ選択画面で`XIAO`、`BOOT`、`UF2`などのbootloaderドライブを選びます。
6. 「このフォルダを使用」を許可すると書き込みが始まります。
7. progress barとstatusを確認します。
8. `Firmware written`が表示されてkeyboardが再起動するまでケーブルを抜かないでください。

誤書き込み防止のため、書き込みモード開始前から存在するremovable driveは新規bootloader候補として扱いません。必ず`Wait for bootloader and write`を先に押してください。

Androidがbootloaderを`StorageVolume`として通知しない場合、USB mass storage接続またはCDC triggerを検知してから約3秒後に保存済みフォルダ権限で直接書き込みを試します。権限が無効ならフォルダ選択を自動表示します。通常の検出待機は最大約60秒です。

`Write now to registered drive`は、bootloaderドライブが既にマウントされ、現在のドライブに対する保存済み権限が有効な場合に使用します。

## 6. CDC Debugと1200 baud書き込み

keyboard firmwareでUSB CDC ACMが有効になっている必要があります。

### ログ表示

1. keyboardをUSB接続します。
2. `Open CDC Debug Console`を押します。
3. `Select CDC Port`から対象portを選び、USBアクセスを許可します。
4. 115200 bpsのログを確認します。

StudioとCDC Debugを同時に有効にした構成では、同じUSB deviceに複数portが表示されます。ログが流れない場合は`Port 1`と`Port 2`を切り替えてください。`Reconnect`は再接続、`Clear Log`は表示ログの消去です。

### Bootloader triggerと自動書き込み

1. 対象`.uf2`を先に選びます。
2. CDC consoleで`1200 baud + Auto Write`を押します。
3. USBケーブルを接続したまま待ちます。
4. アプリが同じUSB deviceのCDC portを順番に試します。
5. bootloader起動後、USB UF2と同じ処理で自動書き込みします。

CDC Debugだけを有効にしたfirmwareではログ表示はできてもtriggerが動作しない場合があります。keyboard側でCDC ACM bootloader triggerを有効にしてください。

## 7. BLE OTAで更新する

BLE OTAは`.uf2`をそのまま送信しません。対象bootloader用に生成されたNordic/Adafruit DFU `.zip`が必要です。GitHub Actionsからダウンロードしたartifact ZIPそのものではなく、manifestとfirmwareを含むDFU packageを選択します。

1. 対象のDFU `.zip`を選択します。
2. `03 Update keyboard`で`BLE OTA`を選びます。
3. keyboardをBLE DFU modeにします。
4. `Scan BLE OTA devices`を押します。
5. `AdaDFU`などの対象DFU deviceを選び、必要なBluetooth/通知権限を許可します。
6. `Write selected ZIP over BLE OTA`を押します。
7. 完了してkeyboardが再起動するまで電源を切らないでください。

OTA開始前には毎回再スキャンし、現在advertiseしているDFU deviceを選び直してください。DFU modeではBLE addressが変わり、古い選択では`GATT CONN TIMEOUT (147)`になりやすいためです。

途中まで進んで失敗した場合は、keyboardをDFU modeに保って同じwriteボタンで再試行できます。接続前の失敗では再スキャンしてください。

速度優先のためMTU 517を要求し、Packet Receipt Notificationを無効化し、DFU retryを5回に設定しています。実効速度は端末、距離、電波干渉、bootloader実装に依存します。

## 8. UF2をBlueboot ZIPへ変換する

この機能は[`zmk-feature-blueboot`](https://github.com/te9no/zmk-feature-blueboot)を使用するkeyboard向けです。すべてのBLE bootloaderで利用できる変換ではありません。

1. 対象`.uf2`を選びます。
2. `BLE OTA`へ切り替えます。
3. `Convert selected UF2 to BLE OTA ZIP`を押します。
4. 生成された`*-blueboot.zip`が自動選択されたことを確認します。
5. keyboardをBlueboot/BLE DFU modeにし、scan、device選択、writeを実行します。

既定値はSoftDevice requirement `0x0123`、device type `0x0052`で、S140 7.3.0/nRF52840を想定しています。異なる互換値が必要ならbuild側で正しいDFU ZIPを生成してください。

## 9. キャッシュと保存データ

通信量と待ち時間を減らすため、次を保存します。

- repository/branchごとのbuild artifact一覧
- GitHub artifact ZIPと展開済みfirmware
- Blueboot変換済みZIP
- 選択中のrepository、branch、artifact、firmware、更新方式
- GitHub tokenとbootloaderフォルダ権限

同じartifactは、展開済みファイル、cached ZIP、GitHub downloadの順に利用します。最新情報へ更新するには`Load successful Actions builds`を再実行します。アプリデータの消去またはアンインストールで、設定、token、選択情報、cacheは削除されます。

## 10. トラブルシューティング

### Artifact download failed: HTTP 401

`Clear GitHub token`を実行して再ログインし、private repositoryへのアクセス権を確認してからbuildを読み直します。

### Unable to resolve host github.com

`Run GitHub network diagnostic`を実行し、Wi-Fi/mobile data、captive portal、Private DNS、VPN、ad blockerを確認します。Wi-Fiとmobile dataの切り替えも試してください。

### ブランチ一覧が表示されない

repository表記、token権限、repository内のbranchとActions runの存在を確認し、token更新後に`Select Branch`を開き直します。

### USB接続されたがドライブが見えない

- 最大約60秒待つ
- AndroidのファイルアプリでUF2ドライブが見えるか確認する
- データ通信対応ケーブルとOTG adapterを使う
- write modeを先に有効化してbootloaderを起動し直す
- フォルダ選択では内部storageでなく現在のUF2 driveを選ぶ

### Bootloaderは起動したが書き込まれない

- `.uf2`が選択済みか確認する
- statusのフォルダ選択要求を確認する
- 現在のbootloader driveを選び直す
- `Register bootloader folder`で古い保存権限を置き換える
- write modeを再度有効にしてbootloaderを起動する

### CDCログまたは1200 baud triggerが動かない

別CDC portの選択、USB権限、データ通信対応ケーブル、firmwareのCDC Debug設定を確認します。triggerにはCDC ACM bootloader triggerが別途必要です。

### BLE OTAが遅い、失敗する

端末とkeyboardを近づけ、DFU modeへ入れ直し、再スキャンして現在のdeviceを選びます。他のBluetooth接続や2.4 GHz干渉を減らし、DFU ZIPが対象bootloader用か確認してください。

## 11. 更新前チェックリスト

- [ ] repository、branch、commitが意図したもの
- [ ] artifactとfirmware名がkeyboard/sideに一致
- [ ] USBはUF2、BLEはDFU ZIPを選択
- [ ] Android端末とkeyboardの電池残量が十分
- [ ] USB cableがデータ通信対応、またはBLE距離が近い
- [ ] 完了または再起動まで接続を切らない
