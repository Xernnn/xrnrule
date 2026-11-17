package com.xern.xrnrule.desktop

import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.util.concurrent.TimeUnit

/** The local page's own tests (`src/test/js`, `node --test`), run with these where node is installed. */
class AppJsTest {
    @Test
    fun thePagesOwnTestsPass() {
        val node = runCatching { ProcessBuilder("node", "--version").start().waitFor(10, TimeUnit.SECONDS) }.getOrDefault(false)
        assumeTrue("node isn't installed", node)
        val run = ProcessBuilder("node", "--test", File("src/test/js").absolutePath).redirectErrorStream(true).start()
        val out = run.inputStream.bufferedReader().readText()
        assertTrue(out, run.waitFor(60, TimeUnit.SECONDS) && run.exitValue() == 0)
    }
}
