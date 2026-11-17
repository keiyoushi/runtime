package keiyoushi.runtime.webviewpatch

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * The process-wide single-flight behind [Entry.apply]: [singleFlightApply] runs the patch at most once across
 * racing callers and short-circuits to `"applied"` when the patch is already live, so a later caller (or a
 * newer runtime version that finds the live patch) adopts it instead of patching again.
 */
class SingleFlightApplyTest {

    @Test
    fun `ten concurrent callers patch once and all get the same status`() {
        val runs = AtomicInteger(0)
        val lock = Mutex()
        // The one patch sets this live; a caller that reaches the lock after it adopts the result.
        val patched = AtomicBoolean(false)
        val applied = { patched.get() }
        val patch: suspend () -> String = {
            runs.incrementAndGet()
            yield() // widen the window so the ten callers genuinely contend on the lock
            patched.set(true)
            "applied"
        }

        val results = runBlocking {
            val callers = (1..10).map {
                async(Dispatchers.Default) { singleFlightApply(lock, applied, patch) }
            }
            withTimeoutOrNull(10_000L) { callers.map { it.await() } }
        }

        assertNotNull("the callers all settled", results)
        assertEquals("the patch ran exactly once", 1, runs.get())
        assertEquals(List(10) { "applied" }, results)
    }

    @Test
    fun `an already-live patch is adopted without patching`() {
        val runs = AtomicInteger(0)
        val patch: suspend () -> String = {
            runs.incrementAndGet()
            "applied"
        }

        val status = runBlocking { singleFlightApply(Mutex(), applied = { true }, patch = patch) }

        assertEquals("applied", status)
        assertEquals("a live patch is adopted, never re-applied", 0, runs.get())
    }

    @Test
    fun `a failed patch is returned as-is and leaves nothing live for the next call`() {
        val runs = AtomicInteger(0)
        val lock = Mutex()
        var patched = false
        val applied = { patched }
        val patch: suspend () -> String = { "failed:attempt ${runs.incrementAndGet()}" }

        runBlocking {
            assertEquals("failed:attempt 1", singleFlightApply(lock, applied, patch))
            // Nothing went live, so the next call runs the patch again.
            assertEquals("failed:attempt 2", singleFlightApply(lock, applied, patch))
        }

        assertEquals("a failed patch does not block a retry", 2, runs.get())
        assertEquals("patched stays false after failures", false, patched)
    }
}
