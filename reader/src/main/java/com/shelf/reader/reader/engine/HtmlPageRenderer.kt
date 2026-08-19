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
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import kotlin.coroutines.resume

private const val TAG = "HtmlPageRenderer"

data class HighlightData(
    val text: String,
    val colorInt: Int,
    val pageIndex: Int,
    val startPageOffset: Float,
    val endPageOffset: Float,
)

/**
 * Off-screen [WebView] renderer for professional ebook typography.
 */
@SuppressLint("SetJavaScriptEnabled")
class HtmlPageRenderer(
    private val context: Context,
    val pageWidth: Int,
    val pageHeight: Int,
    val onHighlightSaved: (HighlightData) -> Unit = {},
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
    private val pendingRenders = ConcurrentHashMap<Pair<Long, Int>, CancellableContinuation<Bitmap>>()

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
                val key = Pair(gen, pageIndex)
                val cont = pendingRenders.remove(key)
                if (cont?.isActive == true) {
                    try {
                        val bmp = Bitmap.createBitmap(pageWidth, pageHeight, Bitmap.Config.ARGB_8888)
                        val canvas = AndroidCanvas(bmp)
                        webView?.draw(canvas)
                        // — FIX E VERIFY — Pixel-sampling after WebView.draw(canvas) ———————————————
                        // Distinguishes two possible root causes of duplicate/stale pages:
                        //   (i)  WebView.draw genuinely produced a BLANK / UNIFORM bitmap because the
                        //        HWUI dirty-rect rejection (see Motorola logs) corrupted the capture.
                        //        In that case a layout fix (LayoutParams 0×0) would be needed.
                        //   (ii) The bitmap has real content but the RACE from FIX B caused a
                        //        wrong-key misroute, and a timeout produced a separate blank.
                        // Samples 9 strategically-chosen pixels; if ALL share the same single color
                        // we report the bitmap as suspiciously uniform.
                        val sampleCount = 9
                        val sampleCoords = IntArray(sampleCount * 2).also { c ->
                            val w = pageWidth; val h = pageHeight
                            val midW = w / 2; val midH = h / 2
                            c[0]=0;         c[1]=0          // top-left
                            c[2]=midW;      c[3]=0          // top
                            c[4]=w-1;       c[5]=0          // top-right
                            c[6]=0;         c[7]=midH       // mid-left
                            c[8]=midW;      c[9]=midH       // center (most diagnostic for text)
                            c[10]=w-1;      c[11]=midH      // mid-right
                            c[12]=0;        c[13]=h-1       // bot-left
                            c[14]=midW;     c[15]=h-1       // bot
                            c[16]=w-1;      c[17]=h-1       // bot-right
                        }
                        var prevColor: Int? = null
                        var allUniform = true
                        var uniqueColors = 0
                        var nonZeroAlpha = 0
                        var centerPxColor: Int = 0
                        val centerX = pageWidth / 2; val centerY = pageHeight / 2
                        try {
                            centerPxColor = bmp.getPixel(centerX, centerY)
                            var i = 0
                            while (i < sampleCount) {
                                val px = sampleCoords[i * 2]; val py = sampleCoords[i * 2 + 1]
                                val c = try { bmp.getPixel(px, py) } catch (_: Throwable) { 0 }
                                if (c ushr 24 != 0) nonZeroAlpha++
                                if (prevColor == null) { prevColor = c; uniqueColors = 1 }
                                else if (prevColor != c) { allUniform = false; uniqueColors++ }
                                i++
                            }
                        } catch (e: Throwable) {
                            allUniform = false; uniqueColors = -1; centerPxColor = 0
                        }
                        val expectedBg: Int = try {
                            (webView?.background as? android.graphics.drawable.ColorDrawable)?.color ?: 0
                        } catch (_: Throwable) { 0 }
                        Log.d(TAG, "Render bitmap analyze (gen=$gen, page=$pageIndex, fx=E_verify): " +
                            "uniform9px=$allUniform " +
                            "uniqueColors=$uniqueColors/$sampleCount " +
                            "nonZeroAlphaPx=$nonZeroAlpha/$sampleCount " +
                            "bgMatchesDrawable=${expectedBg != 0 && prevColor != null && expectedBg == prevColor} " +
                            "centerPxARGB=0x${Integer.toHexString(centerPxColor)} " +
                            "cornerPxARGB=0x${Integer.toHexString(prevColor ?: 0)} " +
                            "w=$pageWidth h=$pageHeight")
                        if (allUniform && uniqueColors == 1 && nonZeroAlpha == 0) {
                            Log.w(TAG, "FIX E: Render bitmap appears GENUINELY BLANK (all 9 sampled pixels = 0x00000000 alpha=0). " +
                                "This correlates with the HWUI dirty-rect warnings; a layout/translation fix is indicated. " +
                                "(gen=$gen, page=$pageIndex)")
                        } else if (allUniform) {
                            Log.w(TAG, "FIX E: Render bitmap is UNIFORM (all 9 sampled pixels match). " +
                                "Likely means the page really was blank or only background rendered. " +
                                "(gen=$gen, page=$pageIndex)")
                        }
                        // — END FIX E VERIFY ———————————————————————————————————————————————————————
                        cont.resume(bmp)
                    } catch (e: Exception) {
                        Log.e(TAG, "Render error (gen=$gen, page=$pageIndex)", e)
                        cont.resume(Bitmap.createBitmap(pageWidth, pageHeight, Bitmap.Config.ARGB_8888))
                    }
                } else {
                    Log.d(TAG, "onPageOffsetApplied: No matching pending render for (gen=$gen, page=$pageIndex). activeKeys=${pendingRenders.keys}")
                }
            }
        }

        @JavascriptInterface
        fun onHighlightCreated(text: String, colorInt: Int, pageIndex: Int, startOff: Double, endOff: Double) {
            try {
                onHighlightSaved(
                    HighlightData(
                        text = text,
                        colorInt = colorInt,
                        pageIndex = pageIndex,
                        startPageOffset = startOff.toFloat(),
                        endPageOffset = endOff.toFloat()
                    )
                )
            } catch (e: Exception) {
                Log.e(TAG, "Highlight bridge error", e)
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
                    val key = Pair(gen, pageIndex)
                    pendingRenders[key] = cont
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
                    cont.invokeOnCancellation {
                        pendingRenders.remove(key, cont)
                    }
                }
            }
        }
        result ?: Bitmap.createBitmap(pageWidth, pageHeight, Bitmap.Config.ARGB_8888)
    }

    fun release() {
        activeGeneration.incrementAndGet()
        pendingRenders.values.forEach { it.cancel() }
        pendingRenders.clear()
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
          ::selection { background: rgba(255, 205, 90, 0.45); }
          .__hl_float { position: fixed; z-index: 9999; display: none; padding: 6px; background: rgba(30,30,32,0.96); border-radius: 10px; box-shadow: 0 4px 14px rgba(0,0,0,0.35); }
          .__hl_btn { display: inline-block; width: 22px; height: 22px; border-radius: 50%; margin: 0 3px; cursor: pointer; border: 2px solid rgba(255,255,255,0.7); }
        </style>
        </head>
        <body><div id="content-wrapper">$content</div>
        <script>
        (function() {
            var colors = [
                { hex: '#FFDD55', android: 0xFFFFFF7F & 0xFFFFFFFF },
                { hex: '#FF9AA2', android: 0xFFFF9AA2 & 0xFFFFFFFF },
                { hex: '#B5DEFF', android: 0xFFB5DEFF & 0xFFFFFFFF },
                { hex: '#C7CEEA', android: 0xFFC7CEEA & 0xFFFFFFFF },
                { hex: '#A0E7E5', android: 0xFFA0E7E5 & 0xFFFFFFFF },
                { hex: '#B4F8C8', android: 0xFFB4F8C8 & 0xFFFFFFFF }
            ];
            var ui = document.createElement('div');
            ui.className = '__hl_float';
            ui.innerHTML = colors.map(function(c){ return '<span class="__hl_btn" data-c="'+c.android+'" style="background:'+c.hex+'"></span>' }).join('');
            document.body.appendChild(ui);
            var btns = ui.querySelectorAll('.__hl_btn');
            for (var i = 0; i < btns.length; i++) {
                btns[i].addEventListener('click', function(ev){
                    ev.preventDefault();
                    ev.stopPropagation();
                    var sel = window.getSelection();
                    if (!sel || sel.rangeCount === 0 || sel.isCollapsed) { ui.style.display = 'none'; return; }
                    var text = sel.toString();
                    if (!text || text.trim().length === 0) { ui.style.display = 'none'; return; }
                    var cInt = parseInt(this.getAttribute('data-c'), 10);
                    var pageWidth = window.innerWidth || document.documentElement.clientWidth || 1;
                    var rect = sel.getRangeAt(0).getBoundingClientRect();
                    var page = Math.max(0, Math.round(rect.left / pageWidth));
                    var startFrac = Math.max(0, Math.min(1, ((page * pageWidth) - rect.left + pageWidth) / pageWidth));
                    var endFrac = Math.max(0, Math.min(1, ((page * pageWidth) - rect.right + pageWidth) / pageWidth));
                    try { AndroidPageReady.onHighlightCreated(text, cInt, page, Math.min(startFrac, endFrac), Math.max(startFrac, endFrac)); } catch(e) {}
                    sel.removeAllRanges();
                    ui.style.display = 'none';
                });
            }
            function hideIfOutside(e){ if (ui.style.display === 'none') return; var r = ui.getBoundingClientRect(); if (e.clientX < r.left || e.clientX > r.right || e.clientY < r.top || e.clientY > r.bottom) ui.style.display = 'none'; }
            document.addEventListener('selectionchange', function(){
                var sel = window.getSelection();
                if (!sel || sel.rangeCount === 0 || sel.isCollapsed || sel.toString().trim().length === 0) { ui.style.display = 'none'; return; }
                var rect = sel.getRangeAt(0).getBoundingClientRect();
                ui.style.display = 'block';
                var top = rect.top - 48;
                if (top < 4) top = rect.bottom + 6;
                var left = rect.left + rect.width/2 - ui.offsetWidth/2;
                if (left < 4) left = 4;
                var maxL = (window.innerWidth || 360) - ui.offsetWidth - 4;
                if (left > maxL) left = maxL;
                ui.style.top = top + 'px';
                ui.style.left = left + 'px';
            });
            document.addEventListener('mousedown', hideIfOutside);
            document.addEventListener('scroll', function(){ ui.style.display = 'none'; }, true);
        })();
        </script>
        </body>
        </html>
    """.trimIndent()
    }
}
