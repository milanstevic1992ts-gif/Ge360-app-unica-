package it.edilmilan.ge360.planimetrie

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.util.Base64
import android.view.Gravity
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.webkit.WebViewAssetLoader
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import androidx.webkit.WebMessageCompat
import androidx.webkit.JavaScriptReplyProxy
import org.json.JSONObject
import com.google.android.material.button.MaterialButton
import it.edilmilan.ge360.core.common.ui.Ge360Brand

/**
 * GE360 OpenPlan3D embedded locally, with a secure HTTPS asset origin.
 *
 * Its localStorage/IndexedDB is distinct from the native FloorPlanStore.
 * This Activity never imports or overwrites a native survey/estimate.
 * The frontend contacts Debian only when the user configures its server.
 */
class AdvancedSurveyActivity : AppCompatActivity() {

    private var browser: WebView? = null
    private var pendingFiles: ValueCallback<Array<Uri>>? = null
    private data class PendingExport(val name: String, val mime: String, val bytes: ByteArray)
    private var pendingExport: PendingExport? = null
    private val assets by lazy {
        WebViewAssetLoader.Builder()
            .addPathHandler("/assets/", WebViewAssetLoader.AssetsPathHandler(this))
            .build()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val density = resources.displayMetrics.density
        fun dp(value: Int) = (value * density).toInt()

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Ge360Brand.Background)
        }
        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(8), dp(4), dp(8), dp(4))
            setBackgroundColor(Ge360Brand.Surface)
            addView(MaterialButton(this@AdvancedSurveyActivity).apply {
                text = "‹ GE360"
                setOnClickListener { confirmExit() }
            })
            addView(TextView(this@AdvancedSurveyActivity).apply {
                text = "Rilievo avanzato · OpenPlan3D"
                textSize = 14f
                setTextColor(Ge360Brand.Text)
            }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        }
        root.addView(bar)

        val web = WebView(this).apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.allowFileAccess = false
            settings.allowContentAccess = false
            settings.javaScriptCanOpenWindowsAutomatically = false
            settings.setSupportMultipleWindows(false)
            settings.mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
            settings.cacheMode = WebSettings.LOAD_DEFAULT
            isLongClickable = true
            setBackgroundColor(Ge360Brand.Background)
            CookieManager.getInstance().setAcceptThirdPartyCookies(this, false)

            webViewClient = object : WebViewClient() {
                override fun shouldInterceptRequest(
                    view: WebView?,
                    request: WebResourceRequest
                ): WebResourceResponse? {
                    val uri = request.url
                    return if (uri.scheme == "https" && uri.host == ASSET_HOST) {
                        assets.shouldInterceptRequest(uri)
                    } else null
                }

                override fun shouldOverrideUrlLoading(
                    view: WebView?,
                    request: WebResourceRequest
                ): Boolean {
                    val uri = request.url
                    if (uri.scheme == "https" && uri.host == ASSET_HOST &&
                        uri.path.orEmpty().startsWith(ASSET_ROOT)) return false
                    if (uri.scheme == "blob" || uri.scheme == "about") return false
                    // Open ordinary HTTPS external links in the user's browser.
                    if (uri.scheme == "https") {
                        runCatching { startActivity(Intent(Intent.ACTION_VIEW, uri)) }
                    }
                    return true
                }

                override fun onReceivedError(
                    view: WebView?,
                    request: WebResourceRequest,
                    error: android.webkit.WebResourceError?
                ) {
                    if (request.isForMainFrame) {
                        Toast.makeText(
                            this@AdvancedSurveyActivity,
                            "Impossibile aprire l'interfaccia Rilievi locale",
                            Toast.LENGTH_LONG
                        ).show()
                    }
                }

                override fun onRenderProcessGone(
                    view: WebView?,
                    detail: android.webkit.RenderProcessGoneDetail?
                ): Boolean {
                    Toast.makeText(
                        this@AdvancedSurveyActivity,
                        "Il visualizzatore Rilievi si e' arrestato. I dati salvati restano sul telefono.",
                        Toast.LENGTH_LONG
                    ).show()
                    browser = null
                    view?.destroy()
                    finish()
                    return true
                }
            }
            webChromeClient = object : WebChromeClient() {
                override fun onShowFileChooser(
                    view: WebView?,
                    filePathCallback: ValueCallback<Array<Uri>>?,
                    fileChooserParams: FileChooserParams?
                ): Boolean {
                    pendingFiles?.onReceiveValue(null)
                    pendingFiles = filePathCallback
                    val intent = runCatching {
                        fileChooserParams?.createIntent()
                    }.getOrNull() ?: Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                        addCategory(Intent.CATEGORY_OPENABLE)
                        type = "*/*"
                    }
                    return runCatching {
                        startActivityForResult(intent, FILE_CHOOSER_REQUEST)
                        true
                    }.getOrElse {
                        pendingFiles?.onReceiveValue(null)
                        pendingFiles = null
                        Toast.makeText(
                            this@AdvancedSurveyActivity, "Nessun gestore file disponibile",
                            Toast.LENGTH_SHORT
                        ).show()
                        false
                    }
                }
            }
        }
        // Origin-scoped WebMessage instead of addJavascriptInterface:
        // untrusted pages and iframe origins cannot call native export methods.
        if (WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER)) {
            WebViewCompat.addWebMessageListener(
                web, "GE360Native", setOf("https://appassets.androidplatform.net"),
                object : WebViewCompat.WebMessageListener {
                    override fun onPostMessage(
                        view: WebView,
                        message: WebMessageCompat,
                        sourceOrigin: Uri,
                        isMainFrame: Boolean,
                        replyProxy: JavaScriptReplyProxy
                    ) {
                        if (isMainFrame && sourceOrigin.scheme == "https" &&
                            sourceOrigin.host == ASSET_HOST &&
                            view.url.orEmpty().startsWith(START_URL)
                        ) receiveExportMessage(message.data)
                    }
                }
            )
        }
        browser = web
        root.addView(
            web,
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f)
        )
        setContentView(root)
        if (savedInstanceState == null || web.restoreState(savedInstanceState) == null) {
            web.loadUrl(START_URL)
        }
    }

    /** A user-driven SAF document creation; data stays in local process memory. */
    private fun receiveExportMessage(raw: String?) {
        if (raw == null || raw.length > MAX_MESSAGE_CHARS || pendingExport != null) return
        runCatching {
            val json = JSONObject(raw)
            require(json.optString("type") == "save")
            val filename = json.optString("filename", "GE360-Rilievo.json")
                .replace(Regex("[^A-Za-z0-9À-ÿ._ -]"), "_")
                .take(110).trim().ifBlank { "GE360-Rilievo.json" }
            val mime = json.optString("mime").takeIf { it in MIME_TYPES }
                ?: "application/octet-stream"
            val base64 = json.optString("base64")
            require(base64.isNotEmpty() && base64.length < MAX_MESSAGE_CHARS)
            val bytes = Base64.decode(base64, Base64.DEFAULT)
            require(bytes.isNotEmpty() && bytes.size <= MAX_EXPORT_BYTES)
            pendingExport = PendingExport(filename, mime, bytes)
            startActivityForResult(
                Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
                    addCategory(Intent.CATEGORY_OPENABLE)
                    type = mime
                    putExtra(Intent.EXTRA_TITLE, filename)
                },
                FILE_EXPORT_REQUEST
            )
        }.onFailure {
            pendingExport = null
            Toast.makeText(
                this, "Esportazione non disponibile: controlla dimensione e formato",
                Toast.LENGTH_LONG
            ).show()
        }
    }

    @Deprecated("Activity file chooser / export compatibility")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        when (requestCode) {
            FILE_CHOOSER_REQUEST -> {
                val result = WebChromeClient.FileChooserParams.parseResult(resultCode, data)
                pendingFiles?.onReceiveValue(if (resultCode == Activity.RESULT_OK) result else null)
                pendingFiles = null
            }
            FILE_EXPORT_REQUEST -> {
                val export = pendingExport
                pendingExport = null
                if (resultCode == Activity.RESULT_OK && export != null && data?.data != null) {
                    runCatching {
                        contentResolver.openOutputStream(data.data!!, "w")?.use { output ->
                            output.write(export.bytes)
                            output.flush()
                        } ?: error("Destinazione non scrivibile")
                    }.onSuccess {
                        Toast.makeText(this, "Documento salvato: ${export.name}", Toast.LENGTH_LONG).show()
                    }.onFailure {
                        Toast.makeText(this, "Salvataggio fallito: prova un'altra cartella", Toast.LENGTH_LONG).show()
                    }
                }
            }
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        browser?.saveState(outState)
        super.onSaveInstanceState(outState)
    }

    @Deprecated("Confirmation before leaving a survey")
    override fun onBackPressed() = confirmExit()

    private fun confirmExit() {
        AlertDialog.Builder(this)
            .setTitle("Chiudere il rilievo avanzato?")
            .setMessage(
                "Salva prima il rilievo nell'archivio web. I rilievi avanzati " +
                    "non vengono ancora inclusi nel backup ZIP dei rilievi nativi GE360: " +
                    "esporta anche il JSON per conservarne una copia."
            )
            .setPositiveButton("Chiudi") { _, _ -> finish() }
            .setNegativeButton("Continua", null)
            .show()
    }

    override fun onDestroy() {
        pendingFiles?.onReceiveValue(null)
        pendingFiles = null
        pendingExport = null
        browser?.let { web ->
            (web.parent as? ViewGroup)?.removeView(web)
            web.stopLoading()
            web.destroy()
        }
        browser = null
        super.onDestroy()
    }

    companion object {
        private const val ASSET_HOST = "appassets.androidplatform.net"
        private const val ASSET_ROOT = "/assets/rilievi/"
        private const val START_URL =
            "https://appassets.androidplatform.net/assets/rilievi/index.html"
        private const val FILE_CHOOSER_REQUEST = 2801
        private const val FILE_EXPORT_REQUEST = 2802
        private const val MAX_EXPORT_BYTES = 10 * 1024 * 1024
        private const val MAX_MESSAGE_CHARS = 15 * 1024 * 1024
        private val MIME_TYPES = setOf(
            "application/json", "application/pdf", "image/png", "image/jpeg",
            "image/webp", "image/svg+xml", "application/dxf", "image/vnd.dxf",
            "application/octet-stream"
        )
    }
}
