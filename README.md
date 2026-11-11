# 奈良バスパルス Android アプリ

https://nara-bus-pulse.base44.app/ を全画面の WebView で表示する Android アプリ (APK) です。

## 機能
- アプリ起動で Web アプリを全画面表示（アドレスバーなし）
- 戻るボタンでページ履歴を戻る
- 位置情報（現在地）の利用に対応（初回に許可ダイアログ）
- カメラ（メーター読み取りなど）に対応（初回に許可ダイアログ）
- ファイル選択（CSV 取り込みなど）に対応
- CSV / JSON バックアップ / PDF などの書き出しを「ダウンロード」フォルダに保存（Android 9 以前は保存先を選択）
- ログイン状態（Cookie / localStorage）を保持
- base44 以外のリンク・電話・地図などは外部アプリで開く
- オフライン時は再読み込みボタンを表示

## APK の入手方法

### GitHub Actions（おすすめ）
push するたびに自動でビルドされます。
GitHub の **Actions → Build APK → 最新の実行 → Artifacts** から `nara-bus-pulse-apk` をダウンロードしてください。
手動実行は Actions 画面の **Run workflow** から。

### ローカルでビルド
JDK 17 以上と Android SDK (platform 34) が必要です。

```bash
./gradlew assembleRelease
# → app/build/outputs/apk/release/app-release.apk
```

## インストール
APK をスマホに送り、開いてインストールします。
初回は「提供元不明のアプリ」のインストール許可を求められます。

## 署名鍵について（重要）
署名鍵を設定しない場合はデバッグ鍵で署名されます。デバッグ鍵はビルド環境ごとに異なるため、
**別の環境でビルドした APK は上書きアップデートできません**（一度アンインストールが必要）。

継続的に配布する場合は固定の署名鍵を作成し、GitHub リポジトリの
Settings → Secrets and variables → Actions に以下を登録してください。

| Secret | 内容 |
| --- | --- |
| `KEYSTORE_BASE64` | `base64 -w0 release.jks` の出力 |
| `KEYSTORE_PASSWORD` | キーストアのパスワード |
| `KEY_ALIAS` | キーのエイリアス |
| `KEY_PASSWORD` | キーのパスワード |

鍵の作成例:
```bash
keytool -genkeypair -v -keystore release.jks -alias narabus -keyalg RSA -keysize 2048 -validity 10000
```

## カスタマイズ
- アプリ名・URL: `app/src/main/res/values/strings.xml`
- テーマカラー: `app/src/main/res/values/colors.xml`
- アイコン: `app/src/main/res/drawable/ic_launcher_foreground.xml`
- パッケージ名: `app/build.gradle` の `applicationId`

## 制限事項
- **Google ログイン**: Google は WebView 内でのログインを禁止しているため、「Continue with Google」を押すと案内を表示します。メールアドレスとパスワードでログインしてください。
- **プッシュ通知**: Web プッシュ（Service Worker + PushManager）は Android の WebView では使えません。
- **共有ボタン**: Web Share API が無いため、サイト側の処理でリンクのコピーになります。

Google ログインやプッシュ通知が必要な場合は、Chrome で動く TWA（Trusted Web Activity）方式への切り替えを検討してください。
