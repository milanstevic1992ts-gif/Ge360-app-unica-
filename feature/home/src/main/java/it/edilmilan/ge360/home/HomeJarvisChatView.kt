package it.edilmilan.ge360.home

import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.text.InputType
import android.util.AttributeSet
import android.view.Gravity
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import it.edilmilan.ge360.cantieri.JobsiteRecord
import it.edilmilan.ge360.cantieri.JobsiteStore
import it.edilmilan.ge360.centrocantiere.JobsiteWorkflowStore
import it.edilmilan.ge360.centrocantiere.WorkflowSources
import it.edilmilan.ge360.centrocantiere.WorkflowTask
import it.edilmilan.ge360.core.bridge.BridgeService
import it.edilmilan.ge360.core.bridge.JsonHttpClient
import it.edilmilan.ge360.core.bridge.SecureBridgeSettings
import it.edilmilan.ge360.core.common.ui.Ge360Brand
import it.edilmilan.ge360.core.common.ui.applyGe360Card
import it.edilmilan.ge360.core.common.ui.brandRounded
import it.edilmilan.ge360.jarvis.JarvisActivity
import org.json.JSONArray
import org.json.JSONObject
import java.time.LocalDate
import java.util.Locale
import java.util.UUID
import kotlin.concurrent.thread

/**
 * Chat Jarvis compatta per la Home.
 *
 * La conversazione viene elaborata dal GE360 Backend. Quando il backend restituisce
 * un'azione `ge360.reminder.create`, il telefono la materializza nello stesso
 * JobsiteWorkflowStore usato dal blocco Promemoria della Home.
 */
class HomeJarvisChatView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : LinearLayout(context, attrs, defStyleAttr) {

    private data class ChatLine(val role: String, val text: String, val timestamp: Long)
    private data class ReminderSaveResult(val ok: Boolean, val detail: String)

    private val settings = SecureBridgeSettings(context.applicationContext)
    private val http = JsonHttpClient()
    private val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private val jarvisPrefs = context.applicationContext.getSharedPreferences(JARVIS_PREFS, Context.MODE_PRIVATE)

    private lateinit var messages: LinearLayout
    private lateinit var status: TextView
    private lateinit var input: EditText
    private lateinit var send: MaterialButton

    init {
        orientation = VERTICAL
        buildUi()
        renderHistory()
        updateConnectionLabel()
    }

    private fun buildUi() {
        addView(MaterialCardView(context).apply {
            applyGe360Card(fill = Ge360Brand.ForestSoft, stroke = Ge360Brand.Forest, radiusDp = 22)
            strokeWidth = dp(2)
            addView(LinearLayout(context).apply {
                orientation = VERTICAL
                setPadding(dp(15), dp(14), dp(15), dp(14))

                addView(LinearLayout(context).apply {
                    orientation = HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    addView(LinearLayout(context).apply {
                        orientation = VERTICAL
                        addView(TextView(context).apply {
                            text = "JARVIS CHAT"
                            textSize = 12f
                            letterSpacing = 0.08f
                            setTypeface(typeface, Typeface.BOLD)
                            setTextColor(Ge360Brand.Forest)
                        })
                        addView(TextView(context).apply {
                            text = "Scrivi come parli. Jarvis capisce, risponde e prepara il lavoro."
                            textSize = 15.5f
                            setTypeface(typeface, Typeface.BOLD)
                            setTextColor(Ge360Brand.ForestDark)
                            setPadding(0, dp(2), 0, 0)
                        })
                    }, LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
                    addView(MaterialButton(context).apply {
                        text = "APRI"
                        textSize = 9.5f
                        setOnClickListener {
                            context.startActivity(Intent(context, JarvisActivity::class.java))
                        }
                    }, LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(42)))
                })

                status = TextView(context).apply {
                    textSize = 10.5f
                    setTextColor(Ge360Brand.TextSecondary)
                    setPadding(0, dp(6), 0, dp(4))
                }
                addView(status)

                messages = LinearLayout(context).apply {
                    orientation = VERTICAL
                }
                addView(messages, LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

                addView(LinearLayout(context).apply {
                    orientation = HORIZONTAL
                    gravity = Gravity.BOTTOM
                    setPadding(0, dp(10), 0, 0)

                    input = EditText(context).apply {
                        hint = "Scrivi a Jarvis…"
                        minLines = 1
                        maxLines = 4
                        textSize = 14f
                        inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
                        imeOptions = EditorInfo.IME_ACTION_SEND
                        background = context.brandRounded(
                            fill = Color.WHITE,
                            stroke = Ge360Brand.BorderStrong,
                            radiusDp = 16,
                            strokeDp = 1
                        )
                        setPadding(dp(12), dp(10), dp(12), dp(10))
                        setOnEditorActionListener { _, actionId, _ ->
                            if (actionId == EditorInfo.IME_ACTION_SEND) {
                                sendMessage()
                                true
                            } else false
                        }
                    }
                    addView(input, LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                        marginEnd = dp(7)
                    })

                    send = MaterialButton(context).apply {
                        text = "INVIA"
                        textSize = 10.5f
                        minWidth = dp(72)
                        setOnClickListener { sendMessage() }
                    }
                    addView(send, LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(50)))
                })
            })
        }, LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
    }

