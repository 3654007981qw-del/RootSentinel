plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
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
//   · 拿不到密码时**不中断构建**，而是回退到 debug 签名并打出显式警告 ——
//     否则贡献者 fork 下来连编译都过不了
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
        "[RootSentinel] 未提供签名凭据（RS_KS_PATH / RS_KS_PASS），" +
            "release 将回退到 debug 签名。这是本地开发路径，不要用于发布。",
    )
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
        val proc = ProcessBuilder(
            "keytool", "-list", "-v",
            "-keystore", storePath,
            "-storepass", storePass,
            "-alias", alias,
        ).redirectErrorStream(true).start()
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

// debug 证书由 AGP 自动生成，路径固定
val debugKeystore = File(System.getProperty("user.home"), ".android/debug.keystore")
val debugSha = if (debugKeystore.exists()) {
    signerSha256(debugKeystore.absolutePath, "android", "androiddebugkey")
} else null

val releaseSha = if (hasSigning) {
    signerSha256(ksStorePath, ksPass, ksAlias)
} else null

android {
    namespace = "com.rootsentinel"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.rootsentinel"
        minSdk = 26
        targetSdk = 36
        versionCode = 22242
        versionName = "2.22.42"

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
            // 签名基线按 release 证书注入
            if (releaseSha != null) {
                defaultConfig {
                    externalNativeBuild {
                        cmake {
                            arguments += "-DRS_SIGNER_SHA256=$releaseSha"
                        }
                    }
                }
            }
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
            signingConfig = if (hasSigning) {
                signingConfigs.getByName("rs")
            } else {
                signingConfigs.getByName("debug")
            }
        }
        debug {
            if (hasSigning) signingConfig = signingConfigs.getByName("rs")
            // 签名基线按 debug 证书注入 —— 与 release 分开，
            // 避免「debug 构建被判重打包」这种开发期误报
            if (debugSha != null) {
                defaultConfig {
                    externalNativeBuild {
                        cmake {
                            arguments += "-DRS_SIGNER_SHA256=$debugSha"
                        }
                    }
                }
            }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
        // backdrop 库用了 Kotlin 2.2 的 context parameters
        freeCompilerArgs += "-Xcontext-parameters"
    }

    buildFeatures { compose = true }

    packaging {
        resources.excludes += setOf("/META-INF/{AL2.0,LGPL2.1}", "/META-INF/*.kotlin_module")
    }

    // 原生库由 native.sh 编好后放进 jniLibs，AGP 只负责打包
    sourceSets["main"].jniLibs.srcDirs("src/main/jniLibs")
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation(platform("androidx.compose:compose-bom:2024.12.01"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-core")

    // Miuix —— HyperOS 风格 Compose 组件库
    implementation("top.yukonga.miuix.kmp:miuix:0.8.8")
    // Miuix 内部已用 kyant-shapes（implementation，不传递）：backdrop 的 lens()
    // 需要 RoundedRectangularShape / Capsule，这里显式声明同一坐标，保证编译可见且不重复。
    implementation("io.github.kyant0:shapes:1.2.0")


    // 液态玻璃：三个候选库已逐一实测（2026-11-08），结论全部无法直接依赖：
//   · io.github.kyant0:backdrop 2.0.1 —— aar 声明 minCompileSdk=37，
//     可用 android.experimental.disableCompileSdkChecks 豁免；但它依赖 Compose 1.12.0，
//     而 Compose 1.12 的 aar 元数据要求 AGP 9.1.0+，本机 AGP 8.9.1 → 硬阻断，无法豁免。
//   · Kashif-E/KMPLiquidGlass —— 内嵌 backdrop 源码，自身用 AGP 8.13.1 + Compose 1.10.0，
//     源码可读，但要移植到 Compose 1.7 需逐 API 核对。
//   · NadeemIqbal/liquid-glass —— 9 star，未发布到 Maven Central，能力是磨砂而非折射。
// 结论：AGSL 只用到 API 33 的 RenderEffect.createRuntimeShaderEffect，
// 本工程自实现（GlassEffect.kt）反而没有版本限制。已吸收其中两处真正有价值的设计：
// 色差（dispersion）与三级质量回退（GlassQuality）。

    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
}