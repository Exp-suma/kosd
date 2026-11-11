# ネイティブ版の構成

## 全体
- **画面**: Kotlin + Jetpack Compose（Material 3）。Web 版の各ページを 1 画面ずつ作り直す（対応表: `docs/PARITY.md`）。
- **データ・処理**: Web 版と同じ Base44 アプリのサーバー処理をそのまま使う。
  - 既存のサーバー関数（約 310 個）は `POST /api/native/rpc {fn, data}` で呼ぶ。
    認証・入力チェック・権限確認はサーバー側の既存コードがそのまま行う。
  - ログイン・登録・エンティティ・ファイルアップロードは Base44 標準 API（SDK と同じ）。
- **Base44 側に追加するファイル**: `base44-gateway/` にある 3 ファイル（既存コードは変更しない）。
  - `src/routes/api/native/rpc.js` … サーバー関数を JSON で呼ぶ API
  - `src/routes/api/native/auth-callback.js` … Google などのログイン後にアプリへ戻す
  - `src/lib/native/registry.js` … サーバー関数の一覧（`gen_registry.py` で自動生成）

## コード
- `core/` 通信（Api）、ログイン状態（Session）、JSON 補助
- `nav/` ドロワー（Web と同じメニュー構成）、画面一覧（Routes.kt は Web から自動生成）
- `ui/kit/` 共通部品（Panel・StatStrip・一覧行・読み込み/エラー表示）
- `feature/<機能>/` 各画面
