package it.edilmilan.ge360.jarvis

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Intent
import android.graphics.Typeface
import android.os.Bundle
import android.speech.RecognizerIntent
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
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import it.edilmilan.ge360.cantieri.JobsiteStore
import it.edilmilan.ge360.clienti.ClientStore
import it.edilmilan.ge360.core.bridge.BridgeService
import it.edilmilan.ge360.core.bridge.BridgeServiceConfig
import it.edilmilan.ge360.core.bridge.JsonHttpClient
import it.edilmilan.ge360.core.bridge.SecureBridgeSettings
import it.edilmilan.ge360.preventivi.EstimateStore
import it.edilmilan.ge360.spese.FinanceScope
import it.edilmilan.ge360.spese.FinanceStore
import it.edilmilan.ge360.spese.FinanceType
import org.json.JSONObject
import java.text.NumberFormat
import java.time.LocalDate
import java.util.Locale
import java.util.UUID
import kotlin.concurrent.thread

class JarvisActivity : AppCompatActivity() {
    private lateinit var settings: SecureBridgeSettings
    private val http = JsonHttpClient()
    private lateinit var url: EditText
    private lateinit var token: EditText
    private lateinit var allowHttp: CheckBox
    private lateinit var includeContext: CheckBox
    private lateinit var prompt: EditText
    private lateinit var status: TextView
    private lateinit var result: TextView
    private lateinit var execute: MaterialButton
    private lateinit var correctTraining: MaterialButton
    private var lastUserText: String = ""
    private var lastAssistantText: String = ""
    private var lastModelName: String = ""
    private var lastResponse: JSONObject? = null
    private val prefs by lazy { getSharedPreferences("ge360_jarvis_ui", MODE_PRIVATE) }

    private val voiceTrainerLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { outcome ->
        if (outcome.resultCode != Activity.RESULT_OK) return@registerForActivityResult
        val transcript = outcome.data
            ?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)
            ?.firstOrNull()
            .orEmpty()
            .trim()
        if (transcript.isBlank()) {
            toast("Non ho ricevuto una trascrizione vocale")
        } else {
            voiceTrainer().handleTranscript(transcript, currentConversation())
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        settings = SecureBridgeSettings(this)
        setContentView(buildScreen())
        loadConfig()
    }

