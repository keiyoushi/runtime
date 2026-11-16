package keiyoushi.runtime.webviewpatch

import android.annotation.SuppressLint
import android.content.Context
import android.content.res.Resources
import android.view.View
import android.view.ViewGroup
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
    val webView = WebView(context)
    webView.settings.apply {
        javaScriptEnabled = true
        domStorageEnabled = true
        blockNetworkImage = false
    }
    runCatching {
        val metrics = Resources.getSystem().displayMetrics
        webView.layoutParams = ViewGroup.LayoutParams(metrics.widthPixels, metrics.heightPixels)
        webView.measure(
            View.MeasureSpec.makeMeasureSpec(metrics.widthPixels, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(metrics.heightPixels, View.MeasureSpec.EXACTLY),
        )
        webView.layout(0, 0, metrics.widthPixels, metrics.heightPixels)
    }
    webView.webViewClient = WebViewClient()
    webView.webChromeClient = WebChromeClient()
    val scope = WebViewRunScope(webView, deferred)
    try {
        try {
            withTimeout(timeout) {
                scope.configure()
                deferred.await()
            }
        } catch (_: TimeoutCancellationException) {
            throw WebViewTimeoutException(timeout)
        }
    } finally {
        runCatching { (webView.parent as? ViewGroup)?.removeView(webView) }
        webView.stopLoading()
        webView.destroy()
    }
}
