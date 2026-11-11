package com.xern.xrnrule.app.loop

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class LoopHabitsTest {

    @Test
    fun theBroadcastIsFinishedInTimeWhateverTheServerDoes() {
        // xrnloops' "habits changed" waits on a habit round; a server that takes the connection and
        // never answers held the receiver past its window, and Android took the app for hung.
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val hung = scope.launch { delay(60_000) }
            val finished = CountDownLatch(1)
            scope.finishWithin(200, hung, finished::countDown)
            assertTrue(finished.await(5, TimeUnit.SECONDS))
            assertTrue("the round itself goes on", hung.isActive)

            val quick = scope.launch { }
            val alsoFinished = CountDownLatch(1)
            scope.finishWithin(60_000, quick, alsoFinished::countDown)
            assertTrue("and a quick one finishes it at once", alsoFinished.await(5, TimeUnit.SECONDS))
        } finally {
            scope.cancel()
        }
    }
}
