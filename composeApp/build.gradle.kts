import com.android.build.api.artifact.SingleArtifact
import com.github.triplet.gradle.androidpublisher.ReleaseStatus
import com.github.triplet.gradle.androidpublisher.ResolutionStrategy
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.androidApplication)
    alias(libs.plugins.composeMultiplatform)
    alias(libs.plugins.composeCompiler)
    alias(libs.plugins.play.publisher)
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

// versionCode = nombre de commits de HEAD : il monte à chaque fusion, même valeur pour le même
// commit. VERSION_CODE le remplace (le tout premier AAB part avec VERSION_CODE=1). Un clone
// superficiel compterait trop peu : les tâches de release refusent plutôt que de produire un
// numéro plus bas que celui déjà installé (la CI de contrôle, elle, ne livre rien). versionName reste écrit ici : release.yml le compare au
// tag ; VERSION_NAME le remplace.
fun git(vararg args: String): String? = runCatching {
    providers.exec {
        commandLine("git", *args)
        isIgnoreExitValue = true
    }.standardOutput.asText.get().trim().ifEmpty { null }
}.getOrNull()

// Tâche de livraison : AAB ou envoi Play. Hors dépôt git (archive source du dépôt public), le
// nombre de commits est inconnu : repli sur 1 avec un avertissement, sauf pour une livraison.
val livraison = gradle.startParameter.taskNames.any { "bundle" in it || "publish" in it }
val appVersionCode = providers.environmentVariable("VERSION_CODE").orNull?.let {
    it.toIntOrNull() ?: error("VERSION_CODE doit être un entier, reçu '$it'.")
} ?: run {
    check(!livraison || git("rev-parse", "--is-shallow-repository") != "true") {
        "Clone superficiel : le nombre de commits est faux. Cloner en entier (fetch-depth: 0) ou passer VERSION_CODE."
    }
    git("rev-list", "--count", "HEAD")?.toIntOrNull() ?: run {
        check(!livraison) { "Pas de dépôt git : le versionCode est inconnu. Passer VERSION_CODE." }
        logger.warn("Pas de dépôt git : versionCode = 1 (build hors livraison).")
        1
    }
}
val appVersionNameOverride = providers.environmentVariable("VERSION_NAME").orNull

// Clé d'envoi Play (upload key), lue seulement dans l'environnement : bundleRelease signé pour
// Play. Aucune des quatre : on retombe sur la clé des APK hors store (VOLTDECK_KEYSTORE_*), puis
// sur une release non signée. Une partie seulement : le build échoue, pour qu'une release à
// moitié réglée ne parte jamais mal signée.
val uploadKeyEnv = listOf(
    "UPLOAD_KEYSTORE_PATH",
    "UPLOAD_KEYSTORE_PASSWORD",
    "UPLOAD_KEY_ALIAS",
    "UPLOAD_KEY_PASSWORD",
).associateWith { providers.environmentVariable(it).orNull?.ifEmpty { null } }
val uploadKeyMissing = uploadKeyEnv.filterValues { it == null }.keys
if (uploadKeyMissing.isNotEmpty() && uploadKeyMissing.size < uploadKeyEnv.size) {
    throw GradleException("Clé d'envoi : il manque $uploadKeyMissing (les quatre UPLOAD_* ou aucune)")
}

// Pas d'AAB ni d'envoi Play sans la clé d'envoi : sinon le repli sur VOLTDECK_KEYSTORE_* signerait
// l'AAB avec la clé des APK hors store, que Play enregistrerait comme clé d'envoi.
tasks.matching { it.name == "bundleRelease" || it.name == "signReleaseBundle" || it.name.startsWith("publish") }
    .configureEach {
        doFirst {
            if (uploadKeyMissing.isNotEmpty()) {
                throw GradleException("$name exige la clé d'envoi : il manque $uploadKeyMissing")
            }
        }
    }

// Clé du compte de service Play, en chemin de fichier (scripts/play-publish.sh l'écrit dans un
// fichier temporaire). Absente : Gradle Play Publisher reste éteint, un build ordinaire
// n'appelle jamais Play.
val playCredentials = providers.environmentVariable("PLAY_SERVICE_ACCOUNT_FILE").orNull?.ifEmpty { null }

android {
    namespace = "app.voltdeck"
    compileSdk = libs.versions.android.compileSdk.get().toInt()
    defaultConfig {
        applicationId = "tech.dmconsulting.voltdeck"
        minSdk = libs.versions.android.minSdk.get().toInt()
        targetSdk = libs.versions.android.targetSdk.get().toInt()
        versionCode = appVersionCode
        versionName = "0.2.0"
        appVersionNameOverride?.let { versionName = it }
    }
    // Clé de publication des APK hors store, fournie par la CI (secrets du dépôt).
    val releaseKeystore = System.getenv("VOLTDECK_KEYSTORE_FILE")
    signingConfigs {
        if (uploadKeyMissing.isEmpty()) {
            create("release") {
                storeFile = file(uploadKeyEnv.getValue("UPLOAD_KEYSTORE_PATH")!!)
                storePassword = uploadKeyEnv.getValue("UPLOAD_KEYSTORE_PASSWORD")
                keyAlias = uploadKeyEnv.getValue("UPLOAD_KEY_ALIAS")
                keyPassword = uploadKeyEnv.getValue("UPLOAD_KEY_PASSWORD")
            }
        } else if (releaseKeystore != null) {
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
    // Seule la variante release part sur Play.
    playConfigs {
        register("release") {
            enabled.set(playCredentials != null)
        }
    }
    testOptions { unitTests.isIncludeAndroidResources = true }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

// Valeurs par défaut de scripts/play-publish.sh, qui passe --track et --release-status.
// AUTO : si le versionCode tiré de git est déjà pris sur Play, le suivant libre est utilisé.
play {
    enabled.set(false)
    playCredentials?.let { serviceAccountCredentials.set(file(it)) }
    track.set("internal")
    defaultToAppBundles.set(true)
    releaseStatus.set(ReleaseStatus.DRAFT)
    resolutionStrategy.set(ResolutionStrategy.AUTO)
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
