package keiyoushi.runtime.webviewpatch

import android.content.Context
import keiyoushi.lib.webviewpatch.WebViewPatches
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * The Keiyoushi Runtime's implementation of [WebViewPatches]. It owns the one copy of the patch machinery in
 * the process ([XrwStripper] over [XrwStripEngine]); extensions reach it through the thin `lib:webviewpatch`
 * shim. The applied-patch record lives in process-wide shared state (see [RuntimeSharedState]), so a later
 * caller — or a newer runtime version loaded mid-session — reads that the patch is already live and returns
 * without writing again.
 *
 * [apply] returns only once a patch has applied and self-tested, so a caller can hold every WebView request
 * until it sees `"applied"`. The WebView library is mapped before the first navigation by [warmWebViewLibrary].
 */
object Entry : WebViewPatches {

    private const val REQUESTED_WITH = "requested-with"

    // One requested-with apply runs at a time in this process.
    private val applyLock = Mutex()

    override suspend fun apply(id: String, context: Context, runJob: Job?): String {
        if (id != REQUESTED_WITH) return "unknown"
        val app = context.applicationContext
        return try {
            applyRequestedWith(app, runJob)
        } catch (c: CancellationException) {
            throw c
        } catch (t: Throwable) {
            failedStatus(t)
        }
    }

    private suspend fun applyRequestedWith(app: Context, runJob: Job?): String {
        XrwStripper.hostUnsupportedReason()?.let { return "unsupported:$it" }

        val status = singleFlightApply(applyLock, { XrwStripper.stripped() }) {
            warmWebViewLibrary(app)
            XrwStripper.patchProcess(app)
            // stripped() reads the live bytes and is authoritative; lastStatus only supplies the failure detail.
            if (XrwStripper.stripped()) {
                "applied"
            } else {
                val detail = XrwStripper.lastStatus()
                if (detail == null || detail == "applied") "failed:patch did not take" else detail
            }
        }

        // Promote the patch to OK once a run that held the patched process survives it. markRunSurvived takes
        // the pending record atomically, so several runs ending all call it but only one promotes.
        if (status == "applied" && XrwStripper.hasPendingSurvival()) {
            runJob?.invokeOnCompletion { promoteIfPending(app) }
        }
        return status
    }

    private fun promoteIfPending(app: Context) {
        if (XrwStripper.hasPendingSurvival()) XrwStripper.markRunSurvived(app)
    }

    // Constructing a WebView maps the provider's native library into this process. The run loads nothing and
    // resolves at once. A host that can't build a WebView falls through to patchProcess finding no library.
    private suspend fun warmWebViewLibrary(app: Context) {
        runCatching { runWebView<Unit>(app) { resolve(Unit) } }
    }

    private fun failedStatus(t: Throwable): String = "failed:${t.message ?: t.javaClass.simpleName}"
}

/**
 * Runs [patch] at most once across the callers racing on [lock]. Returns `"applied"` without calling [patch]
 * when [applied] already holds — before taking the lock for the common adopt case, and again under it so a
 * caller that waited behind the one that patched sees the result instead of patching a second time.
 */
internal suspend fun singleFlightApply(
    lock: Mutex,
    applied: () -> Boolean,
    patch: suspend () -> String,
): String {
    if (applied()) return "applied"
    return lock.withLock {
        if (applied()) "applied" else patch()
    }
}
