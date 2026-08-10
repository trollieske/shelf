package com.shelf.reader.reader.engine

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas as AndroidCanvas
import android.graphics.Matrix
import android.graphics.Paint as AndroidPaint
import android.util.Log
import android.view.View
import android.view.ViewGroup
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import com.shelf.reader.reader.pageturn.ReaderThemeColors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume
import kotlin.math.ceil

private const val TAG = "HtmlPageRenderer"

/**
 * Off-screen [WebView] renderer for professional ebook typography.
 *
 * Density-aware: converts physical-pixel layout dimensions to WebView CSS pixels,
 * so that rendered bitmaps always have the correct 1:1 typographic alignment
 * regardless of device display density.
 *
 * Uses CSS column layout with dynamic text justification, automated hyphenation,
 * publication-grade paragraph indentations, smart quotes, and image bounds checking.
 */
@SuppressLint("SetJavaScriptEnabled")
class HtmlPageRenderer(
    private val context: Context,
    val pageWidth: Int,
    val pageHeight: Int,
) {

    private val density: Float = context.resources.displayMetrics.density.coerceAtLeast(1f)

    val cssPageWidth: Int  = (pageWidth  / density).toInt().coerceAtLeast(1)
    val cssPageHeight: Int = (pageHeight / density).toInt().coerceAtLeast(1)

    private val cssPadTop: Float    = 56f / density
    private val cssPadLeft: Float   = 44f / density
    private val cssPadBottom: Float = 48f / density
    private val cssPadRight: Float  = 44f / density
    private val cssColGap: Float    = 88f / density
    private val cssColWidth: Float  = cssPageWidth - cssPadLeft - cssPadRight
    private val cssImgPadV: Float   = 110f / density
    private val cssQuoteBorder: Float = 3f / density

    private var webView: WebView? = null
    private var _totalPages: Int = 0
    val totalPages: Int get() = _totalPages

    suspend fun prepare(
        htmlContent: String,
        fontSizeSp: Int,
        theme: ReaderThemeColors,
    ): Int = withContext(Dispatchers.Main) {

        val wv = getOrCreateWebView(theme)
        val sanitized = sanitizeHtmlContent(htmlContent)

        val cssPW = cssPageWidth
        val count = withTimeoutOrNull(8_000L) {
            suspendCancellableCoroutine { cont ->
                wv.webViewClient = object : WebViewClient() {
                    override fun onPageFinished(view: WebView, url: String) {
                        view.evaluateJavascript(
                            """
                             (function(){
                               var wrapper = document.getElementById('content-wrapper') || document.body;
                               wrapper.style.transform = 'none';
                               var force = wrapper.offsetHeight;
                               var sw = wrapper.scrollWidth;
                               try {
                                 var range = document.createRange();
                                 range.selectNodeContents(wrapper);
                                 var rects = range.getClientRects();
                                 var mr = 0;
                                 for (var i = 0; i < rects.length; i++) {
                                   if (rects[i].right > mr) mr = rects[i].right;
                                 }
                                 if (mr > sw) sw = mr;
                               } catch(e) {}
                               var pageW = $cssPW;
                               var padL = $cssPadLeft;
                               var effectiveW = Math.max(0, sw - padL + 5);
                               if (effectiveW <= pageW) return 1;
                               var cols = Math.max(1, Math.ceil(effectiveW / pageW));
                               return cols;
                             })()
                            """.trimIndent()
                        ) { result ->
                            val pages = result
                                ?.trim()
                                ?.toDoubleOrNull()
                                ?.let { ceil(it).toInt() }
                                ?.coerceAtLeast(1)
                                ?: 1
                            _totalPages = pages
                            Log.d(TAG, "onPageFinished: totalPages=$pages cssW=$cssPW physW=$pageWidth raw=$result")
                            if (cont.isActive) cont.resume(pages)
                        }
                    }
                }
                val html = buildHtml(sanitized, fontSizeSp, theme)
                val uniqueUrl = "https://shelf.app/reader/${System.currentTimeMillis()}/"
                wv.loadDataWithBaseURL(
                    uniqueUrl,
                    html,
                    "text/html",
                    "UTF-8",
                    null,
                )
                cont.invokeOnCancellation { }
            }
        } ?: run {
            Log.w(TAG, "prepare timed out, falling back to 1 page")
            _totalPages = 1
            1
        }

        count
    }

    suspend fun renderPage(pageIndex: Int): Bitmap = withContext(Dispatchers.Main) {
        val wv = webView ?: error("HtmlPageRenderer not prepared")

        withTimeoutOrNull(5_000L) {
            suspendCancellableCoroutine { cont ->
                val cssOffsetX = pageIndex * cssPageWidth

                wv.evaluateJavascript(
                    """
                    (function(){
                      var el = document.getElementById('content-wrapper') || document.body;
                      el.style.transform = 'translateX(-${cssOffsetX}px)';
                    })();
                    """.trimIndent()
                ) {
                    try {
                        val bmp = Bitmap.createBitmap(pageWidth, pageHeight, Bitmap.Config.ARGB_8888)
                        val canvas = AndroidCanvas(bmp)
                        wv.draw(canvas)
                        if (cont.isActive) cont.resume(bmp)
                    } catch (e: Exception) {
                        Log.e(TAG, "Error drawing webview page $pageIndex", e)
                        if (cont.isActive) cont.resume(Bitmap.createBitmap(pageWidth, pageHeight, Bitmap.Config.ARGB_8888))
                    }
                }
            }
        } ?: Bitmap.createBitmap(pageWidth, pageHeight, Bitmap.Config.ARGB_8888)
    }

    fun createBacksideBitmap(frontBitmap: Bitmap, paperColor: Int): Bitmap {
        val result = Bitmap.createBitmap(pageWidth, pageHeight, Bitmap.Config.ARGB_8888)
        val canvas = AndroidCanvas(result)
        canvas.drawColor(paperColor)
        val mirrorMatrix = Matrix().apply {
            preScale(-1f, 1f, pageWidth / 2f, pageHeight / 2f)
        }
        val paint = AndroidPaint(AndroidPaint.ANTI_ALIAS_FLAG or AndroidPaint.FILTER_BITMAP_FLAG).apply {
            alpha = 26
        }
        canvas.drawBitmap(frontBitmap, mirrorMatrix, paint)
        return result
    }

    fun release() {
        val wv = webView ?: return
        (wv.parent as? ViewGroup)?.removeView(wv)
        wv.destroy()
        webView = null
        _totalPages = 0
    }

    private fun getOrCreateWebView(theme: ReaderThemeColors): WebView {
        webView?.let { return it }

        val wv = WebView(context).also { wv ->
            wv.settings.apply {
                javaScriptEnabled = true
                domStorageEnabled = true
                allowFileAccess = true
                allowContentAccess = true
                @Suppress("DEPRECATION")
                allowFileAccessFromFileURLs = true
                @Suppress("DEPRECATION")
                allowUniversalAccessFromFileURLs = true
                useWideViewPort = true
                loadWithOverviewMode = false
                cacheMode = WebSettings.LOAD_NO_CACHE
                setSupportZoom(false)
                displayZoomControls = false
                layoutAlgorithm = WebSettings.LayoutAlgorithm.NORMAL
            }
            wv.setInitialScale(100)
            try {
                val paperColorInt = android.graphics.Color.parseColor(theme.bodyBg)
                wv.setBackgroundColor(paperColorInt)
            } catch (_: Exception) {
                wv.setBackgroundColor(android.graphics.Color.WHITE)
            }
            wv.isHorizontalScrollBarEnabled = false
            wv.isVerticalScrollBarEnabled = false
            wv.setLayerType(View.LAYER_TYPE_SOFTWARE, null)

            val widthSpec  = View.MeasureSpec.makeMeasureSpec(pageWidth,  View.MeasureSpec.EXACTLY)
            val heightSpec = View.MeasureSpec.makeMeasureSpec(pageHeight, View.MeasureSpec.EXACTLY)
            wv.measure(widthSpec, heightSpec)
            wv.layout(0, 0, pageWidth, pageHeight)
        }

        attachToWindow(wv)

        webView = wv
        return wv
    }

    private fun attachToWindow(wv: WebView) {
        val activity = context as? Activity ?: return
        val decorView = activity.window?.decorView as? ViewGroup ?: return
        val lp = ViewGroup.LayoutParams(pageWidth, pageHeight)
        decorView.addView(wv, lp)
        wv.translationX = -20000f
    }

    private fun sanitizeHtmlContent(raw: String): String {
        return raw
            .replace("&nbsp;", "\u00A0")
            .replace("&mdash;", "—")
            .replace("&ndash;", "–")
            .replace("&hellip;", "…")
            .replace("&ldquo;", "“")
            .replace("&rdquo;", "”")
            .replace("&lsquo;", "‘")
            .replace("&rsquo;", "’")
            .replace("--", "—")
            .replace(Regex("<p>\\s*</p>"), "")
            .replace(Regex("(<br\\s*/?>\\s*){3,}"), "<br/><br/>")
    }

    private fun buildHtml(content: String, fontSizeSp: Int, theme: ReaderThemeColors): String {
        val cw = cssPageWidth
        val ch = cssPageHeight
        val pT = cssPadTop
        val pL = cssPadLeft
        val pB = cssPadBottom
        val pR = cssPadRight
        val cWid = cssColWidth
        val iPV = cssImgPadV
        val qB = cssQuoteBorder
        return """
        <!DOCTYPE html>
        <html>
        <head>
        <meta name="viewport" content="width=device-width, initial-scale=1.0, maximum-scale=1.0, user-scalable=no">
        <style>
          *, *::before, *::after { box-sizing: border-box; }
          html {
            margin: 0; padding: 0;
            height: ${ch}px;
            width: ${cw}px;
            overflow: hidden;
            background: ${theme.htmlBg};
          }
          body {
            margin: 0;
            padding: 0;
            height: ${ch}px;
            width: ${cw}px;
            overflow: hidden;
            background: ${theme.bodyBg};
            color: ${theme.textColor};
            font-family: serif;
            font-size: ${fontSizeSp}px;
            line-height: 1.68;
          }
          #content-wrapper {
            box-sizing: border-box;
            display: block;
            height: ${ch - pT - pB}px;
            margin-top: ${pT}px;
            margin-left: ${pL}px;
            padding: 0;
            width: max-content;
            max-width: none;
            column-width: ${cWid}px;
            column-gap: ${pL + pR}px;
            column-fill: auto;
            column-rule: 0 none transparent;
            orphans: 2;
            widows: 2;
            word-wrap: break-word;
            overflow-wrap: break-word;
            hyphens: auto;
            -webkit-hyphens: auto;
            text-align: justify;
            text-justify: inter-word;
            overflow: visible;
          }
          h1, h2, h3, h4 {
            color: ${theme.headingColor};
            break-after: avoid;
            line-height: 1.35;
            margin: 1.2em 0 0.6em;
            word-break: break-word;
            text-align: center;
            text-indent: 0;
            font-weight: 700;
            letter-spacing: 0.02em;
          }
          h1 { font-size: ${fontSizeSp + 8}px; }
          h2 { font-size: ${fontSizeSp + 4}px; }
          h3 { font-size: ${fontSizeSp + 2}px; }
          p {
            margin: 0 0 0.5em;
            text-align: justify;
            orphans: 2;
            widows: 2;
            word-break: break-word;
            text-indent: 1.4em;
          }
          p.first, h1 + p, h2 + p, h3 + p, header + p {
            text-indent: 0;
          }
          img, svg, figure {
            max-width: 100% !important;
            max-height: calc(${ch}px - ${iPV}px) !important;
            width: auto !important;
            height: auto !important;
            object-fit: contain !important;
            display: block !important;
            margin: 0.5em auto !important;
            break-inside: avoid !important;
          }
          svg {
            width: 100% !important;
            max-height: calc(${ch}px - ${iPV}px) !important;
          }
          blockquote {
            border-left: ${qB}px solid ${theme.headingColor};
            padding-left: 1.1em;
            margin: 1.2em 0;
            opacity: 0.82;
            font-style: italic;
            text-indent: 0;
          }
          hr {
            border: none;
            height: 1px;
            background: rgba(128,128,128,0.25);
            margin: 2em auto;
            width: 40%;
          }
          a { color: ${theme.headingColor}; text-decoration: none; }
          pre, code {
            font-family: monospace;
            font-size: 0.88em;
            background: rgba(128,128,128,0.12);
            border-radius: 4px;
            padding: 0.15em 0.35em;
            word-break: break-all;
          }
        </style>
        </head>
        <body>
          <div id="content-wrapper">$content</div>
        </body>
        </html>
    """.trimIndent()
    }
}
