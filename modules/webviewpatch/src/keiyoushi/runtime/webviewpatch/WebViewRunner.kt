package keiyoushi.runtime.webviewpatch

import android.annotation.SuppressLint
import android.content.Context
import android.content.res.Resources
import android.view.View
import android.view.ViewGroup
import android.webkit.RenderProcessGoneDetail
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/** Thrown by [runWebView] on timeout: a plain [Exception], not a cancellation, so callers see a failure. */
internal class WebViewTimeoutException(timeout: Duration) : Exception("Timed out waiting for WebView after $timeout")

/**
 * Thrown into a [runWebView] run when its WebView's render process dies. A plain [Exception] (not a
 * cancellation), so the run fails rather than hangs and [LoopbackProbe] maps it to
 * [LoopbackProbe.Result.INCONCLUSIVE].
 */
internal class RenderProcessGoneException(didCrash: Boolean) : Exception("WebView render process gone (didCrash=$didCrash)")

/**
 * The slice of the run a configure block drives: load a URL, and resolve the run with a value. This module's
 * two uses ([Entry.warmWebViewLibrary] and [LoopbackProbe.probe]) need nothing more, so the runner stays
 * minimal and pulls in no OkHttp or request-interception machinery from :core's `runWebView`.
 */
internal class WebViewRunScope<T> internal constructor(
    private val webView: WebView,
    private val deferred: CompletableDeferred<T>,
) {
    /** Completes the run with [value]. First completion wins; later calls are no-ops. */
    fun resolve(value: T) {
        deferred.complete(value)
    }

    /** Loads [url] in the run's WebView. */
    fun loadUrl(url: String) {
        webView.loadUrl(url)
    }
}

/**
 * Runs a throwaway WebView with [configure], suspending until it calls `resolve` or [timeout] elapses. The
 * WebView is built on the main thread with JavaScript, DOM storage and network images on, laid out to screen
 * size so it loads subresources and runs timers, and torn down whether this returns, throws or is cancelled.
 *
 * Constructing the WebView is what maps its native library into the process, which is why the module runs one
 * before patching ([Entry.warmWebViewLibrary]); [LoopbackProbe] drives one to load its loopback self-test page.
 */
@SuppressLint("SetJavaScriptEnabled")
internal suspend fun <T> runWebView(
    context: Context,
    timeout: Duration = 30.seconds,
    configure: suspend WebViewRunScope<T>.() -> Unit,
): T = withContext(Dispatchers.Main) {
    val deferred = CompletableDeferred<T>()
    var webView: WebView? = null
    try {
        val view = WebView(context).also { webView = it }
        view.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            blockNetworkImage = false
        }
        runCatching {
            val metrics = Resources.getSystem().displayMetrics
            view.layoutParams = ViewGroup.LayoutParams(metrics.widthPixels, metrics.heightPixels)
            view.measure(
                View.MeasureSpec.makeMeasureSpec(metrics.widthPixels, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(metrics.heightPixels, View.MeasureSpec.EXACTLY),
            )
            view.layout(0, 0, metrics.widthPixels, metrics.heightPixels)
        }
        view.webViewClient = RenderGoneWebViewClient(view, deferred)
        view.webChromeClient = WebChromeClient()
        val scope = WebViewRunScope(view, deferred)
        try {
            withTimeout(timeout) {
                scope.configure()
                deferred.await()
            }
        } catch (_: TimeoutCancellationException) {
            throw WebViewTimeoutException(timeout)
        }
    } finally {
        webView?.let { view ->
            runCatching { (view.parent as? ViewGroup)?.removeView(view) }
            runCatching { view.stopLoading() }
            runCatching { view.destroy() }
        }
    }
}

/**
 * Keeps a dead render process from taking down the host app. With a plain [WebViewClient], a render-process
 * death makes the framework terminate the host process (the reader app), not just the WebView. Here that
 * death happens after the patch is live, so [onRenderProcessGone] completes the run exceptionally — which
 * [LoopbackProbe] maps to [LoopbackProbe.Result.INCONCLUSIVE] so [XrwStripEngine] reverts — destroys the
 * dead WebView, and returns `true` to claim the crash.
 */
private class RenderGoneWebViewClient(
    private val webView: WebView,
    private val deferred: CompletableDeferred<*>,
) : WebViewClient() {
    override fun onRenderProcessGone(view: WebView?, detail: RenderProcessGoneDetail?): Boolean {
        deferred.completeExceptionally(RenderProcessGoneException(detail?.didCrash() == true))
        runCatching { webView.destroy() }
        return true
    }
}
