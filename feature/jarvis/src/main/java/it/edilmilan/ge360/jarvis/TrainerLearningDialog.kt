package it.edilmilan.ge360.jarvis

import android.graphics.Typeface
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import it.edilmilan.ge360.core.bridge.BridgeService
import it.edilmilan.ge360.core.bridge.BridgeServiceConfig
import it.edilmilan.ge360.core.bridge.JsonHttpClient
import it.edilmilan.ge360.core.bridge.SecureBridgeSettings
import it.edilmilan.ge360.core.bridge.SecureTrainerDeviceSettings
import it.edilmilan.ge360.core.bridge.TrainerDeviceConfig
import it.edilmilan.ge360.core.bridge.TrainingBridgeClient
import org.json.JSONArray
import org.json.JSONObject
import kotlin.concurrent.thread

data class TrainerConversation(
    val userText: String,
    val assistantText: String,
    val modelName: String = ""
) {
    val available: Boolean get() = userText.isNotBlank() && assistantText.isNotBlank()
}

private data class ScopeControls(
    val types: List<TrainerScopeTypeOption>,
    val typeSpinner: Spinner,
    val targetLabel: TextView,
    val targetSpinner: Spinner,
    var targets: List<TrainerScopeOption> = emptyList()
)

/**
 * Console di apprendimento esplicito per Jarvis.
 *
 * Nessun contenuto viene catturato automaticamente: insegna, correggi, valuta e approva partono
 * sempre da un gesto dell'utente e usano esclusivamente la credenziale Trainer della Fase 3.
 * In Fase 6 ogni insegnamento puo' essere globale oppure limitato al contesto selezionato.
 */
