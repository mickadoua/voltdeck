import com.android.build.api.artifact.SingleArtifact
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.androidApplication)
    alias(libs.plugins.composeMultiplatform)
    alias(libs.plugins.composeCompiler)
}

kotlin {
    androidTarget {
        compilerOptions { jvmTarget.set(JvmTarget.JVM_17) }
    }
    listOf(iosArm64(), iosSimulatorArm64()).forEach { target ->
        target.binaries.framework {
            baseName = "ComposeApp"
            isStatic = true
        }
    }

    sourceSets {
        commonMain.dependencies {
            implementation(project(":core"))
            implementation(project(":ble"))
            implementation(project(":drivers"))
            implementation(project(":simulator"))
            implementation(project(":storage"))
            implementation(libs.compose.runtime)
            implementation(libs.compose.foundation)
            implementation(libs.compose.ui)
            implementation(libs.vico.compose)
        }
        androidMain.dependencies {
            implementation(libs.androidx.activity.compose)
        }
        commonTest.dependencies {
            implementation(kotlin("test"))
            implementation(libs.kotlinx.coroutines.test)
        }
        getByName("androidUnitTest").dependencies {
            implementation(libs.sqldelight.sqlite.driver)
            implementation(libs.robolectric)
            implementation(libs.compose.ui.test.junit4)
            implementation(libs.junit)
        }
    }
}

android {
    namespace = "app.voltdeck"
    compileSdk = libs.versions.android.compileSdk.get().toInt()
    defaultConfig {
        applicationId = "app.voltdeck"
        minSdk = libs.versions.android.minSdk.get().toInt()
        targetSdk = libs.versions.android.targetSdk.get().toInt()
        versionCode = 1
        versionName = "0.1.0"
    }
    // Clé de publication fournie par la CI (secrets du dépôt) : sans elle, l'APK release reste non signé.
    val releaseKeystore = System.getenv("VOLTDECK_KEYSTORE_FILE")
    signingConfigs {
        if (releaseKeystore != null) {
            create("release") {
                storeFile = file(releaseKeystore)
                storePassword = System.getenv("VOLTDECK_KEYSTORE_PASSWORD")
                keyAlias = System.getenv("VOLTDECK_KEY_ALIAS")
                keyPassword = System.getenv("VOLTDECK_KEY_PASSWORD")
            }
        }
    }
    buildTypes {
        getByName("release") {
            signingConfigs.findByName("release")?.let { signingConfig = it }
        }
    }
    testOptions { unitTests.isIncludeAndroidResources = true }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

// Verrou « aucune permission réseau » (VOL-1 §4.3) : échoue si le manifest fusionné déclare INTERNET.
abstract class VerifyNoInternetPermission : DefaultTask() {
    @get:InputFile
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val mergedManifest: RegularFileProperty

    @TaskAction
    fun verify() {
        val manifest = mergedManifest.get().asFile
        if ("android.permission.INTERNET" in manifest.readText()) {
            throw GradleException(
                "android.permission.INTERNET trouvée dans le manifest fusionné ($manifest) : " +
                    "Voltdeck ne doit jamais pouvoir envoyer de données hors du téléphone.",
            )
        }
    }
}

androidComponents {
    onVariants { variant ->
        val capitalized = variant.name.replaceFirstChar { it.uppercase() }
        val verify = tasks.register<VerifyNoInternetPermission>("verify${capitalized}ManifestHasNoInternet") {
            mergedManifest.set(variant.artifacts.get(SingleArtifact.MERGED_MANIFEST))
        }
        tasks.named("check") { dependsOn(verify) }
    }
}
