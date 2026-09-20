package it.edilmilan.ge360.marketing

import android.content.Intent
import android.graphics.Typeface
import android.net.Uri
import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import it.edilmilan.ge360.core.bridge.BridgeService
import it.edilmilan.ge360.core.bridge.BridgeServiceConfig
import it.edilmilan.ge360.core.bridge.JsonHttpClient
import it.edilmilan.ge360.core.bridge.SecureBridgeSettings
import org.json.JSONArray
import org.json.JSONObject
import java.text.NumberFormat
import java.time.LocalDate
import java.util.Locale
import kotlin.concurrent.thread

class MarketingActivity : AppCompatActivity() {
    private lateinit var settings: SecureBridgeSettings
    private val http = JsonHttpClient()
    private lateinit var url: EditText
    private lateinit var token: EditText
    private lateinit var allowHttp: CheckBox
    private lateinit var status: TextView
    private lateinit var dashboard: TextView
    private val cache by lazy { getSharedPreferences("ge360_marketing_cache", MODE_PRIVATE) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        settings = SecureBridgeSettings(this)
        setContentView(buildScreen())
        loadConfig()
        showCached()
    }

    private fun buildScreen(): View {
        val scroll = ScrollView(this)
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(14), dp(12), dp(14), dp(24)) }
        scroll.addView(root)
        root.addView(LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            addView(MaterialButton(this@MarketingActivity).apply { text = "←"; setOnClickListener { finish() } }, LinearLayout.LayoutParams(dp(54), dp(48)))
            addView(LinearLayout(this@MarketingActivity).apply {
                orientation = LinearLayout.VERTICAL
                addView(TextView(this@MarketingActivity).apply { text = "Marketing"; textSize = 24f; setTypeface(typeface, Typeface.BOLD) })
                addView(TextView(this@MarketingActivity).apply { text = "KPI · lead · SEO · tracking · approvazioni"; textSize = 13f })
            }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        })

        root.addView(card().apply {
            addView(LinearLayout(this@MarketingActivity).apply {
                orientation = LinearLayout.VERTICAL; setPadding(dp(14), dp(12), dp(14), dp(12))
                addView(title("Connessione GE360 Marketing"))
                url = field("URL server, es. https://marketing.miodominio.it")
                token = field("Token Bridge opzionale").apply { inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD }
                allowHttp = CheckBox(this@MarketingActivity).apply { text = "Consenti HTTP locale non cifrato" }
                addView(url, lp52()); addView(token, lp52()); addView(allowHttp)
                addView(LinearLayout(this@MarketingActivity).apply {
                    orientation = LinearLayout.HORIZONTAL
                    addView(button("Salva") { saveConfig() }, LinearLayout.LayoutParams(0, dp(50), 1f).apply { marginEnd = dp(4) })
                    addView(button("Test") { testHealth() }, LinearLayout.LayoutParams(0, dp(50), 1f).apply { marginStart = dp(4) })
                })
                addView(TextView(this@MarketingActivity).apply {
                    text = "Le credenziali Meta/Google/WordPress restano sul server. L'eventuale token Bridge è cifrato con Android Keystore."
                    textSize = 11f; setPadding(0, dp(8), 0, 0)
                })
            })
        })

        status = TextView(this).apply { textSize = 13f; setPadding(dp(2), dp(8), dp(2), dp(4)) }
        root.addView(status)
        root.addView(LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            addView(button("Aggiorna") { refreshDashboard() }, LinearLayout.LayoutParams(0, dp(52), 1f).apply { marginEnd = dp(4) })
            addView(button("Apri dashboard") { openWebDashboard() }, LinearLayout.LayoutParams(0, dp(52), 1f).apply { marginStart = dp(4) })
        })
        root.addView(LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            addView(button("Azioni proposte") { loadActions() }, LinearLayout.LayoutParams(0, dp(52), 1f).apply { marginEnd = dp(4) })
            addView(button("Consiglio IA") { aiRecommend() }, LinearLayout.LayoutParams(0, dp(52), 1f).apply { marginStart = dp(4) })
        })
        root.addView(card().apply {
            dashboard = TextView(this@MarketingActivity).apply { textSize = 14f; setPadding(dp(14), dp(12), dp(14), dp(12)); setTextIsSelectable(true) }
            addView(dashboard)
        })
        root.addView(TextView(this).apply {
            text = "GE360 non approva automaticamente budget, campagne o pubblicazioni. Le operazioni mutative richiedono un tocco e una conferma esplicita."
            textSize = 11f; setPadding(dp(2), dp(12), dp(2), 0)
        })
        return scroll
    }

    private fun loadConfig() {
        val c = settings.get(BridgeService.MARKETING)
        url.setText(c.baseUrl); token.setText(c.token); allowHttp.isChecked = c.allowInsecureHttp
        status.text = if (c.configured) "Server configurato · dati non ancora aggiornati" else "Server Marketing non configurato"
    }

    private fun saveConfig(): BridgeServiceConfig? = runCatching {
        settings.save(BridgeService.MARKETING, url.text.toString(), token.text.toString(), allowHttp.isChecked)
        settings.get(BridgeService.MARKETING)
    }.onSuccess { toast("Configurazione Marketing salvata") }.onFailure { toast(it.message ?: "Configurazione non valida") }.getOrNull()

    private fun config(): BridgeServiceConfig = settings.get(BridgeService.MARKETING)

    private fun testHealth() {
        val c = saveConfig() ?: return
        status.text = "Verifica servizio…"
        thread(name = "ge360-marketing-health") {
            val result = runCatching { http.request(c, "/health").jsonObject() }
            runOnUiThread {
                result.onSuccess { o ->
                    val connectors = o.optJSONArray("connectors")?.length() ?: 0
                    status.text = "Marketing raggiungibile · PostgreSQL ${o.optString("postgres", "?")} · connettori $connectors"
                }.onFailure { status.text = "Marketing non raggiungibile: ${it.message}" }
            }
        }
    }

    private fun refreshDashboard() {
        val c = saveConfig() ?: return
        if (!c.configured) return toast("Configura prima il server Marketing")
        val to = LocalDate.now(); val from = to.minusDays(30)
        status.text = "Aggiornamento dati reali…"
        thread(name = "ge360-marketing-dashboard") {
            val result = runCatching {
                http.request(c, "/dashboard/bootstrap?from=$from&to=$to&limit=100").jsonObject()
            }
            runOnUiThread {
                result.onSuccess { root ->
                    cache.edit().putString("bootstrap", root.toString()).putLong("at", System.currentTimeMillis()).apply()
                    dashboard.text = renderBootstrap(root)
                    status.text = "Dati Marketing aggiornati · ultimi 30 giorni"
                }.onFailure { e ->
                    status.text = "Aggiornamento fallito: ${e.message}"
                    showCached()
                }
            }
        }
    }

    private fun showCached() {
        val raw = cache.getString("bootstrap", "").orEmpty()
        if (raw.isBlank()) {
            if (::dashboard.isInitialized) dashboard.text = "Nessun dato Marketing reale in cache. Configura il server e premi Aggiorna."
            return
        }
        runCatching { JSONObject(raw) }.onSuccess {
            dashboard.text = renderBootstrap(it) + "\n\nCACHE LOCALE · ultima risposta server salvata"
        }
    }

    private fun renderBootstrap(root: JSONObject): String = buildString {
        val mode = root.optString("mode", "live").uppercase(Locale.ROOT)
        val summary = root.optJSONObject("summary")
        val totals = summary?.optJSONObject("totals")
        appendLine("Stato: $mode")
        appendLine("Periodo: ${root.optJSONObject("window")?.optString("from", "?")} → ${root.optJSONObject("window")?.optString("to", "?")}")
        appendLine()
        appendLine("KPI")
        appendLine("Spesa: ${money(totals?.optDouble("spend", 0.0) ?: 0.0)}")
        appendLine("Lead: ${totals?.optInt("leads", 0) ?: 0}")
        appendLine("Conversioni: ${totals?.optInt("conversions", 0) ?: 0}")
        appendLine("Ricavo attribuito: ${money(totals?.optDouble("revenue", 0.0) ?: 0.0)}")
        summary?.takeIf { it.has("cpl") }?.let { appendLine("CPL: ${money(it.optDouble("cpl", 0.0))}") }
        summary?.takeIf { it.has("roas") }?.let { appendLine("ROAS: ${fmt(it.optDouble("roas", 0.0))}x") }
        appendLine("Lead in inbox: ${root.optJSONArray("leads")?.length() ?: 0}")

        root.optJSONObject("seo")?.let { seo ->
            val rankings = seo.optJSONObject("rankings"); val audit = seo.optJSONObject("audit")
            appendLine(); appendLine("SEO")
            appendLine("Keyword monitorate: ${rankings?.optInt("tracked", 0) ?: 0} · Top 10: ${rankings?.optInt("top10", 0) ?: 0}")
            appendLine("Audit: ${if (audit?.isNull("score") != false) "n/d" else audit.optInt("score").toString() + "/100"} · criticità: ${audit?.optInt("critical", 0) ?: 0}")
        }
        root.optJSONObject("tracking")?.optJSONObject("totals")?.let { tr ->
            appendLine(); appendLine("TRACKING")
            appendLine("Click: ${tr.optInt("clicks", 0)} · lead: ${tr.optInt("leads", 0)} · clienti: ${tr.optInt("convertedLeads", 0)}")
            appendLine("Ricavo tracciato: ${money(tr.optDouble("revenue", 0.0))}")
        }
        root.optJSONObject("health")?.optJSONArray("connectors")?.let { connectors ->
            appendLine(); appendLine("INTEGRAZIONI")
            for (i in 0 until connectors.length()) {
                val o = connectors.optJSONObject(i) ?: continue
                appendLine("• ${o.optString("id")}: ${o.optString("health")}")
            }
        }
        val actions = root.optJSONArray("actions")?.length() ?: 0
        if (actions > 0) { appendLine(); appendLine("Azioni proposte da approvare: $actions") }
        val errors = root.optJSONArray("errors")
        if (errors != null && errors.length() > 0) {
            appendLine(); appendLine("ATTENZIONE")
            for (i in 0 until minOf(errors.length(), 5)) appendLine("• ${errors.optJSONObject(i)?.optString("area")}: ${errors.optJSONObject(i)?.optString("message")}")
        }
    }.trim()

    private fun loadActions() {
        val c = config(); if (!c.configured) return toast("Configura prima Marketing")
        status.text = "Carico azioni proposte…"
        thread {
            val result = runCatching { http.request(c, "/actions").jsonArray() }
            runOnUiThread { result.onSuccess { showActions(it) }.onFailure { toast("Errore azioni: ${it.message}") } }
        }
    }

    private fun showActions(rows: JSONArray) {
        if (rows.length() == 0) return toast("Nessuna azione proposta")
        val labels = Array(rows.length()) { i ->
            val o = rows.optJSONObject(i)
            "${o?.optString("channel", "") ?: ""} · ${o?.optString("description", o?.optString("kind", "Azione") ?: "Azione") ?: "Azione"}"
        }
        AlertDialog.Builder(this).setTitle("Azioni Marketing da approvare").setItems(labels) { _, which ->
            val action = rows.optJSONObject(which) ?: return@setItems
            confirmApprove(action)
        }.setNegativeButton("Chiudi", null).show()
    }

    private fun confirmApprove(action: JSONObject) {
        val id = action.optString("id"); if (id.isBlank()) return
        val description = action.optString("description", action.optString("kind", "Azione Marketing"))
        AlertDialog.Builder(this)
            .setTitle("Conferma approvazione")
            .setMessage("$description\n\nQuesta operazione può modificare una campagna, un contenuto o un'attività esterna. Confermi?")
            .setPositiveButton("Approva") { _, _ -> approveAction(id) }
            .setNegativeButton("Annulla", null).show()
    }

    private fun approveAction(id: String) {
        val c = config(); status.text = "Invio approvazione…"
        thread {
            val result = runCatching { http.request(c, "/actions/${Uri.encode(id)}/approve", "POST", JSONObject()).jsonObject() }
            runOnUiThread { result.onSuccess { toast("Azione approvata"); refreshDashboard() }.onFailure { toast("Approvazione fallita: ${it.message}") } }
        }
    }

    private fun aiRecommend() {
        val c = config(); if (!c.configured) return toast("Configura prima Marketing")
        val to = LocalDate.now(); val from = to.minusDays(30)
        status.text = "Analisi Marketing…"
        thread {
            val result = runCatching { http.request(c, "/ai/recommend", "POST", JSONObject().put("from", from.toString()).put("to", to.toString())).text }
            runOnUiThread {
                result.onSuccess { raw ->
                    val pretty = runCatching { JSONObject(raw).toString(2) }.getOrElse { raw }
                    AlertDialog.Builder(this).setTitle("Consiglio Marketing IA").setMessage(pretty.take(12000)).setPositiveButton("Chiudi", null).show()
                    status.text = "Analisi completata"
                }.onFailure { toast("Analisi fallita: ${it.message}") }
            }
        }
    }

    private fun openWebDashboard() {
        val c = config(); if (!c.configured) return toast("Configura prima Marketing")
        startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(c.baseUrl.trimEnd('/') + "/dashboard/")))
    }

    private fun field(hintText: String) = EditText(this).apply { hint = hintText; isSingleLine = true }
    private fun title(text: String) = TextView(this).apply { this.text = text; textSize = 17f; setTypeface(typeface, Typeface.BOLD); setPadding(0, 0, 0, dp(6)) }
    private fun button(text: String, action: () -> Unit) = MaterialButton(this).apply { this.text = text; setOnClickListener { action() } }
    private fun card() = MaterialCardView(this).apply { radius = dp(14).toFloat(); cardElevation = dp(1).toFloat(); layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(9); bottomMargin = dp(7) } }
    private fun lp52() = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(52))
    private fun money(v: Double) = NumberFormat.getCurrencyInstance(Locale.ITALY).format(v)
    private fun fmt(v: Double) = String.format(Locale.ITALY, "%.2f", v)
    private fun toast(text: String) = Toast.makeText(this, text, Toast.LENGTH_LONG).show()
    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
}