    private fun updateConnectionLabel() {
        val config = settings.get(BridgeService.JARVIS)
        status.text = if (config.configured) {
            "● Backend configurato · stessa sessione della schermata Jarvis"
        } else {
            "○ Backend non configurato · tocca APRI per configurarlo"
        }
    }

    private fun sendMessage() {
        val text = input.text.toString().trim()
        if (text.isBlank()) return
        val config = settings.get(BridgeService.JARVIS)
        if (!config.configured) {
            appendLine("assistant", "Il backend Jarvis non è configurato. Tocca APRI e inserisci l'indirizzo del server.")
            return
        }

        input.text.clear()
        appendLine("user", text)
        setBusy(true, "Jarvis sta pensando…")

        val body = JSONObject()
            .put("text", text)
            .put("sessionId", sessionId())
            .put("dryRun", true)
            .put("clientDate", LocalDate.now().toString())

        thread(name = "ge360-home-jarvis") {
            val response = runCatching {
                http.request(config, "/api/jarvis/text", "POST", body, timeoutMs = 45_000).jsonObject()
            }
            post {
                response.onSuccess { root ->
                    val baseReply = root.optString("reply", "Jarvis ha risposto senza testo.")
                    val action = root.optJSONObject("proposedAction")
                    val reminder = if (action?.optString("type") == REMINDER_ACTION) {
                        saveReminder(action)
                    } else null

                    val reply = buildString {
                        append(baseReply)
                        reminder?.let { saved ->
                            append("\n\n")
                            append(if (saved.ok) "✓ " else "⚠ ")
                            append(saved.detail)
                        }
                    }
                    appendLine("assistant", reply)
                    setBusy(false, "● Jarvis online · ${root.optString("intent", "chat")}")

                    if (reminder?.ok == true) {
                        findTodayDashboard()?.refresh()
                    }
                }.onFailure { error ->
                    appendLine(
                        "assistant",
                        "Non riesco a raggiungere Jarvis adesso. Controlla che il backend GE360 sia acceso e raggiungibile.\n${error.message.orEmpty()}"
                    )
                    setBusy(false, "○ Jarvis non raggiungibile")
                }
            }
        }
    }

