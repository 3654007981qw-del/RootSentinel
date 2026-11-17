plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
}

// ---------------------------------------------------------------- 签名凭据
//
// 之前的问题：
//   1. storeFile 写死 /root/work/rs.jks —— 换台机器就构建不了
//   2. 密码从 gradle.properties 读，而 gradle.properties 常在版本库里
//      （明文密码进 Git 是典型凭据泄露）
//
// 现在：
//   · 路径与密码全部走 Gradle Property / 环境变量，脚本里不留任何具体值
//   · 优先读环境变量（CI 友好），其次读 -P 参数，最后读本地
//     ~/.gradle/gradle.properties（不进版本库）
//   · debug 构建拿不到密码不受影响（用 AGP 的 debug 签名）；
//     但 release 构建自 v2.22.44 起**失败关闭**（第四份审查 P2-14）：
//     缺凭据时在任务图阶段直接报错，不再回退 debug 签名 ——
//     回退产物外观与正式包无异，误发出去就是事故。
fun envOrProp(name: String): String? =
    System.getenv(name)?.takeIf { it.isNotBlank() }
        ?: providers.gradleProperty(name).orNull?.takeIf { it.isNotBlank() }

val ksStorePath: String = envOrProp("RS_KS_PATH") ?: ""
val ksPass: String = envOrProp("RS_KS_PASS") ?: ""
val ksAlias: String = envOrProp("RS_KS_ALIAS") ?: "rs"
val ksKeyPass: String = envOrProp("RS_KEY_PASS") ?: ksPass

val hasSigning = ksStorePath.isNotEmpty() &&
    file(ksStorePath).exists() &&
    ksPass.isNotEmpty()

if (!hasSigning) {
    logger.warn(
        "[RootSentinel] 未提供签名凭据（RS_KS_PATH / RS_KS_PASS）：" +
            "debug 构建不受影响；release 构建将被拒绝（失败关闭）。",
    )
}

// v2.22.44（P2-14）：release 任务失败关闭。配置期不报错（否则连
// assembleDebug 都被签名问题挡住），等任务图确定后再检查：
// 只要本次要跑任何 Release 任务且没有正式凭据，直接中断。
gradle.taskGraph.whenReady {
    val wantsRelease = allTasks.any { it.name.contains("Release") }
    if (wantsRelease && !hasSigning) {
        throw GradleException(
            "[RootSentinel] Release 构建缺少签名凭据" +
                "（RS_KS_PATH / RS_KS_PASS），已按失败关闭策略中断。" +
                "本地调试请用 assembleDebug。",
        )
    }
}

/**
 * 读取签名证书的 SHA-256（大写无分隔 hex）。
 *
 * 用 keytool 从 keystore 里取，而不是从 APK 反读 ——
 * 构建期 APK 还不存在，只能从 keystore 取。
 *
 * 拿不到就返回 null，native 侧据此把签名自校验降为 UNKNOWN。
 */
fun signerSha256(storePath: String, storePass: String, alias: String): String? {
    if (storePath.isEmpty() || storePass.isEmpty()) return null
    return try {
        // v2.22.44（P2-13）：密码不再走命令行参数 —— argv 在进程
        // 存活期间可从 /proc/<pid>/cmdline 读到。改用 keytool 的
        // -storepass:env，从子进程环境变量里取。
        val pb = ProcessBuilder(
            "keytool", "-list", "-v",
            "-keystore", storePath,
            "-storepass:env", "RS_KS_PASS_TMP",
            "-alias", alias,
        ).redirectErrorStream(true)
        pb.environment()["RS_KS_PASS_TMP"] = storePass
        val proc = pb.start()
        val out = proc.inputStream.bufferedReader().readText()
        proc.waitFor()
        // 取第一个 SHA256: 行
        val line = out.lineSequence().firstOrNull { it.contains("SHA256:") } ?: return null
        line.substringAfter("SHA256:").trim()
            .replace(":", "")
            .uppercase()
            .takeIf { it.length == 64 }
    } catch (t: Throwable) {
        null
    }
}

// debug 证书由 AGP 自动生成，路径固定。
// 下面两个值自 v2.22.44 起只用于配置期自检日志（确认 keytool 与
// 凭据可用），不再注入任何构建参数 —— 基线注入走 native.sh。
val debugKeystore = File(System.getProperty("user.home"), ".android/debug.keystore")
val debugSha = if (debugKeystore.exists()) {
    signerSha256(debugKeystore.absolutePath, "android", "androiddebugkey")
} else null

val releaseSha = if (hasSigning) {
    signerSha256(ksStorePath, ksPass, ksAlias)
} else null

if (hasSigning && releaseSha == null) {
    logger.warn("[RootSentinel] 有签名凭据但 keytool 读不出证书指纹，请检查 keystore 与 alias。")
}

