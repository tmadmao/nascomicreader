import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import java.util.Properties

plugins {
    // AGP 9 起内置 Kotlin 编译，不再单独应用 org.jetbrains.kotlin.android
    alias(libs.plugins.android.application)
    alias(libs.plugins.ksp)
}

// 签名材料不进仓库：keystore.properties 与 *.jks 都在本地（已 gitignore）。
// 没有这份文件时 release 仍可构建，只是产物未签名。
val keystoreProps = Properties().apply {
    val file = rootProject.file("keystore.properties")
    if (file.exists()) file.inputStream().use { load(it) }
}

// 许可清单只有一份：仓库根的 NOTICE / LICENSE。构建时复制进 assets，界面直接读，不手抄
val licensesDir = layout.buildDirectory.dir("generated/licenses")

val copyLicenses = tasks.register<Copy>("copyLicenses") {
    from(rootProject.file("NOTICE"))
    from(rootProject.file("LICENSE"))
    into(licensesDir)
}

android {
    namespace = "com.nascomic.reader"
    compileSdk = 37
    compileSdkMinor = 2

    defaultConfig {
        applicationId = "com.nascomic.reader"
        minSdk = 24
        targetSdk = 37
        versionCode = 1
        versionName = "0.1.0-beta1"
    }

    signingConfigs {
        if (keystoreProps.isNotEmpty()) {
            create("release") {
                storeFile = rootProject.file(keystoreProps.getProperty("storeFile"))
                storePassword = keystoreProps.getProperty("storePassword")
                keyAlias = keystoreProps.getProperty("keyAlias")
                keyPassword = keystoreProps.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        debug {
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
        }
        release {
            signingConfig = signingConfigs.findByName("release")
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            ndk {
                abiFilters += listOf("armeabi-v7a", "arm64-v8a")
            }
        }
    }

    // AGP 9 不接受 Provider，取成 File 再传
    sourceSets.getByName("main").assets.srcDir(licensesDir.get().asFile)

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        buildConfig = true
        viewBinding = true
    }

    packaging {
        resources.excludes += setOf("/META-INF/{AL2.0,LGPL2.1}")
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.activity)
    implementation(libs.androidx.constraintlayout)
    implementation(libs.androidx.lifecycle.runtime)
    implementation(libs.material)
    implementation(libs.androidx.recyclerview)
    implementation(libs.libarchive)
    implementation(libs.okhttp)
    implementation(libs.smbj)

    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)

    implementation(libs.kotlinx.coroutines.core)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
}

// Room 导出的 schema 进版本库，迁移才有据可查
ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
    arg("room.generateKotlin", "true")
}

// 许可文件要在合并 assets 之前就位
tasks.named("preBuild").configure { dependsOn(copyLicenses) }
