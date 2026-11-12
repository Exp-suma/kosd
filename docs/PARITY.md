# ネイティブ移植の進捗（Web 版との機能対応表）

Web 版 `src/routes/_authed/` の全画面と、ネイティブ版の対応状況。

| 状態 | ネイティブ route | 画面 | Web のファイル | 補足 |
| --- | --- | --- | --- | --- |
| ✅ 移植済み | `admin/board` | 掲示板・車両情報の管理 | `admin.board.jsx` |  |
| ✅ 移植済み | `ai` | AIダッシュボード | `ai.jsx` |  |
| ✅ 移植済み | `ai-search` | AI自然言語検索 | `ai-search.jsx` |  |
| ✅ 移植済み | `all-vehicles` | 全車両マップ | `all-vehicles.jsx` |  |
| ✅ 移植済み | `analysis` | 時間帯別分析 | `analysis.jsx` |  |
| ✅ 移植済み | `analytics/continuity` | 運用の連続性 | `analytics.continuity.jsx` |  |
| ✅ 移植済み | `analytics/fleet` | 車両配置・稼働分析 | `analytics.fleet.jsx` |  |
| ✅ 移植済み | `analytics/headway` | 運行間隔分析 | `analytics.headway.jsx` |  |
| ✅ 移植済み | `analytics` | 運行分析 | `analytics.index.jsx` |  |
| ✅ 移植済み | `analytics/longterm` | 長期統計・ランキング | `analytics.longterm.jsx` |  |
| ✅ 移植済み | `analytics/transfers` | 運行接続・乗り継ぎ分析 | `analytics.transfers.jsx` |  |
| ✅ 移植済み | `analytics/vehicles` | 車両別 稼働グラフ | `analytics.vehicles.jsx` |  |
| ✅ 移植済み | `assistant/{conversationId}` | AIアシスタント | `assistant.$conversationId.jsx` |  |
| ✅ 移植済み | `assistant` | AIアシスタント | `assistant.index.jsx` |  |
| ✅ 移植済み | `automation` | 自動化ワークフロー | `automation.index.jsx` |  |
| ✅ 移植済み | `automation/monitor` | 取得監視・データ保全 | `automation.monitor.jsx` |  |
| ✅ 移植済み | `automation/reports` | 日報・週報・月報 | `automation.reports.jsx` |  |
| ✅ 移植済み | `board/{postId}` | 投稿 | `board.$postId.jsx` |  |
| ✅ 移植済み | `board` | みんなの掲示板 | `board.index.jsx` |  |
| ✅ 移植済み | `board/new` | 新規投稿 | `board.new.jsx` |  |
| ✅ 移植済み | `calendar` | 車両別稼働カレンダー | `calendar.jsx` |  |
| ✅ 移植済み | `camera` | カメラ統合コンソール | `camera.jsx` | USB(PTP)・ブリッジ接続は実機のカメラが必要（画面表示のみ確認） |
| ✅ 移植済み | `camera-bridge` | Androidブリッジ仕様 | `camera-bridge.jsx` |  |
| ✅ 移植済み | `collection` | データ収集 | `collection.jsx` |  |
| ✅ 移植済み | `compare` | お気に入り・車両比較 | `compare.jsx` |  |
| ✅ 移植済み | `dashboard` | パーソナルダッシュボード | `dashboard.jsx` |  |
| ✅ 移植済み | `discover` | 撮影地新規開拓AI | `discover.index.jsx` |  |
| ✅ 移植済み | `discover/saved` | 保存した撮影候補 | `discover.saved.jsx` |  |
| ✅ 移植済み | `expedition/{planId}` | 遠征計画 | `expedition.$planId.jsx` |  |
| ✅ 移植済み | `expedition` | 遠征プランナー | `expedition.index.jsx` |  |
| ✅ 移植済み | `expert/{conversationId}` | 相談AI | `expert.$conversationId.jsx` |  |
| ✅ 移植済み | `expert` | 奈良交通・バスロケ総合相談AI | `expert.index.jsx` |  |
| ✅ 移植済み | `field/conflicts` | 同期競合 | `field.conflicts.jsx` |  |
| ✅ 移植済み | `field/data` | 保存済みデータ | `field.data.jsx` |  |
| ✅ 移植済み | `field` | 現地モード | `field.index.jsx` |  |
| ✅ 移植済み | `field/map` | 保存済み地図 | `field.map.jsx` |  |
| ✅ 移植済み | `field/records` | 現地記録 | `field.records.jsx` |  |
| ✅ 移植済み | `fleet/{carCode}` | 車両詳細 | `fleet.$carCode.jsx` |  |
| ✅ 移植済み | `fleet/favorites` | お気に入り車両 | `fleet.favorites.jsx` |  |
| ✅ 移植済み | `fleet` | 車番検索 | `fleet.index.jsx` |  |
| ✅ 移植済み | `fleet/logs` | 全車両ログ | `fleet.logs.jsx` |  |
| ✅ 移植済み | `forecast/accuracy` | 予測精度分析 | `forecast.accuracy.jsx` |  |
| ✅ 移植済み | `forecast/history` | 予測履歴 | `forecast.history.jsx` |  |
| ✅ 移植済み | `forecast` | 1日運用予測 | `forecast.index.jsx` |  |
| ✅ 移植済み | `forecast/plan` | 1日運用予測（運用表優先） | `forecast.plan.jsx` |  |
| ✅ 移植済み | `forecast/verify` | 運用表で予測を検証 | `forecast.verify.jsx` |  |
| ✅ 移植済み | `home` | ホーム | `index.jsx` |  |
| ✅ 移植済み | `install` | ホーム画面に追加 | `install.jsx` |  |
| ✅ 移植済み | `logs` | 運行変更履歴 | `logs.jsx` |  |
| ✅ 移植済み | `meter/calibration` | 露出計 | `meter.calibration.jsx` | カメラ映像による測光は実機で確認が必要 |
| ✅ 移植済み | `meter/cameras` | 露出計 | `meter.cameras.jsx` |  |
| ✅ 移植済み | `meter/combos` | 露出計 | `meter.combos.jsx` |  |
| ✅ 移植済み | `meter/history` | 露出計 | `meter.history.jsx` |  |
| ✅ 移植済み | `meter` | 露出計 | `meter.index.jsx` | カメラ映像による測光は実機で確認が必要 |
| ✅ 移植済み | `meter/lenses` | 露出計 | `meter.lenses.jsx` |  |
| ✅ 移植済み | `meter/presets` | 露出計 | `meter.presets.jsx` |  |
| ✅ 移植済み | `nara/data` | 奈良交通データ・診断 | `nara.data.jsx` |  |
| ✅ 移植済み | `nara` | 奈良交通 経路検索 | `nara.index.jsx` |  |
| ✅ 移植済み | `nara/stops/{code}` | 停留所の詳細 | `nara.stops.$code.jsx` |  |
| ✅ 移植済み | `nara/stops` | 奈良交通 停留所検索 | `nara.stops.index.jsx` |  |
| ✅ 移植済み | `nara/timetable` | 奈良交通 区間時刻表 | `nara.timetable.jsx` |  |
| ✅ 移植済み | `notifications` | 通知 | `notifications.jsx` |  |
| ✅ 移植済み | `occlusion/compare` | 撮影角度・画角の比較 | `occlusion.compare.jsx` |  |
| ✅ 移植済み | `occlusion/data` | 交通量データ | `occlusion.data.jsx` |  |
| ✅ 移植済み | `occlusion/hourly` | 時間帯別の被り率 | `occlusion.hourly.jsx` |  |
| ✅ 移植済み | `occlusion` | AI撮影環境・被り率解析 | `occlusion.index.jsx` |  |
| ✅ 移植済み | `occlusion/ranking` | 撮影地点ランキング | `occlusion.ranking.jsx` |  |
| ✅ 移植済み | `occlusion/verify` | 予測と実績の検証 | `occlusion.verify.jsx` |  |
| ✅ 移植済み | `operations` | 運用表 | `operations.jsx` |  |
| ✅ 移植済み | `outing/battery` | 充電・バッテリー管理 | `outing.battery.jsx` |  |
| ✅ 移植済み | `outing/gear` | 持ち物管理 | `outing.gear.jsx` |  |
| ✅ 移植済み | `outing` | 外出アシスタント | `outing.index.jsx` |  |
| ✅ 移植済み | `outing/navi` | AI経路検索 | `outing.navi.jsx` |  |
| ✅ 移植済み | `outing/plans/{tripId}` | 外出予定の詳細 | `outing.plans.$tripId.jsx` |  |
| ✅ 移植済み | `outing/plans` | 外出予定 | `outing.plans.index.jsx` |  |
| ✅ 移植済み | `photos/{photoId}` | 撮影記録の詳細 | `photos.$photoId.jsx` |  |
| ✅ 移植済み | `photos` | 撮影データベース | `photos.index.jsx` |  |
| ✅ 移植済み | `photos/learning` | 撮影設定の学習・地点実績 | `photos.learning.jsx` |  |
| ✅ 移植済み | `predict/accuracy` | 予測精度・履歴 | `predict.accuracy.jsx` |  |
| ✅ 移植済み | `predict/intersection` | 交差点通過予測AI | `predict.intersection.jsx` |  |
| ✅ 移植済み | `predict/next` | 次運用予測 | `predict.next.jsx` |  |
| ✅ 移植済み | `predict/report` | 車両運行レポート | `predict.report.jsx` |  |
| ✅ 移植済み | `predict/watch` | 車両出現通知 | `predict.watch.jsx` |  |
| ✅ 移植済み | `profile` | マイページ | `profile.jsx` |  |
| ✅ 移植済み | `radar` | 気象レーダー | `radar.jsx` |  |
| ✅ 移植済み | `research/diff` | 運用表の差分比較 | `research.diff.jsx` |  |
| ✅ 移植済み | `research` | 運用調査データセンター | `research.index.jsx` |  |
| ✅ 移植済み | `research/routes` | 系統別充当車両 | `research.routes.jsx` |  |
| ✅ 移植済み | `research/vehicles` | 車両別運用履歴 | `research.vehicles.jsx` |  |
| ✅ 移植済み | `rides/history` | 乗車履歴 | `rides.history.jsx` |  |
| ✅ 移植済み | `rides/plans` | 乗車予定 | `rides.plans.jsx` |  |
| ✅ 移植済み | `search` | 自由条件検索 | `search.jsx` |  |
| ✅ 移植済み | `services` | 外部サービス接続 | `services.index.jsx` |  |
| ✅ 移植済み | `services/lodging` | 宿泊検索 | `services.lodging.jsx` |  |
| ✅ 移植済み | `services/transit` | 経路検索 | `services.transit.jsx` |  |
| ✅ 移植済み | `services/x` | X投稿検索 | `services.x.jsx` |  |
| ✅ 移植済み | `settings/advanced` | 詳細設定 | `settings.advanced.jsx` |  |
| ✅ 移植済み | `settings/data` | データ管理センター | `settings.data.jsx` |  |
| ✅ 移植済み | `settings` | 基本設定 | `settings.index.jsx` |  |
| ✅ 移植済み | `settings/monitor` | システム状態モニター | `settings.monitor.jsx` |  |
| ✅ 移植済み | `settings/notify` | 通知の設定 | `settings.notify.jsx` |  |
| ✅ 移植済み | `settings/offline` | オフライン現地活動モード | `settings.offline.jsx` |  |
| ✅ 移植済み | `settings/performance` | パフォーマンス設定 | `settings.performance.jsx` |  |
| ✅ 移植済み | `shoot/chances` | 撮影チャンス | `shoot.chances.jsx` |  |
| ✅ 移植済み | `shoot/environment` | 撮影環境予測 | `shoot.environment.jsx` |  |
| ✅ 移植済み | `shoot/favorites` | お気に入り撮影地点 | `shoot.favorites.jsx` |  |
| ✅ 移植済み | `shoot/light` | 光線シミュレーション | `shoot.light.jsx` |  |
| ✅ 移植済み | `shoot/spots` | 撮影スポット検索 | `shoot.spots.jsx` |  |
| ✅ 移植済み | `shoot/times` | おすすめ撮影時間 | `shoot.times.jsx` |  |
| ✅ 移植済み | `sightings` | 目撃情報マップ | `sightings.jsx` |  |
| ✅ 移植済み | `sim3d` | AI撮影環境3Dシミュレーター | `sim3d.index.jsx` |  |
| ✅ 移植済み | `sim3d/saved` | 保存したシミュレーション | `sim3d.saved.jsx` |  |
| ✅ 移植済み | `status` | 車両状態一覧 | `status.jsx` |  |
| ✅ 移植済み | `terrain/{routeName}` | 路線地形 | `terrain.$routeName.jsx` |  |
| ✅ 移植済み | `terrain/ai` | AI地形分析 | `terrain.ai.jsx` |  |
| ✅ 移植済み | `terrain/compare` | 路線比較 | `terrain.compare.jsx` |  |
| ✅ 移植済み | `terrain/hazards` | 狭隘・難所マップ | `terrain.hazards.jsx` |  |
| ✅ 移植済み | `terrain` | 路線地形分析 | `terrain.index.jsx` |  |
| ✅ 移植済み | `timetable/favorites` | 保存した交差点 | `timetable.favorites.jsx` |  |
| ✅ 移植済み | `timetable` | 交差点別バス時刻表 | `timetable.index.jsx` |  |
| ✅ 移植済み | `transit` | 総合経路検索（奈良交通＋鉄道） | `transit.jsx` |  |
| ✅ 移植済み | `vehicle-ai` | 車両情報AI | `vehicle-ai.jsx` |  |
| ✅ 移植済み | `vehicle-catalog/{fleetNumber}` | 車両詳細 | `vehicle-catalog.$fleetNumber.jsx` |  |
| ✅ 移植済み | `vehicle-catalog` | 車両図鑑 | `vehicle-catalog.index.jsx` |  |
| ✅ 移植済み | `vehicle-db/{vehicleNumber}` | 車両詳細 | `vehicle-db.$vehicleNumber.jsx` |  |
| ✅ 移植済み | `vehicle-db` | 車両データベース | `vehicle-db.index.jsx` |  |
| ✅ 移植済み | `vehicle-notify/history` | 車両通知の履歴 | `vehicle-notify.history.jsx` |  |
| ✅ 移植済み | `vehicle-notify` | 車両通知 | `vehicle-notify.index.jsx` |  |
| ✅ 移植済み | `vehicle-notify/place` | 地点を指定した車両通知 | `vehicle-notify.place.jsx` |  |
| ✅ 移植済み | `vehicles` | 車両位置 | `vehicles.jsx` |  |
| ✅ 移植済み | `weather/{code}` | 市町村別30日予報 | `weather.$code.jsx` |  |
| ✅ 移植済み | `weather/ai` | AI地域別気象解説 | `weather.ai.jsx` |  |
| ✅ 移植済み | `weather/compare` | 市町村の天気比較 | `weather.compare.jsx` |  |
| ✅ 移植済み | `weather/elevation` | 標高別気象・積雪残存 | `weather.elevation.jsx` |  |
| ✅ 移植済み | `weather` | 奈良県 市町村別の天気 | `weather.index.jsx` |  |
| ✅ 移植済み | `weather/map` | 奈良県 天気マップ | `weather.map.jsx` |  |
| ✅ 移植済み | `weather/similar` | 過去の類似気象日 | `weather.similar.jsx` |  |
| ✅ 移植済み | `weather/snow` | 降雪・積雪分析 | `weather.snow.jsx` |  |
| ✅ 移植済み | `wifi/history` | 転送履歴・検証 | `wifi.history.jsx` |  |
| ✅ 移植済み | `wifi` | Wi-Fi画像転送 | `wifi.index.jsx` | ブリッジとカメラの実機が必要（画面表示のみ確認） |
| ✅ 移植済み | `wifi/setup` | ブリッジ設定・対応表 | `wifi.setup.jsx` |  |

