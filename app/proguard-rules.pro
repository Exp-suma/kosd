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