    private fun saveReminder(action: JSONObject): ReminderSaveResult {
        val title = action.optString("title").trim()
        if (title.isBlank()) return ReminderSaveResult(false, "Jarvis non ha restituito un titolo valido: promemoria non salvato.")

        val due = action.optString("dueDate").trim().takeIf { it.isNotBlank() }?.let {
            runCatching { LocalDate.parse(it) }.getOrNull()
        }
        val hint = action.optString("jobsiteHint").trim()
        val sourceText = action.optString("sourceText").trim()

        val jobs = JobsiteStore(context.applicationContext)
        val workflow = JobsiteWorkflowStore(context.applicationContext)
        return try {
            val operational = jobs.list().filter { it.status != JobsiteStore.STATUS_ARCHIVED }
            val job = resolveJobsite(hint, operational)
            val scope = job?.ge360Id ?: GENERAL_REMINDER_SCOPE
            val detail = buildString {
                append("Creato da Jarvis")
                if (hint.isNotBlank() && job == null) append(" · riferimento cantiere non associato: $hint")
                if (sourceText.isNotBlank()) append("\nOrigine: $sourceText")
            }
            workflow.saveTask(
                WorkflowTask(
                    jobsiteId = scope,
                    workType = job?.workType ?: GENERAL_WORK_TYPE,
                    templateId = reminderToken(due),
                    phase = if (job == null) "Promemoria generale" else "Promemoria",
                    sortOrder = REMINDER_SORT_BASE + (System.currentTimeMillis() % 900_000L).toInt(),
                    title = title,
                    detail = detail,
                    source = WorkflowSources.MANUAL
                )
            )
            val whenLabel = due?.let { " per ${it.format(java.time.format.DateTimeFormatter.ofPattern("dd/MM"))}" }
                ?: " finché non clicchi Fatto"
            when {
                job != null -> ReminderSaveResult(true, "Salvato nei Promemoria · ${job.displayTitle()}$whenLabel.")
                hint.isNotBlank() -> ReminderSaveResult(true, "Salvato nei Promemoria come Generale$whenLabel; non ho associato automaticamente '$hint' a un cantiere per evitare errori.")
                else -> ReminderSaveResult(true, "Salvato nei Promemoria · Generale$whenLabel.")
            }
        } catch (error: Throwable) {
            ReminderSaveResult(false, "Non sono riuscito a salvare il promemoria: ${error.message ?: "errore"}")
        } finally {
            jobs.close()
            workflow.close()
        }
    }

    private fun resolveJobsite(hint: String, jobs: List<JobsiteRecord>): JobsiteRecord? {
        val needle = normalize(hint)
        if (needle.isBlank()) return null

        jobs.firstOrNull { normalize(it.code) == needle }?.let { return it }
        if (needle.length < 3) return null

        val direct = jobs.filter { job ->
            val values = listOf(job.title, job.address, job.code, job.clientName, job.displayTitle())
                .map(::normalize)
                .filter { it.isNotBlank() }
            values.any { value -> value == needle || value.contains(needle) || (needle.length >= 6 && needle.contains(value)) }
        }
        if (direct.size == 1) return direct.first()

        val tokens = needle.split(' ').filter { it.length >= 3 }.toSet()
        if (tokens.isEmpty()) return null
        val scored = jobs.map { job ->
            val haystack = normalize(listOf(job.code, job.title, job.address, job.clientName).joinToString(" "))
            job to tokens.count { haystack.contains(it) }
        }.sortedByDescending { it.second }
        val best = scored.firstOrNull() ?: return null
        val second = scored.getOrNull(1)?.second ?: 0
        return if (best.second >= 2 && best.second > second) best.first else null
    }

    private fun normalize(value: String): String = value
        .lowercase(Locale.ITALY)
        .replace(Regex("[^a-z0-9à-ÿ]+"), " ")
        .trim()
        .replace(Regex("\\s+"), " ")

    private fun reminderToken(due: LocalDate?): String =
        REMINDER_PREFIX + (due?.toString() ?: REMINDER_PERSISTENT)

    private fun setBusy(busy: Boolean, label: String) {
        send.isEnabled = !busy
        input.isEnabled = !busy
        status.text = label
    }

