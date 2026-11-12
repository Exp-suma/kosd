# コード縮小（R8）の除外設定

# QuickJS（共通ロジックの実行）は JNI からクラス・メソッドを名前で参照する
-keep class com.dokar.quickjs.** { *; }

# 地図（osmdroid）はタイル取得などでクラスを名前で参照する
-keep class org.osmdroid.** { *; }
-dontwarn org.osmdroid.**

# 任意依存（実行時には使わない）への参照で警告を出さない
-dontwarn okhttp3.internal.platform.**
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**

# カメラ Wi-Fi 接続（CamBridge）はそのまま組み込んでいるため縮小の対象外にする
-keep class com.example.d750bridge.** { *; }
# MediaPipe（被写体検出）はネイティブ層からクラスを名前で参照する
-keep class com.google.mediapipe.** { *; }
-keep class com.google.protobuf.** { *; }
# MediaPipe が参照するが同梱されていない任意のクラス（R8 が出した一覧）
-dontwarn com.google.mediapipe.proto.CalculatorProfileProto$CalculatorProfile
-dontwarn com.google.mediapipe.proto.GraphTemplateProto$CalculatorGraphTemplate