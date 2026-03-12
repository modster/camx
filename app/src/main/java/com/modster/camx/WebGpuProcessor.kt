package com.modster.camx

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Handler
import android.os.Looper
import android.util.Base64
import android.webkit.JavascriptInterface
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import java.io.ByteArrayOutputStream

/**
 * Wraps a hidden [WebView] that hosts a WebGPU color-inversion shader written in WGSL.
 *
 * Usage:
 * ```
 * val processor = WebGpuProcessor(context)
 * processor.initialize { /* ready */ }
 * processor.processImage(bitmap) { result -> /* use result */ }
 * processor.destroy()
 * ```
 *
 * The WGSL shader in `assets/webgpu_invert.html` inverts each RGB channel:
 * `output = vec4(1 - r, 1 - g, 1 - b, a)`.
 *
 * If the device's WebView does not support WebGPU (API < 34 or older WebView),
 * the HTML page automatically falls back to a Canvas 2D inversion so the
 * feature always produces a result.
 */
class WebGpuProcessor(private val context: Context) {

    private var webView: WebView? = null
    private var isReady = false
    private var pendingReadyCallback: (() -> Unit)? = null
    private var onProcessedCallback: ((Bitmap?) -> Unit)? = null
    private val mainHandler = Handler(Looper.getMainLooper())

    // ── Initialisation ────────────────────────────────────────────────────────

    /**
     * Creates the backing [WebView] and loads the WebGPU shader page.
     * [onReady] is invoked on the main thread once the page (and GPU adapter)
     * are initialised.
     *
     * Must be called on the main thread.
     */
    @SuppressLint("SetJavaScriptEnabled")
    fun initialize(onReady: () -> Unit) {
        pendingReadyCallback = onReady

        webView = WebView(context).apply {
            settings.apply {
                javaScriptEnabled = true
                domStorageEnabled = true
                // Allow same-origin file loads needed by the shader page
                allowFileAccess = true
                cacheMode = WebSettings.LOAD_NO_CACHE
            }

            addJavascriptInterface(AndroidBridge(), "AndroidInterface")

            webViewClient = object : WebViewClient() {
                override fun onReceivedError(
                    view: WebView?,
                    errorCode: Int,
                    description: String?,
                    failingUrl: String?,
                ) {
                    // If the page itself fails to load, fire the ready callback
                    // anyway so callers are not left waiting.
                    fireReady()
                }
            }

            // The HTML file lives in src/main/assets/
            loadUrl("file:///android_asset/webgpu_invert.html")
        }
    }

    // ── Image processing ──────────────────────────────────────────────────────

    /**
     * Submits [bitmap] to the WebGPU shader page and returns the color-inverted
     * result via [onResult] on the main thread.
     *
     * If the processor is not yet ready, [onResult] is called immediately with
     * `null`.
     */
    fun processImage(bitmap: Bitmap, onResult: (Bitmap?) -> Unit) {
        if (!isReady) {
            onResult(null)
            return
        }

        onProcessedCallback = onResult

        // Encode bitmap → JPEG → Base64 data-URL
        val outputStream = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.JPEG, 90, outputStream)
        val base64 = Base64.encodeToString(outputStream.toByteArray(), Base64.NO_WRAP)
        val dataUrl = "data:image/jpeg;base64,$base64"

        mainHandler.post {
            webView?.evaluateJavascript("processImage('$dataUrl')", null)
        }
    }

    // ── Lifecycle ─────────────────────────────────────────────────────────────

    /** Releases WebView resources. Call from the owning Activity/Fragment's onDestroy. */
    fun destroy() {
        webView?.apply {
            removeJavascriptInterface("AndroidInterface")
            stopLoading()
            destroy()
        }
        webView = null
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private fun fireReady() {
        mainHandler.post {
            if (!isReady) {
                isReady = true
                pendingReadyCallback?.invoke()
                pendingReadyCallback = null
            }
        }
    }

    // ── JavaScript bridge ─────────────────────────────────────────────────────

    /**
     * Methods annotated with [JavascriptInterface] are callable from the
     * WebGPU shader page running inside the hidden WebView.
     */
    inner class AndroidBridge {

        /** Called by the JS page once the GPU adapter is ready (or after fallback init). */
        @JavascriptInterface
        fun onReady() = fireReady()

        /**
         * Called by the JS page after the shader has finished rendering.
         * [base64Result] is a `data:image/png;base64,…` string produced by
         * `canvas.toDataURL()`.
         */
        @JavascriptInterface
        fun onProcessed(base64Result: String) {
            val pureBase64 = base64Result.substringAfter(",")
            val bytes = Base64.decode(pureBase64, Base64.DEFAULT)
            val bitmap: Bitmap? = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)

            mainHandler.post {
                onProcessedCallback?.invoke(bitmap)
                onProcessedCallback = null
            }
        }
    }
}
