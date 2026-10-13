import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// CI가 Mozilla Maven에서 실제 존재하는 아티팩트/버전을 찾아 넘겨준다.
val geckoArtifact = (findProperty("geckoArtifact") as String?) ?: "geckoview-omni"
val geckoVersion = (findProperty("geckoVersion") as String?)
    ?: error("-PgeckoVersion=<버전> 이 필요합니다 (CI가 자동으로 지정)")
val appVersionCode = ((findProperty("appVersionCode") as String?) ?: "1").toInt()
val appVersionName = (findProperty("appVersionName") as String?) ?: "1.0.$appVersionCode"
val updateRepo = (findProperty("updateRepo") as String?) ?: "sehzyeon-lab/bgtube"

android {
    namespace = "app.bgtube"
    compileSdk = 36

    defaultConfig {
        applicationId = "app.bgtube"
        minSdk = 26
        targetSdk = 35
        versionCode = appVersionCode
        versionName = appVersionName
        buildConfigField("String", "UPDATE_REPO", "\"$updateRepo\"")
    }

    // 업데이트가 덮어 설치되려면 매 빌드 같은 키로 서명해야 한다.
    // CI 는 GitHub Secrets(ANDROID_KEYSTORE_BASE64 등)로 키를 주입한다. 없으면 debug 키로 서명(업데이트 불가).
    val ksFile = System.getenv("ANDROID_KEYSTORE_FILE")
    val hasKeystore = ksFile != null && file(ksFile).exists()
    if (hasKeystore) {
        signingConfigs {
            create("personal") {
                storeFile = file(ksFile!!)
                storePassword = System.getenv("ANDROID_KEYSTORE_PASSWORD")
                keyAlias = System.getenv("ANDROID_KEY_ALIAS")
                keyPassword = System.getenv("ANDROID_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName(if (hasKeystore) "personal" else "debug")
        }
    }

    buildFeatures { buildConfig = true }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    packaging { jniLibs { useLegacyPackaging = true } }
}

// GeckoView 가 끌어오는 최신 androidx.core(1.19)는 AGP 9.1+ 를 요구하므로 AGP 8.x 에서 쓸 수 있는 버전으로 고정.
configurations.all {
    resolutionStrategy.force("androidx.core:core:1.16.0", "androidx.core:core-ktx:1.16.0")
}

// GeckoView 최신판은 compileSdk 37 / AGP 9.1 을 요구하지만, 이 앱은 해당 API 를 직접 쓰지 않으므로
// AGP 8.9 + compileSdk 36 으로 컴파일하고 라이브러리 메타데이터 검사만 끈다.
tasks.configureEach {
    if (name.endsWith("AarMetadata")) enabled = false
}

kotlin {
    compilerOptions { jvmTarget.set(JvmTarget.JVM_17) }
}

dependencies {
    implementation("org.mozilla.geckoview:$geckoArtifact:$geckoVersion")
    implementation("androidx.core:core-ktx:1.16.0")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("androidx.swiperefreshlayout:swiperefreshlayout:1.1.0")
}
