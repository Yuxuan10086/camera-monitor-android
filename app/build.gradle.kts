import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// 从本地 secrets.properties 读取密钥（该文件不提交到仓库，模板见 secrets.properties.example）
val secrets = Properties().apply {
    val file = File(rootDir, "secrets.properties")
    if (file.exists()) file.inputStream().use { load(it) }
}
val logUploadUrl: String = secrets.getProperty("LOG_UPLOAD_URL").orEmpty()

android {
    namespace = "com.cameramonitor.app"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.cameramonitor.app"
        minSdk = 26
        targetSdk = 34
        versionCode = 17
        versionName = "2.5"

        // 日志上传端点由 secrets.properties 注入，避免 token 入库
        buildConfigField("String", "LOG_UPLOAD_URL", "\"$logUploadUrl\"")
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        viewBinding = true
        buildConfig = true
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.12.0")
    implementation("androidx.appcompat:appcompat:1.6.1")
    implementation("com.google.android.material:material:1.11.0")
    implementation("androidx.constraintlayout:constraintlayout:2.1.4")
    implementation("androidx.activity:activity-ktx:1.8.2")
    implementation("androidx.fragment:fragment-ktx:1.6.2")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.7.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.7.3")
}

// 编译后自动发布到 update/ 目录，供 update_server.py 推送到手机
tasks.register("publishUpdate") {
    dependsOn("assembleDebug")
    doLast {
        val updateDir = File(rootDir, "update").apply { mkdirs() }
        val apk = File(buildDir, "outputs/apk/debug/app-debug.apk")
        apk.copyTo(File(updateDir, "app.apk"), overwrite = true)
        val vc = android.defaultConfig.versionCode
        val vn = android.defaultConfig.versionName
        File(updateDir, "version.json").writeText(
            """{"versionCode": $vc, "versionName": "$vn"}"""
        )
        println("Published update: versionCode=$vc versionName=$vn -> ${updateDir.absolutePath}")
    }
}
