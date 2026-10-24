import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
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
        versionCode = 1
        versionName = "0.1.0"
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

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
}

// The phone's block page is the extension's (extension/block.*), served by the app itself at
// http://127.0.0.1:47321/block (see ExtensionServer); copied in at build time so there's one copy.
val blockPage = tasks.register<Sync>("blockPage") {
    from(rootProject.file("extension")) {
        include("block.html", "block.js", "shared.js", "page.css", "icons/icon-48.png")
    }
    into(layout.buildDirectory.dir("generated/blockPage/web"))
}
android.sourceSets.getByName("main").assets.srcDir(layout.buildDirectory.dir("generated/blockPage"))
tasks.named("preBuild") { dependsOn(blockPage) }

dependencies {
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
    implementation(libs.kotlinx.coroutines.android)

    debugImplementation(libs.androidx.compose.ui.tooling)
}
