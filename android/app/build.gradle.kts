import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

// 签名：读取仓库根目录 signing/keystore.properties（本地自备，已被 .gitignore 忽略）。
// CI 在构建前按同一形状生成该文件与 key；本地没有签名材料时仍可打 debug 包。
// 工程根 = <repo>/android  →  ../signing 在本仓库内，../../signing 兼容原有工作区布局。
val signingDir =
    rootProject.file("../signing").takeIf { it.exists() } ?: rootProject.file("../../signing")
val keystoreProps = Properties().apply {
    val f = signingDir.resolve("keystore.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}

android {
    signingConfigs {
        create("projectKey") {
            storeFile = signingDir.resolve(keystoreProps.getProperty("storeFile") ?: "gps-signing.jks")
            storePassword = keystoreProps.getProperty("storePassword")
            keyAlias = keystoreProps.getProperty("keyAlias")
            keyPassword = keystoreProps.getProperty("keyPassword")
            keyPassword = keystoreProps["keyPassword"] as String?
        }
    }
    namespace = "com.phonepctransfer"
    compileSdk {
        version = release(37)
    }

    defaultConfig {
        applicationId = "com.phonepctransfer"
        minSdk = 28
        targetSdk = 37
        // versionCode 走「major*10000 + minor*100 + patch」＝ 10100，保证严格递增
        // （同签名覆盖安装认 versionCode，必须 ≥ 已装版本）。
        // 2026-10-01 收尾定版：对外 1.1，内部仍为 10100（= 1.1 口径，两者对应）。
        // CI 通过 -PppVersionName=1.1.<run> -PppVersionCode=10100+<run> 注入，
        // 保证每次 Release 的 versionCode 单调递增。
        versionCode = (project.findProperty("ppVersionCode") as String?)?.toInt() ?: 10100
        versionName = (project.findProperty("ppVersionName") as String?) ?: "1.1"

        // 只保留中英文资源
        resourceConfigurations.addAll(listOf("zh", "zh-rCN", "zh-rTW", "en"))
    }

    buildTypes {
        debug {
            signingConfig = signingConfigs.getByName("projectKey")
        }
        release {
            optimization {
                // 2026-09-15：加一个命令行开关，便于「先用非 R8 包做真机回归」——
                //   ./gradlew :app:assembleRelease -Pr8=false   → 不打 R8（体积 ~39MB，但功能一致）
                //   ./gradlew :app:assembleRelease              → 默认打 R8（~2.7MB）
                enable = (project.findProperty("r8") as String? ?: "true").toBoolean()
                // 自定义 keep 规则
                // ⚠️ 2026-09-15：此前这里只写了 enable = true，keepRules 从未声明 →
                //    app/keepRules/rules.keep 一直是**死文件**（R8 走的是纯默认规则，
                //    协议层 net 包被整体改名，见 outputs/mapping/release/mapping.txt）。
                //    AGP 9 的 optimization 块必须显式 files.add 才会读取规则文件。
                keepRules {
                    files.add(file("keepRules/rules.keep"))
                }
            }
            signingConfig = signingConfigs.getByName("projectKey")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    buildFeatures {
        compose = true
    }
}

dependencies {
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    debugImplementation(libs.androidx.compose.ui.tooling)

    // 语音通话：纯 Java Opus 编解码（concentus，零 JNI，两端一致）
    implementation(files("libs/concentus-1.0.2.jar"))
}
