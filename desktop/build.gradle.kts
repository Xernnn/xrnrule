import org.gradle.api.tasks.testing.logging.TestExceptionFormat
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

// The laptop half of xrnrule: the same engine (:domain) enforcing the same shared blocks on
// websites (through extension/) and desktop apps (through the KWin script in desktop/kwin, or
// Hyprland's sockets).
plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
    application
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

// Java 17 bytecode and API, built by whatever JDK runs Gradle (17 or newer): no toolchain to find.
kotlin {
    compilerOptions {
        jvmTarget = JvmTarget.JVM_17
        freeCompilerArgs.add("-Xjdk-release=17")
    }
}

application {
    applicationName = "xrnrule-desktop"
    mainClass.set("com.xern.xrnrule.desktop.MainKt")
    // A small, always-on daemon: keep the heap and the JIT modest.
    applicationDefaultJvmArgs = listOf("-Xmx96m", "-XX:+UseSerialGC", "-XX:TieredStopAtLevel=1", "-Xss512k")
}

// This build's version, for the page and /api/state: `git describe` of the checkout it was built
// from (install.sh builds from one), so whether the installed daemon is behind main can be told.
val daemonVersion: Provider<String> = providers.exec {
    commandLine("git", "describe", "--tags", "--always", "--dirty")
    workingDir = rootProject.projectDir
    isIgnoreExitValue = true
}.standardOutput.asText.map { it.trim() }

val versionFile = tasks.register("daemonVersion") {
    val version = daemonVersion
    val out = layout.buildDirectory.file("generated/version/xrnrule-desktop.version")
    inputs.property("version", version)
    outputs.file(out)
    doLast { out.get().asFile.apply { parentFile.mkdirs() }.writeText(version.get()) }
}

// The extension this build was made with, to tell an out-of-date one (see Foreground.extensionVersion).
tasks.processResources {
    from(versionFile)
    from(rootProject.file("extension/manifest.json")) { rename { "extension-manifest.json" } }
    // What the local page shares with the sync server's status page (server/web): the look, the
    // icons, the Insights page, the font and xrnloops' tile. One copy of each, kept there.
    from(rootProject.file("server/web")) {
        include("xrn.css", "ui.js", "insights.js", "MiSansLatinVF.woff2", "xrnloops.svg")
        into("web")
    }
}

dependencies {
    implementation(project(":domain"))
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.dbus.java.core)
    implementation(libs.dbus.java.transport.unixsocket)
    runtimeOnly(libs.slf4j.nop)

    testImplementation(libs.junit)
}

// A failed test's message in full, not only its class and line: AppJsTest's message is everything
// node printed, and on CI the console is the only place to read it.
tasks.test {
    testLogging {
        events("failed")
        exceptionFormat = TestExceptionFormat.FULL
    }
    // ServerIconsTest and EndToEndTest run the sync servers themselves (xrnloops' when it is checked
    // out beside this repo). Without them as inputs, a change to a server alone took the last
    // results from the build cache, on CI too, and its tests never ran against it.
    inputs.files(rootProject.file("server/xrnrule-sync.py"), rootProject.file("../xrnloops/server/xrnloops-sync.py"))
        .withPropertyName("syncServers")
        .withPathSensitivity(PathSensitivity.NAME_ONLY)
}

// The page on made-up data, to look at it (DemoDaemon.kt): ./gradlew :desktop:demo --args="<port> <empty dir> [empty]"
tasks.register<JavaExec>("demo") {
    classpath = sourceSets["test"].runtimeClasspath
    mainClass.set("com.xern.xrnrule.desktop.DemoDaemonKt")
}
