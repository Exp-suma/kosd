# バスロケ監視（奈良交通 運行管理）アプリ

https://nara-bus-pulse.base44.app/ の全機能を使えるアプリです。

| 種類 | 場所 | 内容 |
| --- | --- | --- |
| Android（ネイティブ） | [`app/`](app) | Kotlin + Jetpack Compose で全 142 画面を作り直したアプリ。アプリ内ブラウザは使いません。 |
| Windows（デスクトップ） | [`desktop/`](desktop) | Web 版を専用ウィンドウで開くアプリ（Electron）。 |
| サーバー側の窓口 | [`base44-gateway/`](base44-gateway) | ネイティブアプリが Web 版と同じサーバー関数を呼ぶための入口（`/api/native/rpc`）。 |

画面ごとの対応表は [`docs/PARITY.md`](docs/PARITY.md)、設計は [`docs/NATIVE.md`](docs/NATIVE.md) を参照してください。

## Android アプリ

- 通信は Web 版と同じサーバー関数を `/api/native/rpc` 経由で呼びます（API キーはアプリに入れていません）。
- Web 版の計算ロジック（`src/lib`）は 1 本の JavaScript にまとめてアプリ内で実行するため、結果は Web 版と一致します。
- 地図は OpenStreetMap（osmdroid）、3D シミュレーターは Canvas の透視投影、カメラは Camera2 / USB Host API を使います。
- リリース版はコード縮小（R8）とリソース縮小を有効にしています（APK 約 7.5MB）。

### 本番で使うための準備（必須）

ネイティブアプリは本番サイトの `/api/native/rpc` を呼びます。Base44 のエディタで **Publish** を押して、
この窓口を含む版を本番に公開してください。公開前はログイン後の画面でデータを取得できません。

### ビルド

JDK 17 以上と Android SDK（platform 37）を用意します。

```bash
# 署名付きリリース版（本番向け）
KEYSTORE_FILE=... KEYSTORE_PASSWORD=... KEY_ALIAS=narabus KEY_PASSWORD=... ./gradlew assembleRelease
# → app/build/outputs/apk/release/app-release.apk

# デバッグ版（公開前のプレビューに接続する場合は DEV_BASE_URL を指定）
DEV_BASE_URL=https://... ./gradlew assembleDebug
```

共通ロジック（`app/src/main/assets/logic.js`）は Web 版のソースから作ります。

```bash
node tools/logic-bundle/build.mjs <Web版のソースのフォルダ> app/src/main/assets/logic.js
```

### 署名鍵（重要）

配布用の署名鍵 `nara-bus-pulse-release.jks` は**リポジトリに入れていません**。
なくすと同じアプリとして更新できなくなるので、安全な場所に保管してください。
環境変数を指定しないビルドはデバッグ鍵で署名されます。

### プッシュ通知（未対応の範囲）

アプリを開いている間の通知（車両通知・雨雲・乗車予定など）は動きます。
アプリを閉じている間に届くプッシュ通知（Firebase Cloud Messaging）は**まだ組み込んでいません**。
Firebase プロジェクトの `google-services.json` が必要です。サービスアカウントの JSON は Base44 の Secrets に登録し、
リポジトリやチャットには貼らないでください。

## Windows アプリ

```bash
cd desktop
npm install
npm run dist   # → desktop/dist/NaraBusPulse-<version>-win-x64.exe（インストール不要）
```

コード署名はしていないため、初回起動時に Windows の SmartScreen が確認を出します。