android {
    namespace = "com.rootsentinel"
    compileSdk {
        version = release(37) {
            minorApiLevel = 0
        }
    }

    defaultConfig {
        applicationId = "com.rootsentinel"
        minSdk = 26
        targetSdk = 36
        versionCode = 22280
        versionName = "2.22.80"

        ndk {
            abiFilters += listOf("arm64-v8a", "armeabi-v7a", "x86_64")
        }
    }

    signingConfigs {
        if (hasSigning) {
            create("rs") {
                storeFile = file(ksStorePath)
                storePassword = ksPass
                keyAlias = ksAlias
                keyPassword = ksKeyPass
            }
        }
    }

    buildTypes {
        release {
            // v2.22.44（P2-12）：删掉原先两处 externalNativeBuild
            // cmake arguments 死配置 —— 全文件没有顶层
            // externalNativeBuild.cmake.path，那些参数从来没被
            // 消费过。签名基线的真实注入路径是 native.sh：
            // 它读 RS_KS_PATH/RS_KS_PASS 算出指纹后以
            // -DRS_SIGNER_SHA256 传给 CMake（见 native.sh）。
            // ---------------------------------------------------------- R8
            // 之前 isMinifyEnabled = false，15MB 的包里塞着完整的
            // Compose + Miuix + kotlin-reflect 未压缩符号。
            // 开启后 APK 体积可降到 6~8MB，同时把检测规则里的
            // 字符串常量做了进一步混淆 —— 对手 grep 不到明文特征。
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
            // 无凭据时这里名义上回退 debug，但任务图检查（见上）
            // 会先把 release 任务拦下，实际走不到这条分支。
            signingConfig = if (hasSigning) {
                signingConfigs.getByName("rs")
            } else {
                signingConfigs.getByName("debug")
            }
        }
        debug {
            if (hasSigning) signingConfig = signingConfigs.getByName("rs")
            // debug 的签名基线同样由 native.sh 在编 .so 时决定：
            // 编库时给的指纹对应哪把钥匙，基线就是哪把。
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlin {
        compilerOptions {
            jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
            // backdrop 库用了 Kotlin 2.2 的 context parameters
            freeCompilerArgs.add("-Xcontext-parameters")
            // 与手动流水线一致的实验 API 全局 opt-in（KSU 移植代码
            // 大量使用 material3 / foundation 实验 API，缺了会在
            // Gradle 下报 experimental API 错误）
            freeCompilerArgs.addAll(
                "-opt-in=androidx.compose.material3.ExperimentalMaterial3Api",
                "-opt-in=androidx.compose.material3.ExperimentalMaterial3ExpressiveApi",
                "-opt-in=androidx.compose.foundation.ExperimentalFoundationApi",
                "-opt-in=androidx.compose.animation.ExperimentalAnimationApi",
                "-opt-in=kotlinx.coroutines.ExperimentalCoroutinesApi",
            )
        }
    }

    buildFeatures { compose = true; buildConfig = true }

    packaging {
        resources.excludes += setOf("/META-INF/{AL2.0,LGPL2.1}", "/META-INF/*.kotlin_module")
    }

    // 原生库由 native.sh 编好后放进 jniLibs，AGP 只负责打包
    sourceSets["main"].jniLibs.srcDirs("src/main/jniLibs")
}

dependencies {
    // v2.22.60：依赖栈与 KernelSU 管理器对齐（Kotlin 2.4.20、Compose BOM
    // 2026.09.00、material3 1.5.0-alpha28、miuix 0.9.4 模块化坐标）。
    // 手工管线（tools/manual-build）用同一套版本解析（deps2/resolved.txt）。
    implementation("androidx.core:core-ktx:1.18.0")
    implementation("androidx.activity:activity-compose:1.13.0")
    implementation(platform("androidx.compose:compose-bom:2026.09.00"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3:1.5.0-alpha28")
    implementation("androidx.compose.material:material-icons-extended")

    // Miuix 0.9.4 —— HyperOS 风格 Compose 组件库（模块化坐标）
    implementation("top.yukonga.miuix.kmp:miuix-ui-android:0.9.4")
    implementation("top.yukonga.miuix.kmp:miuix-blur-android:0.9.4")
    implementation("top.yukonga.miuix.kmp:miuix-preference-android:0.9.4")
    implementation("top.yukonga.miuix.kmp:miuix-icons-android:0.9.4")
    implementation("top.yukonga.miuix.kmp:miuix-nav-android:0.9.4")

    // KernelSU 界面栈的其余依赖：动态取色、Markdown 渲染、WebView 资源加载、
    // 预测性返回手势、生命周期
    implementation("com.materialkolor:material-kolor-android:5.0.1")
    implementation("com.squareup.okhttp3:okhttp:5.5.0")
    implementation("org.commonmark:commonmark:0.30.0")
    implementation("org.commonmark:commonmark-ext-autolink:0.30.0")
    implementation("org.commonmark:commonmark-ext-gfm-strikethrough:0.30.0")
    implementation("org.commonmark:commonmark-ext-gfm-tables:0.30.0")
    implementation("org.commonmark:commonmark-ext-task-list-items:0.30.0")
    implementation("androidx.webkit:webkit:1.17.0")
    implementation("androidx.navigationevent:navigationevent-compose:1.1.2")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.11.0")
    implementation("androidx.lifecycle:lifecycle-viewmodel-ktx:2.11.0")

    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.11.0")

    // 单元测试。org.json 需要真实实现（android.jar 里的是桩），
    // JUnit 跑协议严格性 / DER 向量 / 去重。
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20231013")

}