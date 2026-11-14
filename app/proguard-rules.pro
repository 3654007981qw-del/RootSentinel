# ---------------------------------------------------------------- RootSentinel R8 规则
#
# 目标：能混淆的全混淆，只保留「反射 / JNI / 序列化」必须可见的符号。

# ---- JNI 入口：native 方法名必须与 C++ 侧 Java_com_rootsentinel_NativeBridge_* 对齐 ----
-keepclasseswithmembernames class com.rootsentinel.NativeBridge {
    native <methods>;
}
-keep class com.rootsentinel.NativeBridge { *; }

# ---- JNI 回调接口：C++ 用 GetMethodID 按名字找，不能被混淆 ----
-keep interface com.rootsentinel.NativeBridge$ProgressCallback { *; }
-keep class * implements com.rootsentinel.NativeBridge$ProgressCallback {
    void onItem(java.lang.String, java.lang.String, java.lang.String, java.lang.String, java.lang.String);
    void onModule(java.lang.String);
}

# ---- SystemProperties 反射：类名与方法名必须保留 ----
-keep class android.os.SystemProperties { *; }

# ---- Keystore / 证书链：反射路径与标准库类 ----
-keep class java.security.** { *; }
-keep class javax.security.** { *; }
-keep class android.security.** { *; }

# ---- org.json：NativeReportParser 按字段名取值 ----
-keep class org.json.** { *; }

# ---- Compose / Miuix：Compose 编译器已生成足够信息，这里只防误删 ----
-dontwarn androidx.compose.**
-keep class androidx.compose.runtime.** { *; }
-keep class top.yukonga.miuix.** { *; }

# ---- backdrop 内嵌库 ----
-keep class com.kyant.backdrop.** { *; }

# ---- 协程 ----
-dontwarn kotlinx.coroutines.**
-keepclassmembers class kotlinx.coroutines.** { volatile <fields>; }

# ---- 保留行号，崩溃栈可读（不保留源文件名） ----
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile

# ---- 移除日志：release 不打 ev/xxx 逐项日志 ----
-assumenosideeffects class android.util.Log {
    public static int v(...);
    public static int d(...);
}

# ---- 不警告：可选的 GMS / Play Integrity 类在无 GMS 设备上不存在 ----
-dontwarn com.google.android.gms.**
-dontwarn com.google.android.play.**
