# バスロケ監視 Windows デスクトップ版

Web 版（https://nara-bus-pulse.base44.app/）を専用ウィンドウで開く Windows 用アプリです（Electron）。

- ログイン状態は端末に保存されます（「ヘルプ → 保存データを消去して再起動」でログアウト）。
- 外部サイトへのリンクは既定のブラウザで開きます。
- 現在地・通知・カメラ・USB（カメラ統合コンソールの USB 接続）は本体のページにだけ許可します。
- CSV・PDF・RAW などの保存時は保存先を選べます。

## ビルド

```
cd desktop
npm install
npm run dist     # dist/NaraBusPulse-<version>-win-x64.exe（インストール不要の単体実行形式）
```

Linux / macOS からでもビルドできます（コード署名はしていないため、初回起動時に Windows の SmartScreen が確認を出します）。

接続先を変える場合は環境変数 `NARA_BUS_URL` を指定して起動します。