    private fun appendLine(role: String, text: String) {
        val history = readHistory().toMutableList()
        history += ChatLine(role, text.take(MAX_MESSAGE_CHARS), System.currentTimeMillis())
        writeHistory(history.takeLast(MAX_HISTORY))
        renderHistory()
    }

    private fun renderHistory() {
        messages.removeAllViews()
        val history = readHistory()
        if (history.isEmpty()) {
            messages.addView(bubble(ChatLine(
                "assistant",
                "Ciao. Scrivimi cose normali tipo “domani sopralluogo in via Mulino a Vento”, “dobbiamo comprare colla” oppure fammi una domanda sul lavoro.",
                System.currentTimeMillis()
            )))
            return
        }
        history.takeLast(VISIBLE_HISTORY).forEach { messages.addView(bubble(it)) }
    }

    private fun bubble(line: ChatLine): ViewGroup {
        val user = line.role == "user"
        return LinearLayout(context).apply {
            orientation = HORIZONTAL
            gravity = if (user) Gravity.END else Gravity.START
            setPadding(0, dp(4), 0, dp(4))
            addView(TextView(context).apply {
                text = line.text
                textSize = 13f
                setTextColor(if (user) Color.WHITE else Ge360Brand.ForestDark)
                setPadding(dp(12), dp(9), dp(12), dp(9))
                background = context.brandRounded(
                    fill = if (user) Ge360Brand.PrimaryDark else Color.WHITE,
                    stroke = if (user) Ge360Brand.PrimaryDark else Ge360Brand.Border,
                    radiusDp = 15,
                    strokeDp = 1
                )
            }, LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 0.88f))
        }
    }

    private fun readHistory(): List<ChatLine> = runCatching {
        val raw = prefs.getString(KEY_HISTORY, "[]").orEmpty()
        val array = JSONArray(raw.ifBlank { "[]" })
        buildList {
            for (index in 0 until array.length()) {
                val item = array.optJSONObject(index) ?: continue
                val role = item.optString("role")
                val text = item.optString("text")
                if (role in setOf("user", "assistant") && text.isNotBlank()) {
                    add(ChatLine(role, text, item.optLong("timestamp", 0L)))
                }
            }
        }
    }.getOrDefault(emptyList())

    private fun writeHistory(lines: List<ChatLine>) {
        val array = JSONArray()
        lines.forEach { line ->
            array.put(JSONObject()
                .put("role", line.role)
                .put("text", line.text)
                .put("timestamp", line.timestamp))
        }
        prefs.edit().putString(KEY_HISTORY, array.toString()).apply()
    }

    private fun sessionId(): String {
        var value = jarvisPrefs.getString("session_id", "").orEmpty()
        if (value.isBlank()) {
            value = UUID.randomUUID().toString()
            jarvisPrefs.edit().putString("session_id", value).apply()
        }
        return value
    }

    private fun findTodayDashboard(): TodayDashboardView? {
        var current: android.view.View? = this
        while (current != null) {
            if (current is TodayDashboardView) return current
            current = current.parent as? android.view.View
        }
        return rootView?.findViewById(R.id.todayDashboard)
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    companion object {
        const val VIEW_TAG = "ge360_home_jarvis_chat"
        private const val PREFS = "ge360_home_jarvis_chat"
        private const val JARVIS_PREFS = "ge360_jarvis_ui"
        private const val KEY_HISTORY = "history_v1"
        private const val MAX_HISTORY = 24
        private const val VISIBLE_HISTORY = 8
        private const val MAX_MESSAGE_CHARS = 4_000

        private const val REMINDER_ACTION = "ge360.reminder.create"
        private const val REMINDER_PREFIX = "dashboard-reminder:"
        private const val REMINDER_PERSISTENT = "persistent"
        private const val REMINDER_SORT_BASE = 1_000_000
        private const val GENERAL_REMINDER_SCOPE = "__ge360_home_general__"
        private const val GENERAL_WORK_TYPE = "Generale"
    }
}