class TrainerLearningDialog(
    private val activity: AppCompatActivity,
    private val bridgeSettings: SecureBridgeSettings,
    http: JsonHttpClient = JsonHttpClient(),
    private val sessionIdProvider: () -> String
) {
    private val trainerSettings = SecureTrainerDeviceSettings(activity)
    private val training = TrainingBridgeClient(http)
    private val scopeCatalog = TrainerScopeCatalog(activity)

    fun showTeach(seedInstruction: String = "") {
        val auth = authorizedContext() ?: return
        val root = formRoot()
        root.addView(label("Cosa deve imparare Jarvis"))
        val instruction = multiline("Scrivi una regola, una preferenza o una procedura da ricordare", 5).apply {
            setText(seedInstruction.trim().take(12_000))
        }
        root.addView(instruction, matchWrap())
        root.addView(label("Categoria"))
        val category = singleLine("general").apply { setText("general") }
        root.addView(category, matchWrap())
        val scopeControls = addScopeControls(root)
        root.addView(label("Nota opzionale"))
        val note = multiline("Perché questa regola è importante", 2)
        root.addView(note, matchWrap())
        root.addView(
            explainer(
                "L'insegnamento viene salvato come PENDING. Dopo l'approvazione Jarvis lo userà solo nell'ambito scelto; non viene promosso o riaddestrato automaticamente."
            )
        )

        val dialog = AlertDialog.Builder(activity)
            .setTitle("Insegna a Jarvis")
            .setView(wrapScroll(root))
            .setPositiveButton("SALVA INSEGNAMENTO", null)
            .setNegativeButton("ANNULLA", null)
            .create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val text = instruction.text.toString().trim()
                if (text.length < 3) {
                    instruction.error = "Scrivi almeno 3 caratteri"
                    return@setOnClickListener
                }
                val scope = selectedScope(scopeControls) ?: run {
                    toast("Seleziona il contesto in cui deve valere la regola")
                    return@setOnClickListener
                }
                setBusy(dialog, true)
                thread(name = "ge360-trainer-teach") {
                    val result = runCatching {
                        training.teach(
                            server = auth.first,
                            trainer = auth.second,
                            instruction = text,
                            category = category.text.toString(),
                            note = note.text.toString(),
                            sessionId = sessionIdProvider(),
                            scopeType = scope.type,
                            scopeId = scope.id,
                            scopeLabel = scope.label
                        )
                    }
                    activity.runOnUiThread {
                        result.onSuccess { entry ->
                            dialog.dismiss()
                            toast(
                                "Insegnamento ${shortId(entry)} salvato · ${scope.label} · in attesa di approvazione"
                            )
                        }.onFailure { error ->
                            setBusy(dialog, false)
                            showError("Impossibile salvare l'insegnamento", error)
                        }
                    }
                }
            }
        }
        dialog.show()
    }

    fun showCorrection(conversation: TrainerConversation) {
        if (!conversation.available) {
            toast("Prima chiedi qualcosa a Jarvis: serve una risposta da correggere")
            return
        }
        val auth = authorizedContext() ?: return
        val root = formRoot()
        root.addView(label("La tua richiesta"))
        root.addView(readOnly(conversation.userText))
        root.addView(label("Risposta di Jarvis"))
        root.addView(readOnly(conversation.assistantText))
        root.addView(label("Come avrebbe dovuto rispondere"))
        val correction = multiline("Scrivi la risposta corretta o la regola che Jarvis deve seguire", 5)
        root.addView(correction, matchWrap())
        root.addView(label("Categoria"))
        val category = singleLine("general").apply { setText("general") }
        root.addView(category, matchWrap())
        val scopeControls = addScopeControls(root)
        root.addView(label("Nota opzionale"))
        val note = multiline("Dettaglio utile per la revisione", 2)
        root.addView(note, matchWrap())
        root.addView(
            explainer(
                "La correzione resta PENDING fino alla tua approvazione e, se contestuale, non deve influenzare cantieri, lavorazioni, materiali o attrezzi diversi."
            )
        )

        val dialog = AlertDialog.Builder(activity)
            .setTitle("Correggi Jarvis")
            .setView(wrapScroll(root))
            .setPositiveButton("SALVA CORREZIONE", null)
            .setNegativeButton("ANNULLA", null)
            .create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val preferred = correction.text.toString().trim()
                if (preferred.length < 3) {
                    correction.error = "Scrivi la correzione"
                    return@setOnClickListener
                }
                val scope = selectedScope(scopeControls) ?: run {
                    toast("Seleziona il contesto in cui deve valere la correzione")
                    return@setOnClickListener
                }
                setBusy(dialog, true)
                thread(name = "ge360-trainer-correct") {
                    val result = runCatching {
                        training.correct(
                            server = auth.first,
                            trainer = auth.second,
                            userText = conversation.userText,
                            assistantText = conversation.assistantText,
                            correction = preferred,
                            category = category.text.toString(),
                            note = note.text.toString(),
                            sessionId = sessionIdProvider(),
                            modelName = conversation.modelName,
                            scopeType = scope.type,
                            scopeId = scope.id,
                            scopeLabel = scope.label
                        )
                    }
                    activity.runOnUiThread {
                        result.onSuccess { entry ->
                            dialog.dismiss()
                            toast("Correzione ${shortId(entry)} salvata · ${scope.label} · approvala dalla coda")
                        }.onFailure { error ->
                            setBusy(dialog, false)
                            showError("Impossibile salvare la correzione", error)
                        }
                    }
                }
            }
        }
        dialog.show()
    }

    fun rate(conversation: TrainerConversation, positive: Boolean) {
        if (!conversation.available) {
            toast("Non c'è ancora una risposta Jarvis da valutare")
            return
        }
        val auth = authorizedContext() ?: return
        showScopePicker("Dove vale questa valutazione?") { scope ->
            thread(name = "ge360-trainer-rate") {
                val result = runCatching {
                    training.rate(
                        server = auth.first,
                        trainer = auth.second,
                        positive = positive,
                        userText = conversation.userText,
                        assistantText = conversation.assistantText,
                        sessionId = sessionIdProvider(),
                        modelName = conversation.modelName,
                        scopeType = scope.type,
                        scopeId = scope.id,
                        scopeLabel = scope.label
                    )
                }
                activity.runOnUiThread {
                    result.onSuccess {
                        toast(
                            (if (positive) "Valutazione positiva" else "Valutazione negativa") +
                                " salvata · ${scope.label}"
                        )
                    }.onFailure { showError("Impossibile salvare la valutazione", it) }
                }
            }
        }
    }

    fun showInbox() {
        val auth = authorizedContext() ?: return
        toast("Carico la coda Trainer…")
        thread(name = "ge360-trainer-inbox") {
            val result = runCatching {
                training.inbox(auth.first, auth.second, status = "pending", limit = 100)
            }
            activity.runOnUiThread {
                result.onSuccess { array ->
                    val entries = array.toObjects()
                    if (entries.isEmpty()) {
                        toast("Nessun esempio in attesa di approvazione")
                    } else {
                        showReviewEntry(auth, entries, 0)
                    }
                }.onFailure { showError("Impossibile leggere la coda Trainer", it) }
            }
        }
    }

    private fun showReviewEntry(
        auth: Pair<BridgeServiceConfig, TrainerDeviceConfig>,
        entries: List<JSONObject>,
        index: Int
    ) {
        if (index >= entries.size) {
            toast("Coda Trainer completata")
            return
        }
        val entry = entries[index]
        val detail = TextView(activity).apply {
            text = renderEntry(entry, index + 1, entries.size)
            textSize = 13f
            setTextIsSelectable(true)
            setPadding(dp(18), dp(12), dp(18), dp(12))
        }
        val dialog = AlertDialog.Builder(activity)
            .setTitle("Approva esempio")
            .setView(ScrollView(activity).apply { addView(detail) })
            .setPositiveButton("APPROVA", null)
            .setNeutralButton("RIFIUTA", null)
            .setNegativeButton(if (index + 1 < entries.size) "SUCCESSIVO" else "CHIUDI", null)
            .create()

        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                reviewEntry(dialog, auth, entry, approve = true) {
                    if (index + 1 < entries.size) showReviewEntry(auth, entries, index + 1)
                }
            }
            dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener {
                askRejectReason { reason ->
                    reviewEntry(dialog, auth, entry, approve = false, note = reason) {
                        if (index + 1 < entries.size) showReviewEntry(auth, entries, index + 1)
                    }
                }
            }
            dialog.getButton(AlertDialog.BUTTON_NEGATIVE).setOnClickListener {
                dialog.dismiss()
                if (index + 1 < entries.size) showReviewEntry(auth, entries, index + 1)
            }
        }
        dialog.show()
    }

    private fun reviewEntry(
        dialog: AlertDialog,
        auth: Pair<BridgeServiceConfig, TrainerDeviceConfig>,
        entry: JSONObject,
        approve: Boolean,
        note: String = "",
        onDone: () -> Unit
    ) {
        val id = entry.optString("id").trim()
        if (id.isBlank()) {
            toast("Esempio senza ID: impossibile revisionare")
            return
        }
        setBusy(dialog, true)
        thread(name = if (approve) "ge360-trainer-approve" else "ge360-trainer-reject") {
            val result = runCatching {
                if (approve) training.approve(auth.first, auth.second, id, note)
                else training.reject(auth.first, auth.second, id, note)
            }
            activity.runOnUiThread {
                result.onSuccess {
                    dialog.dismiss()
                    toast(if (approve) "Esempio approvato" else "Esempio rifiutato")
                    onDone()
                }.onFailure { error ->
                    setBusy(dialog, false)
                    showError("Revisione non riuscita", error)
                }
            }
        }
    }

    private fun askRejectReason(onReason: (String) -> Unit) {
        val note = multiline("Motivo del rifiuto (opzionale)", 3)
        AlertDialog.Builder(activity)
            .setTitle("Rifiuta esempio")
            .setView(note)
            .setPositiveButton("RIFIUTA") { _, _ -> onReason(note.text.toString().trim()) }
            .setNegativeButton("ANNULLA", null)
            .show()
    }

    private fun showScopePicker(title: String, onSelected: (TrainerScopeOption) -> Unit) {
        val root = formRoot()
        val controls = addScopeControls(root)
        val dialog = AlertDialog.Builder(activity)
            .setTitle(title)
            .setView(root)
            .setPositiveButton("CONTINUA", null)
            .setNegativeButton("ANNULLA", null)
            .create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val scope = selectedScope(controls)
                if (scope == null) {
                    toast("Seleziona un contesto disponibile")
                    return@setOnClickListener
                }
                dialog.dismiss()
                onSelected(scope)
            }
        }
        dialog.show()
    }

    private fun addScopeControls(root: LinearLayout): ScopeControls {
        root.addView(label("Dove vale questo insegnamento"))
        val typeSpinner = Spinner(activity)
        val typeAdapter = ArrayAdapter(
            activity,
            android.R.layout.simple_spinner_item,
            scopeCatalog.types
        ).apply { setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item) }
        typeSpinner.adapter = typeAdapter
        root.addView(typeSpinner, matchWrap())

        val targetLabel = label("Contesto specifico")
        val targetSpinner = Spinner(activity)
        root.addView(targetLabel)
        root.addView(targetSpinner, matchWrap())
        root.addView(
            explainer(
                "Globale vale ovunque. Gli altri ambiti usano gli ID reali già salvati nell'App e sincronizzati con GE360."
            )
        )

        val controls = ScopeControls(
            types = scopeCatalog.types,
            typeSpinner = typeSpinner,
            targetLabel = targetLabel,
            targetSpinner = targetSpinner
        )
        typeSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                refreshScopeTargets(controls)
            }

            override fun onNothingSelected(parent: AdapterView<*>?) = Unit
        }
        refreshScopeTargets(controls)
        return controls
    }

    private fun refreshScopeTargets(controls: ScopeControls) {
        val type = controls.types.getOrNull(controls.typeSpinner.selectedItemPosition)?.type ?: "global"
        controls.targets = scopeCatalog.options(type)
        val adapter = ArrayAdapter(
            activity,
            android.R.layout.simple_spinner_item,
            controls.targets
        ).apply { setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item) }
        controls.targetSpinner.adapter = adapter

        val global = type == "global"
        controls.targetLabel.visibility = if (global) View.GONE else View.VISIBLE
        controls.targetSpinner.visibility = if (global || controls.targets.isEmpty()) View.GONE else View.VISIBLE
        if (!global && controls.targets.isEmpty()) {
            controls.targetLabel.text = "Nessun ${scopeTypeLabel(type).lowercase()} disponibile: sincronizza o crea prima il dato nell'App"
        } else {
            controls.targetLabel.text = "Contesto specifico"
        }
    }

    private fun selectedScope(controls: ScopeControls): TrainerScopeOption? {
        val type = controls.types.getOrNull(controls.typeSpinner.selectedItemPosition)?.type ?: "global"
        if (type == "global") return TrainerScopeOption("global", "", "Globale")
        val selected = controls.targets.getOrNull(controls.targetSpinner.selectedItemPosition) ?: return null
        return selected.takeIf { it.type == type && it.id.isNotBlank() }
    }

    private fun authorizedContext(): Pair<BridgeServiceConfig, TrainerDeviceConfig>? {
        val server = bridgeSettings.get(BridgeService.JARVIS)
        if (!server.configured) {
            toast("Configura prima la connessione Jarvis")
            return null
        }
        val trainer = trainerSettings.get()
        if (!trainer.paired) {
            AlertDialog.Builder(activity)
                .setTitle("Telefono non abbinato come Trainer")
                .setMessage("Per insegnare o correggere Jarvis devi prima abbinare questo telefono con la credenziale Trainer del server GE360.")
                .setPositiveButton("APRI PAIRING") { _, _ ->
                    TrainerPairingDialog(activity, bridgeSettings).show()
                }
                .setNegativeButton("ANNULLA", null)
                .show()
            return null
        }
        return server to trainer
    }

    private fun renderEntry(entry: JSONObject, position: Int, total: Int): String = buildString {
        appendLine("Esempio $position di $total")
        appendLine("ID: ${entry.optString("id")}")
        appendLine("Tipo: ${entry.optString("kind", "?")}")
        appendLine("Categoria: ${entry.optString("category", "general")}")
        appendLine("Stato: ${entry.optString("status", "pending")}")
        val scopeType = entry.optString("scopeType", "global").ifBlank { "global" }
        val scopeId = entry.optString("scopeId").trim()
        val scopeLabel = entry.optJSONObject("metadata")?.optString("scope_label").orEmpty().trim()
        val scopeTarget = scopeLabel.ifBlank { scopeId }
        appendLine(
            "Ambito: ${scopeTypeLabel(scopeType)}" +
                if (scopeTarget.isNotBlank()) " · $scopeTarget" else ""
        )
        appendLine("Origine: ${entry.optString("sourceDeviceId", "?")}")
        appendLine()
        val user = entry.optString("userText").trim()
        val assistant = entry.optString("assistantText").trim()
        val preferred = entry.optString("preferredText").trim()
        if (user.isNotBlank()) appendLine("RICHIESTA\n$user\n")
        if (assistant.isNotBlank()) appendLine("RISPOSTA JARVIS\n$assistant\n")
        if (preferred.isNotBlank()) appendLine("TESTO DA IMPARARE / RISPOSTA PREFERITA\n$preferred\n")
        val rating = entry.optString("rating").trim()
        if (rating.isNotBlank()) appendLine("VALUTAZIONE: $rating")
        val note = entry.optString("note").trim()
        if (note.isNotBlank()) appendLine("NOTA: $note")
        appendLine()
        append("APPROVA rende l'esempio verificato solo per l'ambito mostrato. RIFIUTA lo esclude dal flusso di apprendimento.")
    }

    private fun scopeTypeLabel(type: String): String = when (type) {
        "jobsite" -> "Cantiere"
        "work_type" -> "Tipo di lavoro"
        "tool" -> "Attrezzo"
        "material" -> "Materiale"
        else -> "Globale"
    }

    private fun formRoot() = LinearLayout(activity).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(18), dp(8), dp(18), dp(8))
    }

    private fun wrapScroll(root: LinearLayout) = ScrollView(activity).apply { addView(root) }

    private fun label(text: String) = TextView(activity).apply {
        this.text = text
        textSize = 12f
        setTypeface(typeface, Typeface.BOLD)
        setPadding(0, dp(10), 0, dp(3))
    }

    private fun explainer(text: String) = TextView(activity).apply {
        this.text = text
        textSize = 11f
        setPadding(0, dp(10), 0, dp(4))
    }

    private fun singleLine(hintText: String) = EditText(activity).apply {
        hint = hintText
        isSingleLine = true
        inputType = InputType.TYPE_CLASS_TEXT
    }

    private fun multiline(hintText: String, lines: Int) = EditText(activity).apply {
        hint = hintText
        minLines = lines
        gravity = Gravity.TOP
        inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
    }

    private fun readOnly(value: String) = TextView(activity).apply {
        text = value
        textSize = 13f
        setTextIsSelectable(true)
        setPadding(dp(10), dp(8), dp(10), dp(8))
    }

    private fun matchWrap() = LinearLayout.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT,
        ViewGroup.LayoutParams.WRAP_CONTENT
    )

    private fun JSONArray.toObjects(): List<JSONObject> = buildList {
        for (i in 0 until length()) optJSONObject(i)?.let(::add)
    }

    private fun shortId(entry: JSONObject): String = entry.optString("id").take(8).ifBlank { "creato" }

    private fun setBusy(dialog: AlertDialog, busy: Boolean) {
        dialog.getButton(AlertDialog.BUTTON_POSITIVE)?.isEnabled = !busy
        dialog.getButton(AlertDialog.BUTTON_NEGATIVE)?.isEnabled = !busy
        dialog.getButton(AlertDialog.BUTTON_NEUTRAL)?.isEnabled = !busy
    }

    private fun showError(title: String, error: Throwable) {
        val message = error.message.orEmpty().trim().let {
            when {
                it.isBlank() -> error.javaClass.simpleName
                it.length > 500 -> it.take(500) + "…"
                else -> it
            }
        }
        AlertDialog.Builder(activity)
            .setTitle(title)
            .setMessage(message)
            .setPositiveButton("OK", null)
            .show()
    }

    private fun toast(text: String) = Toast.makeText(activity, text, Toast.LENGTH_LONG).show()
    private fun dp(value: Int): Int = (value * activity.resources.displayMetrics.density).toInt()
}
