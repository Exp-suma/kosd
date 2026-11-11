# 奈良バスパルス Android アプリ（TWA）

https://nara-bus-pulse.base44.app/ を **Trusted Web Activity (TWA)** として動かす Android アプリです。
端末の Chrome エンジンでサイトを全画面表示するため、Web 版の機能がそのまま使えます。

- プッシュ通知（「奈良バスパルス」アプリの通知として届く）
- Google ログイン
- 現在地、カメラ、ファイル選択、CSV / PDF のダウンロード、共有ボタン
- サイトのリンク (nara-bus-pulse.base44.app) をこのアプリで開く

> Chrome（または TWA 対応ブラウザ）が端末に入っている必要があります。

## 全画面にするための設定（必須）
TWA は「このアプリとサイトが同じ持ち主である」ことをサイト側で証明できたときだけ、
アドレスバーなしの全画面で表示されます。証明できない場合は上部に URL バーが出ます（機能は動きます）。

サイトの `https://nara-bus-pulse.base44.app/.well-known/assetlinks.json` で、
[`twa/assetlinks.json`](twa/assetlinks.json) の内容をそのまま返すようにしてください。
（Base44 のエディタ / AI に「`/.well-known/assetlinks.json` で次の JSON を `application/json` で返して」と依頼する）

確認: `https://digitalassetlinks.googleapis.com/v1/statements:list?source.web.site=https://nara-bus-pulse.base44.app&relation=delegate_permission/common.handle_all_urls`
に `app.narabuspulse` が出れば OK。設定後はアプリを一度アンインストールして入れ直すと確実です。

## 署名鍵（重要）
`twa/assetlinks.json` のフィンガープリントは、配布用の署名鍵 `nara-bus-pulse-release.jks` のものです。
**鍵はリポジトリに入れていません。** なくすと同じアプリとして更新できなくなるので、安全な場所に保管してください。

GitHub Actions で同じ鍵を使ってビルドするには、Settings → Secrets and variables → Actions に登録します。

| Secret | 内容 |
| --- | --- |
| `KEYSTORE_BASE64` | `base64 -w0 nara-bus-pulse-release.jks` の出力 |
| `KEYSTORE_PASSWORD` | キーストアのパスワード |
| `KEY_ALIAS` | `narabus` |
| `KEY_PASSWORD` | キーのパスワード（キーストアと同じ） |

Secrets 未登録のビルドはデバッグ鍵で署名されるため、全画面表示にはなりません（URL バー表示）。

## APK の入手
- GitHub Actions: **Actions → Build APK → 最新の実行 → Artifacts**
- ローカル: JDK 17 以上と Android SDK (platform 36) を用意して
  ```bash
  KEYSTORE_FILE=... KEYSTORE_PASSWORD=... KEY_ALIAS=narabus KEY_PASSWORD=... ./gradlew assembleRelease
  # → app/build/outputs/apk/release/app-release.apk
  ```

## カスタマイズ
- アプリ名・URL: `app/src/main/res/values/strings.xml`
- ステータスバーの色: `app/src/main/res/values/colors.xml`
- アイコン: `app/src/main/res/drawable/ic_launcher_foreground.xml`
- 通知アイコン: `app/src/main/res/drawable/ic_notification.xml`
