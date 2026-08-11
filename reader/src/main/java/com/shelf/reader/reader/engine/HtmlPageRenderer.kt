package com.shelf.reader.reader.engine

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas as AndroidCanvas
import android.graphics.Color
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.View
import android.view.ViewGroup
import android.webkit.ConsoleMessage
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import com.shelf.reader.reader.pageturn.ReaderThemeColors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.atomic.AtomicLong
import kotlin.coroutines.resume

private const val TAG = "HtmlPageRenderer"

/**
 * Off-screen [WebView] renderer for professional ebook typography.
 */
@SuppressLint("SetJavaScriptEnabled")
class HtmlPageRenderer(
    private val context: Context,
    val pageWidth: Int,
    val pageHeight: Int,
) {
    private val density: Float = context.resources.displayMetrics.density.coerceAtLeast(1f)
    private val cssPageWidth: Int = (pageWidth / density).toInt()
    private val cssQuoteBorder: Float = 3f / density

    private var webView: WebView? = null
    private var _totalPages: Int = 0
    val totalPages: Int get() = _totalPages

    private val activeGeneration = AtomicLong(0L)
    private val renderMutex = Mutex()

    private val _lastMetrics = MutableStateFlow("")
    val lastMetrics: StateFlow<String> = _lastMetrics.asStateFlow()

    private var pendingPrepareCont: CancellableContinuation<Int>? = null
    private var pendingRenderGen = 0L
    private var pendingRenderPage = -1
    private var pendingRenderCont: CancellableContinuation<Bitmap>? = null

    private val jsInterface = object {
        @JavascriptInterface
        fun onLayoutCalculated(gen: Long, totalPages: Int, sw: Int, stride: Int) {
            Handler(Looper.getMainLooper()).post {
                val activeGen = activeGeneration.get()
                if (gen == activeGen) {
                    val cont = pendingPrepareCont
                    pendingPrepareCont = null
                    _lastMetrics.value = "sw=$sw, stride=$stride, pages=$totalPages"
                    Log.i(TAG, "Layout calculated (Gen $gen): $totalPages pages [sw=$sw, stride=$stride]")
                    _totalPages = totalPages
                    if (cont?.isActive == true) cont.resume(totalPages)
                }
            }
        }

        @JavascriptInterface
        fun onPageOffsetApplied(gen: Long, pageIndex: Int) {
            Handler(Looper.getMainLooper()).post {
                if (gen == pendingRenderGen && pageIndex == pendingRenderPage) {
                    val cont = pendingRenderCont
                    pendingRenderCont = null
                    if (cont?.isActive == true) {
                        try {
                            val bmp = Bitmap.createBitmap(pageWidth, pageHeight, Bitmap.Config.ARGB_8888)
                            val canvas = AndroidCanvas(bmp)
                            webView?.draw(canvas)
                            cont.resume(bmp)
                        } catch (e: Exception) {
                            Log.e(TAG, "Render error", e)
                            cont.resume(Bitmap.createBitmap(pageWidth, pageHeight, Bitmap.Config.ARGB_8888))
                        }
                    }
                }
            }
        }
    }

    suspend fun prepare(
        htmlContent: String,
        fontSizeSp: Int,
        theme: ReaderThemeColors,
        lang: String = "en",
    ): Int = withContext(Dispatchers.Main) {
        val result = withTimeoutOrNull(5000L) {
            renderMutex.withLock {
                val gen = activeGeneration.incrementAndGet()
                _totalPages = 0
                val wv = getOrCreateWebView(theme)
                val sanitized = sanitizeHtmlContent(htmlContent)

                suspendCancellableCoroutine { cont ->
                    wv.webViewClient = object : WebViewClient() {
                        override fun onPageFinished(view: WebView, url: String) {
                            if (gen != activeGeneration.get()) {
                                if (cont.isActive) cont.resume(1)
                                return
                            }
                            view.evaluateJavascript(
                                """
                                 (function() {
                                     function measure() {
                                         var wrapper = document.getElementById('content-wrapper');
                                         var sw = wrapper.scrollWidth;
                                         var stride = window.innerWidth;
                                         // Ceil ensures we don't cut off the last word of a chapter
                                         var cols = Math.max(1, Math.ceil((sw - 1) / (stride || 1)));
                                         return {cols: cols, sw: sw, stride: stride};
                                     }
                                     
                                     // Robust retry logic for complex EPUB structures
                                     var m = measure();
                                     if (m.cols <= 1 && document.body.innerText.length > 500) {
                                         setTimeout(function() {
                                             var m2 = measure();
                                             AndroidPageReady.onLayoutCalculated($gen, m2.cols, m2.sw, m2.stride);
                                         }, 300);
                                     } else {
                                         AndroidPageReady.onLayoutCalculated($gen, m.cols, m.sw, m.stride);
                                     }
                                 })();
                                """.trimIndent(), null
                            )
                        }
                    }
                    val html = buildHtml(sanitized, fontSizeSp, theme, lang)
                    pendingPrepareCont = cont
                    wv.loadDataWithBaseURL("https://shelf.app/r/", html, "text/html", "UTF-8", null)
                    cont.invokeOnCancellation { if (activeGeneration.get() == gen) pendingPrepareCont = null }
                }
            }
        }
        result ?: 1
    }

    suspend fun renderPage(pageIndex: Int): Bitmap = withContext(Dispatchers.Main) {
        val result = withTimeoutOrNull(3000L) {
            renderMutex.withLock {
                val gen = activeGeneration.get()
                val wv = webView ?: error("Not prepared")
                suspendCancellableCoroutine { cont ->
                    pendingRenderGen = gen
                    pendingRenderPage = pageIndex
                    pendingRenderCont = cont
                    wv.evaluateJavascript(
                        """
                        (function(){
                          var el = document.getElementById('content-wrapper') || document.body;
                          var stride = window.innerWidth;
                          el.style.transform = 'translateX(' + (-( ${pageIndex} * stride )) + 'px)';
                          // Force browser reflow to ensure sharp text
                          var f = el.offsetHeight;
                          setTimeout(function() { AndroidPageReady.onPageOffsetApplied($gen, $pageIndex); }, 50);
                        })();
                        """.trimIndent(), null
                    )
                    cont.invokeOnCancellation { if (pendingRenderGen == gen && pendingRenderPage == pageIndex) pendingRenderCont = null }
                }
            }
        }
        result ?: Bitmap.createBitmap(pageWidth, pageHeight, Bitmap.Config.ARGB_8888)
    }

    fun release() {
        activeGeneration.incrementAndGet()
        val wv = webView ?: return
        (wv.parent as? ViewGroup)?.removeView(wv)
        wv.destroy()
        webView = null
    }

    private fun getOrCreateWebView(theme: ReaderThemeColors): WebView {
        webView?.let { return it }
        val wv = WebView(context).also { wv ->
            wv.settings.apply {
                javaScriptEnabled = true; domStorageEnabled = true; allowFileAccess = true; allowContentAccess = true
                useWideViewPort = true; loadWithOverviewMode = false; textZoom = 100; cacheMode = WebSettings.LOAD_NO_CACHE
                setSupportZoom(false); displayZoomControls = false; layoutAlgorithm = WebSettings.LayoutAlgorithm.NORMAL
            }
            wv.setBackgroundColor(Color.parseColor(theme.bodyBg))
            wv.isHorizontalScrollBarEnabled = false; wv.isVerticalScrollBarEnabled = false
            wv.setLayerType(View.LAYER_TYPE_SOFTWARE, null)
            wv.addJavascriptInterface(jsInterface, "AndroidPageReady")
            wv.webChromeClient = object : WebChromeClient() {
                override fun onConsoleMessage(msg: ConsoleMessage): Boolean {
                    Log.d("chromium", msg.message()); return true
                }
            }
            wv.measure(View.MeasureSpec.makeMeasureSpec(pageWidth, View.MeasureSpec.EXACTLY),
                       View.MeasureSpec.makeMeasureSpec(pageHeight, View.MeasureSpec.EXACTLY))
            wv.layout(0, 0, pageWidth, pageHeight)
        }
        val activity = context as? Activity
        val decor = activity?.window?.decorView as? ViewGroup
        if (wv.parent != null) (wv.parent as? ViewGroup)?.removeView(wv)
        decor?.addView(wv, ViewGroup.LayoutParams(pageWidth, pageHeight))
        wv.translationX = -10000f
        webView = wv
        return wv
    }

    private fun sanitizeHtmlContent(raw: String) = raw.replace("&nbsp;", "\u00A0").replace("&mdash;", "—").replace("&ndash;", "–")
        .replace("&hellip;", "…").replace("&ldquo;", "“").replace("&rdquo;", "”")
        .replace("&lsquo;", "‘").replace("&rsquo;", "’").replace("--", "—")
        .replace(Regex("<p>\\s*</p>"), "").replace(Regex("(<br\\s*/?>\\s*){3,}"), "<br/><br/>")

    private fun buildHtml(content: String, fontSizeSp: Int, theme: ReaderThemeColors, lang: String): String {
        return """
        <!DOCTYPE html>
        <html lang="${lang.ifEmpty { "en" }}">
        <head>
        <meta name="viewport" content="width=device-width, initial-scale=1.0, maximum-scale=1.0, user-scalable=no, viewport-fit=cover">
        <style>
          *, *::before, *::after { box-sizing: border-box; }
          html, body { 
            margin: 0; padding: 0; height: 100vh; width: 100vw; 
            overflow: hidden; background: ${theme.bodyBg}; 
            -webkit-text-size-adjust: none;
          }
          body { 
            color: ${theme.textColor}; 
            font-family: "Crimson Pro", "EB Garamond", "Palatino", "Georgia", serif; 
            font-size: ${fontSizeSp}px; 
            line-height: 1.6; 
            text-rendering: optimizeLegibility;
            -webkit-font-smoothing: antialiased;
          }
          #content-wrapper {
            display: block; 
            height: 100vh !important; 
            width: 100% !important;
            margin: 0;
            padding: 0;
            column-width: 100vw !important; 
            column-gap: 0 !important; 
            column-fill: auto;
            word-wrap: break-word; 
            overflow-wrap: break-word; 
            hyphens: auto; 
            -webkit-hyphens: auto; 
            text-align: justify;
            overflow: visible; 
            will-change: transform;
            orphans: 1;
            widows: 1;
          }
          h1, h2, h3 { color: ${theme.headingColor}; text-align: center !important; margin: 1.2em 0 0.6em !important; font-weight: 700 !important; line-height: 1.3; }
          h1 { font-size: 1.5em !important; }
          h2 { font-size: 1.3em !important; }
          h3 { font-size: 1.15em !important; }
          p { margin: 0 0 0.6em !important; text-align: justify !important; text-indent: 1.5em !important; line-height: 1.6 !important; }
          img, svg { max-width: 100% !important; height: auto !important; display: block !important; margin: 0.8em auto !important; }
          blockquote { border-left: ${cssQuoteBorder}px solid ${theme.headingColor}; padding-left: 1.2em; margin: 1.5em 0; font-style: italic; opacity: 0.9; }
        </style>
        </head>
        <body><div id="content-wrapper">$content</div></body>
        </html>
    """.trimIndent()
    }
}
