package com.xern.xrnrule.desktop

import org.junit.Assert.fail
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.util.concurrent.TimeUnit

/** The local page's own tests (`src/test/js`, `node --test`), run with these where node is installed. */
class AppJsTest {
    @Test
    fun thePagesOwnTestsPass() {
        val version = runCatching {
            val probe = ProcessBuilder("node", "--version").redirectErrorStream(true).start()
            probe.inputStream.bufferedReader().readText().trim().takeIf { probe.waitFor(10, TimeUnit.SECONDS) && probe.exitValue() == 0 }
        }.getOrNull()
        assumeTrue("node isn't installed", version != null)
        val command = listOf("node", "--test", File("src/test/js").absolutePath)
        // Into a file, not read off a pipe: reading the pipe to its end would wait out a node that
        // never exits, and the timeout below would never come.
        val log = File.createTempFile("xrnrule-app-js", ".log").apply { deleteOnExit() }
        val run = ProcessBuilder(command).redirectErrorStream(true).redirectOutput(log).start()
        val finished = run.waitFor(60, TimeUnit.SECONDS)
        if (!finished) run.destroyForcibly().waitFor(10, TimeUnit.SECONDS)
        if (!finished || run.exitValue() != 0) {
            // Everything node said, in the failure's message: on CI that message is the only place
            // to read it (the build prints it in full, see build.gradle.kts).
            val how = if (finished) "exited ${run.exitValue()}" else "was stopped after 60 s"
            fail("${command.joinToString(" ")} on node $version $how:\n${log.readText()}")
        }
    }
}