全 142 画面を移植済みです（Web 版 `src/routes/_authed/` の画面と 1 対 1 で対応）。

## 動作確認の範囲

- エミュレーター（Android 9・公開前チェックポイントのサーバー）で、各画面を開いて表示とクラッシュの有無を確認しました。今回追加した画面（地形・自動化・撮影地開拓・3D・写真・Wi-Fi・カメラ・被り率・レーダー・現地モード・外出・露出計・撮影支援など）は、リリース版と同じコード縮小（R8・リソース縮小）をかけたビルドでも開いて確認しています。
- リリース版の署名済み APK は、起動してログイン画面が出るところまで確認しました（本番サーバーは Publish 前のため、それ以降は未確認）。
- 統合乗換案内は、縮小ビルドで実際に検索して結果が表示されることを確認しました。
- 写真・車両図鑑の画像表示は、開発環境のネットワーク制限で画像サーバーに届かないため、表示そのものは未確認です。
- 実機のハードウェアが必要な機能（USB/PTP でのカメラ接続、Wi-Fi ブリッジ経由の RAW 転送、カメラ映像による測光）は、画面と通信手順までの確認で、実機では未確認です。
- アプリを閉じている間のプッシュ通知（FCM）は未実装です。
- 経路検索は `/transit`（総合）と `/nara`（奈良交通）を 1 つの「統合乗換案内」にまとめています。バスのみ・鉄道のみ・バス⇄鉄道・バス同士の乗り換えを同じ一覧で比較します。

## 共通ロジック

Web 版の `src/lib` の計算は `tools/logic-bundle` で 1 本の JavaScript（`app/src/main/assets/logic.js`）にまとめ、アプリ内の QuickJS で実行します。
ネイティブ専用のつなぎは `tools/logic-bundle/glue/`（停留所検索・乗換案内・撮影支援・3D シミュレーター・カメラ）にあります。