    private fun buildScreen(): View {
        val scroll = ScrollView(this)
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(14), dp(12), dp(14), dp(24)) }
        scroll.addView(root)
        root.addView(LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            addView(MaterialButton(this@JarvisActivity).apply { text = "←"; setOnClickListener { finish() } }, LinearLayout.LayoutParams(dp(54), dp(48)))
            addView(LinearLayout(this@JarvisActivity).apply {
                orientation = LinearLayout.VERTICAL
                addView(TextView(this@JarvisActivity).apply { text = "Jarvis / IA"; textSize = 24f; setTypeface(typeface, Typeface.BOLD) })
                addView(TextView(this@JarvisActivity).apply { text = "Analizza → proponi → conferma → esegui"; textSize = 13f })
            }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        })

        root.addView(card().apply {
            addView(LinearLayout(this@JarvisActivity).apply {
                orientation = LinearLayout.VERTICAL; setPadding(dp(14), dp(12), dp(14), dp(12))
                addView(title("Connessione Jarvis"))
                url = field("URL backend, es. https://jarvis.miodominio.it")
                token = field("Token Bridge opzionale").apply { inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD }
                allowHttp = CheckBox(this@JarvisActivity).apply { text = "Consenti HTTP locale non cifrato" }
                addView(url, lp52()); addView(token, lp52()); addView(allowHttp)
                addView(LinearLayout(this@JarvisActivity).apply {
                    orientation = LinearLayout.HORIZONTAL
                    addView(button("Salva") { saveConfig() }, LinearLayout.LayoutParams(0, dp(50), 1f).apply { marginEnd = dp(4) })
                    addView(button("Stato sistema") { health() }, LinearLayout.LayoutParams(0, dp(50), 1f).apply { marginStart = dp(4) })
                })
                addView(button("Dispositivo Trainer") {
                    TrainerPairingDialog(this@JarvisActivity, settings, http).show()
                }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(50)).apply { topMargin = dp(7) })
                addView(TextView(this@JarvisActivity).apply {
                    text = "Il token, se usato, resta cifrato nel Keystore del telefono e non entra nei backup. La credenziale Trainer usa un archivio separato."
                    textSize = 11f; setPadding(0, dp(8), 0, 0)
                })
            })
        })

        status = TextView(this).apply { textSize = 13f; setPadding(dp(2), dp(8), dp(2), dp(6)) }
        root.addView(status)
        includeContext = CheckBox(this).apply {
            text = "Includi contesto GE360 sintetico (solo aggregati, nessun nome cliente)"
            isChecked = false
        }
        root.addView(includeContext)
        prompt = EditText(this).apply {
            hint = "Scrivi cosa vuoi chiedere a Jarvis…"
            minLines = 4; gravity = Gravity.TOP; inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
        }
        root.addView(prompt, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(130)))
        root.addView(LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            addView(button("Analizza senza eseguire") { analyze() }, LinearLayout.LayoutParams(0, dp(54), 1f).apply { marginEnd = dp(4) })
            addView(button("Nuova sessione") { newSession() }, LinearLayout.LayoutParams(0, dp(54), 1f).apply { marginStart = dp(4) })
        })
        execute = button("Esegui azione proposta") { confirmExecute() }.apply { isEnabled = false }
        root.addView(execute, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(54)).apply { topMargin = dp(8) })
        root.addView(card().apply {
            result = TextView(this@JarvisActivity).apply {
                text = "Jarvis non ha ancora ricevuto richieste."; textSize = 14f; setPadding(dp(14), dp(12), dp(14), dp(12)); setTextIsSelectable(true)
            }
            addView(result)
        })

        root.addView(card().apply {
            addView(LinearLayout(this@JarvisActivity).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(14), dp(12), dp(14), dp(12))
                addView(title("Addestra Jarvis"))
                addView(TextView(this@JarvisActivity).apply {
                    text = "Insegna regole nuove, correggi una risposta reale e approva solo gli esempi che vuoi rendere verificati. Nulla viene appreso automaticamente."
                    textSize = 12f
                    setPadding(0, 0, 0, dp(7))
                })
                addView(LinearLayout(this@JarvisActivity).apply {
                    orientation = LinearLayout.HORIZONTAL
                    addView(button("Insegna") {
                        learning().showTeach()
                    }, LinearLayout.LayoutParams(0, dp(50), 1f).apply { marginEnd = dp(4) })
                    correctTraining = button("Correggi") {
                        learning().showCorrection(currentConversation())
                    }.apply { isEnabled = false }
                    addView(correctTraining, LinearLayout.LayoutParams(0, dp(50), 1f).apply { marginStart = dp(4) })
                })
                addView(button("🎤 Insegna / correggi a voce") {
                    startVoiceTrainer()
                }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(52)).apply { topMargin = dp(7) })
                addView(button("Approva esempi in coda") {
                    learning().showInbox()
                }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(50)).apply { topMargin = dp(7) })
                addView(LinearLayout(this@JarvisActivity).apply {
                    orientation = LinearLayout.HORIZONTAL
                    addView(button("👍 Risposta buona") {
                        learning().rate(currentConversation(), positive = true)
                    }, LinearLayout.LayoutParams(0, dp(48), 1f).apply { marginEnd = dp(4); topMargin = dp(7) })
                    addView(button("👎 Da migliorare") {
                        learning().rate(currentConversation(), positive = false)
                    }, LinearLayout.LayoutParams(0, dp(48), 1f).apply { marginStart = dp(4); topMargin = dp(7) })
                })
                addView(TextView(this@JarvisActivity).apply {
                    text = "Voice Trainer: la trascrizione viene prima interpretata dal Training Bridge e poi mostrata per conferma. Solo dopo scegli l'ambito; il record nasce PENDING e richiede ancora la tua approvazione."
                    textSize = 11f
                    setPadding(0, dp(8), 0, 0)
                })
            })
        })

        root.addView(TextView(this).apply {
            text = "L'app non esegue comandi in background: ogni esecuzione parte da una conferma esplicita. Per impostazione predefinita Jarvis riceve dryRun=true."
            textSize = 11f; setPadding(dp(2), dp(12), dp(2), 0)
        })
        return scroll
    }

    private fun startVoiceTrainer() {
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, "it-IT")
            putExtra(RecognizerIntent.EXTRA_PROMPT, "Parla a Jarvis Trainer")
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 3)
        }
        try {
            voiceTrainerLauncher.launch(intent)
        } catch (_: ActivityNotFoundException) {
            AlertDialog.Builder(this)
                .setTitle("Riconoscimento vocale non disponibile")
                .setMessage("Su questo telefono non è installato un servizio di riconoscimento vocale compatibile. Il Trainer testuale continua a funzionare normalmente.")
                .setPositiveButton("OK", null)
                .show()
        }
    }

    private fun loadConfig() {
        val c = settings.get(BridgeService.JARVIS)
        url.setText(c.baseUrl); token.setText(c.token); allowHttp.isChecked = c.allowInsecureHttp
        status.text = if (c.configured) "Jarvis configurato · sessione ${sessionId().take(8)}" else "Backend Jarvis non configurato"
    }

    private fun saveConfig(): BridgeServiceConfig? = runCatching {
        settings.save(BridgeService.JARVIS, url.text.toString(), token.text.toString(), allowHttp.isChecked)
        settings.get(BridgeService.JARVIS)
    }.onSuccess { toast("Configurazione Jarvis salvata") }.onFailure { toast(it.message ?: "Configurazione non valida") }.getOrNull()

    private fun config() = settings.get(BridgeService.JARVIS)

    private fun health() {
        val c = saveConfig() ?: return
        if (!c.configured) return toast("Configura prima Jarvis")
        status.text = "Verifica Jarvis…"
        thread(name = "ge360-jarvis-health") {
            val response = runCatching {
                val health = http.request(c, "/health").jsonObject()
                val capabilities = runCatching { http.request(c, "/api/v1/capabilities").jsonObject() }.getOrNull()
                health to capabilities
            }
            runOnUiThread {
                response.onSuccess { (h, caps) ->
                    status.text = "Jarvis ${h.optString("status", "?")} · PostgreSQL ${h.optString("postgres", "?")} · Redis ${h.optString("redis", "?")}"
                    val details = buildString {
                        appendLine("Servizio: ${h.optString("service", "GE360 Jarvis")}")
                        appendLine("Versione: ${h.optString("version", "?")}")
                        appendLine("PostgreSQL: ${h.optString("postgres", "?")}")
                        appendLine("Redis: ${h.optString("redis", "?")}")
                        caps?.let {
                            appendLine(); appendLine("Protocollo: ${it.optString("protocolVersion", "?")}")
                            appendLine("API: ${it.optString("apiVersion", "?")}")
                            val features = it.optJSONArray("features")
                            if (features != null) appendLine("Funzioni: ${join(features)}")
                        }
                    }
                    AlertDialog.Builder(this).setTitle("Stato Jarvis").setMessage(details.trim()).setPositiveButton("OK", null).show()
                }.onFailure { status.text = "Jarvis non raggiungibile: ${it.message}" }
            }
        }
    }

    private fun analyze() {
        val text = prompt.text.toString().trim()
        if (text.isBlank()) return toast("Scrivi prima una richiesta")
        val c = saveConfig() ?: return
        if (!c.configured) return toast("Configura prima Jarvis")
        lastUserText = text
        lastAssistantText = ""
        lastModelName = ""
        correctTraining.isEnabled = false
        execute.isEnabled = false
        status.text = "Jarvis sta analizzando senza eseguire…"
        send(c, text, dryRun = true)
    }

    private fun send(c: BridgeServiceConfig, userText: String, dryRun: Boolean) {
        val outgoing = if (includeContext.isChecked) "$userText\n\n[CONTESTO GE360 SINTETICO]\n${aggregateContext()}" else userText
        val body = JSONObject()
            .put("text", outgoing)
            .put("sessionId", sessionId())
            .put("dryRun", dryRun)
        thread(name = if (dryRun) "ge360-jarvis-dryrun" else "ge360-jarvis-execute") {
            val response = runCatching { http.request(c, "/api/jarvis/text", "POST", body).jsonObject() }
            runOnUiThread {
                response.onSuccess { root ->
                    lastResponse = root
                    lastAssistantText = root.optString("reply", root.optString("message", "")).trim()
                    lastModelName = root.optString("modelName", root.optString("model", "")).trim()
                    result.text = render(root, dryRun)
                    val proposed = root.optJSONObject("proposedAction") != null
                    val needsApproval = root.optBoolean("needsApproval", false)
                    execute.isEnabled = dryRun && (proposed || needsApproval)
                    correctTraining.isEnabled = lastUserText.isNotBlank() && lastAssistantText.isNotBlank()
                    status.text = if (dryRun) "Analisi completata · nessuna azione eseguita" else "Richiesta esecutiva inviata · stato ${root.optString("actionStatus", "?")}"
                }.onFailure {
                    status.text = "Errore Jarvis: ${it.message}"
                    result.text = status.text
                }
            }
        }
    }

    private fun render(root: JSONObject, dryRun: Boolean): String = buildString {
        appendLine(if (dryRun) "MODALITÀ ANALISI · dryRun=true" else "MODALITÀ ESECUZIONE CONFERMATA")
        appendLine("Intent: ${root.optString("intent", "n/d")}")
        if (root.has("confidence")) appendLine("Confidenza: ${String.format(Locale.ITALY, "%.0f%%", root.optDouble("confidence", 0.0) * 100)}")
        appendLine("Richiede approvazione: ${if (root.optBoolean("needsApproval", false)) "sì" else "no"}")
        appendLine("Stato azione: ${root.optString("actionStatus", "n/d")}")
        root.optJSONObject("proposedAction")?.let { appendLine("Azione proposta: ${it.toString(2)}") }
        appendLine(); append(root.optString("reply", root.optString("message", "Nessuna risposta testuale")))
    }

    private fun confirmExecute() {
        val response = lastResponse ?: return
        if (lastUserText.isBlank()) return
        val proposed = response.optJSONObject("proposedAction")
        val detail = proposed?.toString(2) ?: response.optString("intent", "azione Jarvis")
        AlertDialog.Builder(this)
            .setTitle("Conferma esecuzione Jarvis")
            .setMessage("Jarvis ha proposto:\n\n$detail\n\nVuoi inviare la stessa richiesta con dryRun=false?")
            .setPositiveButton("Esegui") { _, _ ->
                execute.isEnabled = false
                status.text = "Invio comando confermato…"
                send(config(), lastUserText, dryRun = false)
            }
            .setNegativeButton("Annulla", null).show()
    }

    private fun learning() = TrainerLearningDialog(this, settings, http, ::sessionId)
    private fun voiceTrainer() = VoiceTrainerFlow(this, settings, http, ::sessionId)

    private fun currentConversation() = TrainerConversation(
        userText = lastUserText,
        assistantText = lastAssistantText,
        modelName = lastModelName
    )

    private fun aggregateContext(): String {
        val clients = ClientStore(this)
        val jobs = JobsiteStore(this)
        val finances = FinanceStore(this)
        val estimates = EstimateStore(this)
        return try {
            val year = LocalDate.now().year.toString()
            val business = finances.all().filter { it.scope == FinanceScope.BUSINESS && it.date.startsWith(year) }
            val income = business.filter { it.type == FinanceType.INCOME }.sumOf { it.amount }
            val expenses = business.filter { it.type == FinanceType.EXPENSE }.sumOf { it.amount }
            val jobRows = jobs.list()
            buildString {
                appendLine("Anno: $year")
                appendLine("Clienti totali: ${clients.search().size}")
                appendLine("Cantieri totali: ${jobRows.size}")
                appendLine("Cantieri in corso: ${jobRows.count { it.status == JobsiteStore.STATUS_ACTIVE }}")
                appendLine("Preventivi totali: ${estimates.list().size}")
                appendLine("Entrate azienda registrate anno: ${money(income)}")
                appendLine("Uscite azienda registrate anno: ${money(expenses)}")
                appendLine("Saldo azienda registrato anno: ${money(income - expenses)}")
                append("Nessun nome, telefono, email, indirizzo o testo documento incluso automaticamente.")
            }
        } finally {
            clients.close(); jobs.close(); finances.close(); estimates.close()
        }
    }

    private fun sessionId(): String {
        var value = prefs.getString("session_id", "").orEmpty()
        if (value.isBlank()) {
            value = UUID.randomUUID().toString()
            prefs.edit().putString("session_id", value).apply()
        }
        return value
    }

    private fun newSession() {
        prefs.edit().putString("session_id", UUID.randomUUID().toString()).apply()
        lastUserText = ""; lastAssistantText = ""; lastModelName = ""; lastResponse = null
        execute.isEnabled = false; correctTraining.isEnabled = false
        result.text = "Nuova sessione Jarvis creata."; status.text = "Sessione ${sessionId().take(8)}"
    }

    private fun join(array: org.json.JSONArray): String = buildList {
        for (i in 0 until array.length()) add(array.optString(i))
    }.joinToString(", ")
    private fun field(hintText: String) = EditText(this).apply { hint = hintText; isSingleLine = true }
    private fun title(text: String) = TextView(this).apply { this.text = text; textSize = 17f; setTypeface(typeface, Typeface.BOLD); setPadding(0, 0, 0, dp(6)) }
    private fun button(text: String, action: () -> Unit) = MaterialButton(this).apply { this.text = text; setOnClickListener { action() } }
    private fun card() = MaterialCardView(this).apply { radius = dp(14).toFloat(); cardElevation = dp(1).toFloat(); layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(9); bottomMargin = dp(7) } }
    private fun lp52() = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(52))
    private fun money(v: Double) = NumberFormat.getCurrencyInstance(Locale.ITALY).format(v)
    private fun toast(text: String) = Toast.makeText(this, text, Toast.LENGTH_LONG).show()
    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
}
