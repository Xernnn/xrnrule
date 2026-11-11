package com.xern.xrnrule.desktop

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File
import java.net.URI
import java.nio.file.Files
import java.util.concurrent.ConcurrentHashMap

class SiteIconCacheTest {

    private fun await(what: String, ok: () -> Boolean) {
        val until = System.currentTimeMillis() + 10_000
        while (!ok()) {
            check(System.currentTimeMillis() < until) { "timed out waiting for: $what" }
            Thread.sleep(20)
        }
    }

    /** The hosts kept on disk, an icon or a miss each. */
    private fun kept(dir: File, prefix: String = ""): Int =
        dir.listFiles().orEmpty().count { it.name.startsWith(prefix) && !it.name.startsWith(".") && it.name.endsWith(".miss") }

    @Test
    fun aRunLooksForSoManyHostsAndTheDiskKeepsNoMore() {
        val dir = Files.createTempDirectory("xrnrule-icons").toFile()
        try {
            val looked = ConcurrentHashMap.newKeySet<String>()
            val fetcher = { url: String, _: Int -> looked += URI(url).host; null }
            val max = SiteIconCache.MAX_HOSTS
            // A web page can name endless hosts under a blocked site: a1.youtube.com, a2.youtube.com…
            val run = SiteIconCache(dir, CoroutineScope(Dispatchers.Unconfined), fetcher)
            repeat(max + 50) { run.icon("a$it.youtube.com") }
            await("a run's misses kept") { kept(dir) >= max }
            Thread.sleep(300) // the rest would have been fetched by now
            assertEquals(max, looked.size)
            assertEquals(max, kept(dir))

            // The next run (the daemon restarted) asks for as many new ones: the disk keeps no more,
            // the oldest going first.
            val next = SiteIconCache(dir, CoroutineScope(Dispatchers.Unconfined), fetcher)
            repeat(max) { next.icon("b$it.youtube.com") }
            await("the next run's misses kept") { kept(dir, "b") == max }
            assertEquals(max, kept(dir))
        } finally {
            dir.deleteRecursively()
        }
    }
}
