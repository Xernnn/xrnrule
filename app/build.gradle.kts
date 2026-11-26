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
        // The phone and the self-update channel go by versionCode alone; the name is for people.
        // scripts/release.mjs may raise the code past what the server has, and leaves the name.
        versionCode = 27
        versionName = "0.8.0"
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
        // `run-as` into the app's files (the sync password is there). Shrunk by R8, with the
        // keep rules in proguard-rules.pro (checkReleaseKeeps below checks the ones reflection
        // needs). A world.json the app can't read starts it empty, so open a release build on
        // a copy of real data before shipping one.
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
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
            include("block.html", "block.js", "shared.js", "page.css", "icons/icon.svg", "icons/icon-48.png", "fonts/MiSansLatinVF.woff2", "manifest.json")
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

// What something builds by reflection, which R8 removes unless a keep rule names its constructor
// (copied from xrnimg, whose 0.1.0 release crashed at every start that way, 2026-11-22). Debug
// builds and the unit tests never shrink, so only a release shows it: this reads R8's seeds (what
// keep rules hold) after the sideload release is shrunk and fails on any of these missing, and on
// a serializable class of the app's or the engine's whose serializer went.
//     ./gradlew :app:checkReleaseKeeps
tasks.register("checkReleaseKeeps") {
    group = "verification"
    description = "Fails when R8 would drop a constructor only reflection calls, or a serializer."
    dependsOn("minifySideloadReleaseWithR8", "processSideloadReleaseMainManifest")
    val seeds = layout.buildDirectory.file("outputs/mapping/sideloadRelease/seeds.txt").get().asFile
    val manifest = layout.buildDirectory.file(
        "intermediates/merged_manifest/sideloadRelease/processSideloadReleaseMainManifest/AndroidManifest.xml").get().asFile
    val sources = listOf(file("src/main/kotlin"), rootProject.file("domain/src/main/kotlin"))
    inputs.files(seeds, manifest)
    sources.forEach { inputs.dir(it) }
    doLast {
        val kept = seeds.readLines().map { it.trim() }.toSet()
        val wanted = mutableListOf(
            // Room: Room.databaseBuilder makes the generated <Name>_Impl with Class.newInstance().
            "androidx.work.impl.WorkDatabase_Impl: WorkDatabase_Impl()",
            // WorkManager: the input merger is named in each stored WorkSpec, made by its name.
            "androidx.work.OverwritingInputMerger: OverwritingInputMerger()",
        )
        val worker = Regex("""class\s+(\w+)\s*\([^)]*\)\s*:\s*(?:CoroutineWorker|Worker|ListenableWorker)\(""")
        val serializable = Regex("""@Serializable(?:\([^)]*\))?\s+(?:data\s+|enum\s+)?class\s+(\w+)""")
        val serializers = mutableListOf<String>()
        sources.forEach { dir ->
            dir.walkTopDown().filter { it.extension == "kt" }.forEach { file ->
                val text = file.readText()
                val pkg = Regex("""^package\s+([\w.]+)""", RegexOption.MULTILINE).find(text)?.groupValues?.get(1)
                worker.findAll(text).forEach {
                    val name = it.groupValues[1]
                    wanted += "$pkg.$name: $name(android.content.Context,androidx.work.WorkerParameters)"
                }
                // Top-level ones only: a nested class's name in the seeds has its outer class's in front.
                serializable.findAll(text).filter { m -> text.lastIndexOf('\n', m.range.first).let { text[it + 1] == '@' } }
                    .forEach { serializers += "$pkg.${it.groupValues[1]}" }
            }
        }
        // androidx.startup: each initializer the merged manifest names, made with no arguments.
        Regex("""android:name="([\w.$]+)"\s+android:value="androidx.startup"""").findAll(manifest.readText()).forEach {
            val name = it.groupValues[1]
            wanted += "$name: ${name.substringAfterLast('.')}()"
        }
        val missing = wanted.filter { it !in kept } +
            serializers.filter { cls -> kept.none { it.startsWith("$cls\$Companion: kotlinx.serialization.KSerializer serializer(") || it.startsWith("$cls: kotlinx.serialization.KSerializer serializer(") } }
                .map { "$it (its serializer)" }
        if (missing.isNotEmpty()) {
            throw GradleException("R8 drops what only reflection or a serializer reaches (the app would crash, or read " +
                "world.json as empty). Keep them in app/proguard-rules.pro:\n" + missing.joinToString("\n") { "  $it" })
        }
        println("R8 keeps all ${wanted.size} constructors built by reflection and ${serializers.size} serializers")
    }
}
