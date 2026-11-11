import java.util.Properties
import javax.inject.Inject

plugins {
    // Kotlin is built into AGP 9: no kotlin-android plugin.
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
}

// Optional: a signing key shared with xrnloops (both apps must carry the same signature), kept
// outside both repos in ../xrn-signing.properties (storeFile, storePassword, keyAlias, keyPassword;
// storeFile relative to that file's folder). Without that file, builds are signed with this
// machine's debug key.
val xrnSigning: Properties? = rootProject.file("../xrn-signing.properties").takeIf { it.isFile }?.let { f ->
    Properties().apply { f.inputStream().use { load(it) } }
}

android {
    namespace = "com.xern.xrnrule.app"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.xern.xrnrule"
        minSdk = 26
        targetSdk = 35
        versionCode = 16
        versionName = "0.2.$versionCode"
    }

    buildFeatures {
        compose = true
    }

    // "chưa chốt phân phối" (brainstorm §2.2): the :hardcore module (Device Admin, settings
    // guard) is only linked into the `sideload` flavor. `play` builds ship without it.
    flavorDimensions += "distribution"
    productFlavors {
        create("sideload") { dimension = "distribution" }
        create("play") { dimension = "distribution" }
    }

    signingConfigs {
        if (xrnSigning != null) {
            create("xrn") {
                storeFile = rootProject.file("..").resolve(xrnSigning.getProperty("storeFile"))
                storePassword = xrnSigning.getProperty("storePassword")
                keyAlias = xrnSigning.getProperty("keyAlias")
                keyPassword = xrnSigning.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        debug {
            signingConfigs.findByName("xrn")?.let { signingConfig = it }
        }
        // The build to live with: not debuggable, so a computer allowed USB debugging can't
        // `run-as` into the app's files (the sync password is there). Not shrunk: R8 has never
        // been checked against the saved World, and a world.json the app can't read starts it
        // empty.
        release {
            isMinifyEnabled = false
            signingConfigs.findByName("xrn")?.let { signingConfig = it }
        }
    }

    // Kotlin's jvmTarget follows targetCompatibility.
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

// The phone's block page is the extension's (extension/block.*), served by the app itself at
// http://127.0.0.1:47321/block (see ExtensionServer); copied in at build time so there's one copy.
abstract class BlockPage : DefaultTask() {
    @get:InputFiles
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val page: ConfigurableFileCollection

    /** The assets folder AGP adds (see onVariants below); the page goes under web/. */
    @get:OutputDirectory
    abstract val outputDir: DirectoryProperty

    @get:Inject
    abstract val files: FileSystemOperations

    @TaskAction
    fun copy() {
        files.sync {
            from(page)
            into(outputDir.dir("web"))
        }
    }
}

val blockPage = tasks.register<BlockPage>("blockPage") {
    page.from(
        rootProject.fileTree("extension") {
            // manifest.json only for its version: the extension this build was made with (not served).
            include("block.html", "block.js", "shared.js", "page.css", "icons/icon-48.png", "manifest.json")
        },
    )
}
androidComponents {
    onVariants { variant -> variant.sources.assets?.addGeneratedSourceDirectory(blockPage, BlockPage::outputDir) }
}

dependencies {
    testImplementation(libs.junit)
    implementation(project(":domain"))
    "sideloadImplementation"(project(":hardcore"))

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.service)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons.extended)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.androidx.work.runtime.ktx)
    // The fingerprint step in front of the app (data/AppLock.kt). BiometricPrompt needs a
    // FragmentActivity; biometric's own fragment dependency is from 2020, so name a current one.
    implementation(libs.androidx.biometric)
    implementation(libs.androidx.fragment)
    implementation(libs.kotlinx.coroutines.android)

    debugImplementation(libs.androidx.compose.ui.tooling)
}
