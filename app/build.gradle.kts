import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

val localProperties = Properties().apply {
    val file = rootProject.file("local.properties")
    if (file.exists()) {
        file.inputStream().use { load(it) }
    }
}
fun localProp(key: String): String? = localProperties.getProperty(key)?.takeIf { it.isNotBlank() }

val appVersionName: String = rootProject.file("VERSION")
    .takeIf { it.exists() }
    ?.readText()
    ?.trim()
    ?.removePrefix("v")
    ?: "1.0.0"

/** Google 官方測試 ID — debug 與未設定 release 金鑰時使用 */
val admobTestAppId = "ca-app-pub-3940256099942544~3347511713"
val admobTestBannerId = "ca-app-pub-3940256099942544/9214589741"

composeCompiler {
    reportsDestination.set(layout.buildDirectory.dir("compose_reports"))
}

android {
    namespace = "com.lambliver.appforsale"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.lambliver.appforsale"
        minSdk = 24
        targetSdk = 35
        versionCode = 2
        versionName = appVersionName

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        manifestPlaceholders["admobApplicationId"] = admobTestAppId
        buildConfigField("String", "ADMOB_BANNER_AD_UNIT_ID", "\"$admobTestBannerId\"")
    }

    buildTypes {
        debug {
            manifestPlaceholders["admobApplicationId"] = admobTestAppId
            buildConfigField("String", "ADMOB_BANNER_AD_UNIT_ID", "\"$admobTestBannerId\"")
        }
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
            val releaseAppId = localProp("admob.application.id") ?: admobTestAppId
            val releaseBannerId = localProp("admob.banner.unit.id") ?: admobTestBannerId
            manifestPlaceholders["admobApplicationId"] = releaseAppId
            buildConfigField("String", "ADMOB_BANNER_AD_UNIT_ID", "\"$releaseBannerId\"")
            ndk {
                debugSymbolLevel = "SYMBOL_TABLE"
            }
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
    testOptions {
        unitTests {
            isIncludeAndroidResources = true
        }
    }
    lint {
        lintConfig = file("lint.xml")
        abortOnError = true
        checkTestSources = true
    }
}

dependencies {
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons.extended)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.process)
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.kotlinx.collections.immutable)
    implementation(libs.play.services.ads)
    implementation(libs.user.messaging.platform)
    implementation(libs.play.billing.ktx)
    testImplementation(libs.junit)
    testImplementation(libs.robolectric)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.androidx.arch.core.testing)
    testImplementation(libs.androidx.test.core.ktx)
    testImplementation(libs.androidx.junit.ktx)
    testImplementation(platform(libs.androidx.compose.bom))
    testImplementation(libs.androidx.compose.ui.test.junit4)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.junit)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
    debugImplementation(libs.androidx.compose.ui.tooling)
}
