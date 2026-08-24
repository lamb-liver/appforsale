import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.room3)
    alias(libs.plugins.ksp)
}

val appVersionName: String = rootProject.file("VERSION")
    .takeIf { it.exists() }
    ?.readText()
    ?.trim()
    ?.removePrefix("v")
    ?: "1.0.0"

val localProperties = Properties().apply {
    rootProject.file("local.properties").takeIf { it.isFile }?.inputStream()?.use(::load)
}
fun signingValue(environmentName: String, localName: String): String? =
    providers.environmentVariable(environmentName).orNull
        ?.takeIf { it.isNotBlank() }
        ?: localProperties.getProperty(localName)?.takeIf { it.isNotBlank() }

val releaseStorePath = signingValue("STALLPOS_RELEASE_STORE_FILE", "storeFile")
val releaseStorePassword = signingValue("STALLPOS_RELEASE_STORE_PASSWORD", "storePassword")
val releaseKeyAlias = signingValue("STALLPOS_RELEASE_KEY_ALIAS", "keyAlias")
val releaseKeyPassword = signingValue("STALLPOS_RELEASE_KEY_PASSWORD", "keyPassword")
val releaseSigningComplete = listOf(
    releaseStorePath,
    releaseStorePassword,
    releaseKeyAlias,
    releaseKeyPassword,
).all { !it.isNullOrBlank() }

composeCompiler {
    reportsDestination.set(layout.buildDirectory.dir("compose_reports"))
}

room3 {
    schemaDirectory("$projectDir/schemas")
}

android {
    namespace = "com.lambliver.stallpos"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.lambliver.stallpos"
        minSdk = 24
        targetSdk = 35
        versionCode = 6
        versionName = appVersionName

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    signingConfigs {
        if (releaseSigningComplete) {
            create("release") {
                storeFile = rootProject.file(requireNotNull(releaseStorePath))
                storePassword = requireNotNull(releaseStorePassword)
                keyAlias = requireNotNull(releaseKeyAlias)
                keyPassword = requireNotNull(releaseKeyPassword)
            }
        }
    }

    buildTypes {
        release {
            signingConfig = signingConfigs.findByName("release")
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
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
    sourceSets["test"].resources.srcDir(rootProject.file("contracts"))
}

val validateReleaseSigning = tasks.register("validateReleaseSigning") {
    doLast {
        project.delete(layout.buildDirectory.dir("outputs/apk/release"))
        check(releaseSigningComplete) {
            "Release signing requires keystore, store password, alias, and key password"
        }
        val releaseStoreExists = rootProject.file(requireNotNull(releaseStorePath)).isFile
        check(releaseStoreExists) {
            "Release keystore does not exist: $releaseStorePath"
        }
    }
}

tasks.configureEach {
    if (name == "packageRelease" || name == "bundleRelease") dependsOn(validateReleaseSigning)
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
    implementation(libs.androidx.room3.runtime)
    implementation(libs.androidx.sqlite.bundled)
    implementation(libs.kotlinx.collections.immutable)
    ksp(libs.androidx.room3.compiler)
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
