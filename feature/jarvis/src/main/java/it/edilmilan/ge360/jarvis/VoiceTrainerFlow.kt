package it.edilmilan.ge360.jarvis

import android.view.View
import android.view.ViewGroup
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.LinearLayout
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
import org.json.JSONObject
import kotlin.concurrent.thread

/**
 * Fase 7: interpreta il testo riconosciuto dal microfono tramite il backend Trainer,
 * ma non salva mai nulla senza una seconda conferma esplicita dell'utente.
 */
class VoiceTrainerFlow(
    private val activity: AppCompatActivity,
    private val bridgeSettings: SecureBridgeSettings,
    http: JsonHttpClient = JsonHttpClient(),
    private val sessionIdProvider: () -> String
) {
    private val trainerSettings = SecureTrainerDeviceSettings(activity)
    private val training = TrainingBridgeClient(http)
    private val scopes = TrainerScopeCatalog(activity)

    fun handleTranscript(transcript: String, conversation: TrainerConversation) {
        val clean = transcript.trim()
        if (clean.length < 2) {
            toast("Non ho capito il comando vocale")
            return
        }
        val auth = authorizedContext() ?: return
        toast("Interpreto il comando vocale…")
        thread(name = "ge360-voice-trainer-interpret") {
            val result = runCatching {
                training.interpretVoice(auth.first, auth.second, clean)
            }
            activity.runOnUiThread {
                result.onSuccess { interpretation ->
                    dispatch(auth, interpretation, clean, conversation)
                }.onFailure { error ->
                    showError("Voice Trainer non disponibile", error)
                }
            }
        }
    }

    private fun dispatch(
        auth: Pair<BridgeServiceConfig, TrainerDeviceConfig>,
        interpretation: JSONObject,
        originalTranscript: String,
        conversation: TrainerConversation
    ) {
        val action = interpretation.optString("action", "unknown")
        val content = interpretation.optString("content").trim()
        when (action) {
            "teach" -> confirmTeaching(auth, content, originalTranscript)
            "correct" -> {
                if (!conversation.available) {
                    toast("Per correggere serve prima una risposta reale di Jarvis")
                    return
                }
                confirmCorrection(auth, conversation, content, originalTranscript)
            }
            "open_correction" -> {
                if (!conversation.available) {
                    toast("Per correggere serve prima una risposta reale di Jarvis")
                    return
                }
                TrainerLearningDialog(activity, bridgeSettings, sessionIdProvider = sessionIdProvider)
                    .showCorrection(conversation)
            }
            "rate_positive" -> confirmRating(auth, conversation, positive = true, originalTranscript)
            "rate_negative" -> confirmRating(auth, conversation, positive = false, originalTranscript)
            else -> showUnknown(auth, originalTranscript)
        }
    }

    private fun confirmTeaching(
        auth: Pair<BridgeServiceConfig, TrainerDeviceConfig>,
        content: String,
        transcript: String
    ) {
        if (content.length < 3) return showUnknown(auth, transcript)
        AlertDialog.Builder(activity)
            .setTitle("Conferma insegnamento vocale")
            .setMessage("Jarvis ha capito:\n\n$content\n\nVuoi salvarlo nella coda PENDING?")
            .setPositiveButton("SCEGLI AMBITO") { _, _ ->
                chooseScope("Dove deve valere questa regola?") { scope ->
                    saveTeaching(auth, content, transcript, scope)
                }
            }
            .setNegativeButton("ANNULLA", null)
            .show()
    }

    private fun confirmCorrection(
        auth: Pair<BridgeServiceConfig, TrainerDeviceConfig>,
        conversation: TrainerConversation,
        content: String,
        transcript: String
    ) {
        if (content.length < 3) {
            TrainerLearningDialog(activity, bridgeSettings, sessionIdProvider = sessionIdProvider)
                .showCorrection(conversation)
            return
        }
        val message = buildString {
            appendLine("Ultima richiesta:")
            appendLine(conversation.userText)
            appendLine()
            appendLine("Correzione dettata:")
            append(content)
        }
        AlertDialog.Builder(activity)
            .setTitle("Conferma correzione vocale")
            .setMessage(message)
            .setPositiveButton("SCEGLI AMBITO") { _, _ ->
                chooseScope("Dove deve valere questa correzione?") { scope ->
                    saveCorrection(auth, conversation, content, transcript, scope)
                }
            }
            .setNegativeButton("ANNULLA", null)
            .show()
    }

    private fun confirmRating(
        auth: Pair<BridgeServiceConfig, TrainerDeviceConfig>,
        conversation: TrainerConversation,
        positive: Boolean,
        transcript: String
    ) {
        if (!conversation.available) {
            toast("Non c'è ancora una risposta Jarvis da valutare")
            return
        }
        AlertDialog.Builder(activity)
            .setTitle(if (positive) "Conferma risposta corretta" else "Conferma risposta da migliorare")
            .setMessage("La valutazione verrà salvata solo dopo la scelta dell'ambito e resterà tracciata come origine vocale.")
            .setPositiveButton("SCEGLI AMBITO") { _, _ ->
                chooseScope("Dove vale questa valutazione?") { scope ->
                    saveRating(auth, conversation, positive, transcript, scope)
                }
            }
            .setNegativeButton("ANNULLA", null)
            .show()
    }

    private fun showUnknown(
        auth: Pair<BridgeServiceConfig, TrainerDeviceConfig>,
        transcript: String
    ) {
        AlertDialog.Builder(activity)
            .setTitle("Comando vocale non riconosciuto")
            .setMessage("Ho trascritto:\n\n$transcript\n\nNessun dato è stato salvato. Vuoi usare comunque questo testo come insegnamento?")
            .setPositiveButton("USA COME INSEGNAMENTO") { _, _ ->
                chooseScope("Dove deve valere questo insegnamento?") { scope ->
                    saveTeaching(auth, transcript, transcript, scope)
                }
            }
            .setNegativeButton("ANNULLA", null)
            .show()
    }

    private fun saveTeaching(
        auth: Pair<BridgeServiceConfig, TrainerDeviceConfig>,
        content: String,
        transcript: String,
        scope: TrainerScopeOption
    ) {
        thread(name = "ge360-voice-trainer-teach") {
            val result = runCatching {
                training.teach(
                    server = auth.first,
                    trainer = auth.second,
                    instruction = content,
                    note = "Origine vocale confermata. Trascrizione: ${transcript.take(500)}",
                    sessionId = sessionIdProvider(),
                    scopeType = scope.type,
                    scopeId = scope.id,
                    scopeLabel = scope.label,
                    source = "android-voice-trainer"
                )
            }
            activity.runOnUiThread {
                result.onSuccess { toast("Insegnamento vocale salvato PENDING · ${scope.label}") }
                    .onFailure { showError("Salvataggio vocale non riuscito", it) }
            }
        }
    }

    private fun saveCorrection(
        auth: Pair<BridgeServiceConfig, TrainerDeviceConfig>,
        conversation: TrainerConversation,
        content: String,
        transcript: String,
        scope: TrainerScopeOption
    ) {
        thread(name = "ge360-voice-trainer-correct") {
            val result = runCatching {
                training.correct(
                    server = auth.first,
                    trainer = auth.second,
                    userText = conversation.userText,
                    assistantText = conversation.assistantText,
                    correction = content,
                    note = "Correzione vocale confermata. Trascrizione: ${transcript.take(500)}",
                    sessionId = sessionIdProvider(),
                    modelName = conversation.modelName,
                    scopeType = scope.type,
                    scopeId = scope.id,
                    scopeLabel = scope.label,
                    source = "android-voice-trainer"
                )
            }
            activity.runOnUiThread {
                result.onSuccess { toast("Correzione vocale salvata PENDING · ${scope.label}") }
                    .onFailure { showError("Correzione vocale non salvata", it) }
            }
        }
    }

    private fun saveRating(
        auth: Pair<BridgeServiceConfig, TrainerDeviceConfig>,
        conversation: TrainerConversation,
        positive: Boolean,
        transcript: String,
        scope: TrainerScopeOption
    ) {
        thread(name = "ge360-voice-trainer-rate") {
            val result = runCatching {
                training.rate(
                    server = auth.first,
                    trainer = auth.second,
                    positive = positive,
                    userText = conversation.userText,
                    assistantText = conversation.assistantText,
                    note = "Valutazione vocale confermata. Trascrizione: ${transcript.take(500)}",
                    sessionId = sessionIdProvider(),
                    modelName = conversation.modelName,
                    scopeType = scope.type,
                    scopeId = scope.id,
                    scopeLabel = scope.label,
                    source = "android-voice-trainer"
                )
            }
            activity.runOnUiThread {
                result.onSuccess {
                    toast((if (positive) "Valutazione positiva" else "Valutazione negativa") + " salvata · ${scope.label}")
                }.onFailure { showError("Valutazione vocale non salvata", it) }
            }
        }
    }

    private fun chooseScope(title: String, onSelected: (TrainerScopeOption) -> Unit) {
        val root = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(8), dp(18), dp(8))
        }
        val typeSpinner = Spinner(activity)
        val targetLabel = TextView(activity).apply { text = "Contesto specifico" }
        val targetSpinner = Spinner(activity)
        val typeAdapter = ArrayAdapter(
            activity,
            android.R.layout.simple_spinner_item,
            scopes.types
        ).apply { setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item) }
        typeSpinner.adapter = typeAdapter
        root.addView(TextView(activity).apply { text = "Dove vale"; textSize = 13f })
        root.addView(typeSpinner, matchWrap())
        root.addView(targetLabel, matchWrap())
        root.addView(targetSpinner, matchWrap())

        var targets: List<TrainerScopeOption> = scopes.options("global")
        fun refresh() {
            val type = scopes.types.getOrNull(typeSpinner.selectedItemPosition)?.type ?: "global"
            targets = scopes.options(type)
            targetSpinner.adapter = ArrayAdapter(
                activity,
                android.R.layout.simple_spinner_item,
                targets
            ).apply { setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item) }
            val global = type == "global"
            targetLabel.visibility = if (global) View.GONE else View.VISIBLE
            targetSpinner.visibility = if (global || targets.isEmpty()) View.GONE else View.VISIBLE
            if (!global && targets.isEmpty()) targetLabel.text = "Nessun contesto disponibile nell'App"
            else targetLabel.text = "Contesto specifico"
        }
        typeSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) = refresh()
            override fun onNothingSelected(parent: AdapterView<*>?) = Unit
        }
        refresh()

        val dialog = AlertDialog.Builder(activity)
            .setTitle(title)
            .setView(root)
            .setPositiveButton("CONTINUA", null)
            .setNegativeButton("ANNULLA", null)
            .create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val type = scopes.types.getOrNull(typeSpinner.selectedItemPosition)?.type ?: "global"
                val scope = if (type == "global") {
                    TrainerScopeOption("global", "", "Globale")
                } else {
                    targets.getOrNull(targetSpinner.selectedItemPosition)
                }
                if (scope == null || (scope.type != "global" && scope.id.isBlank())) {
                    toast("Seleziona un contesto valido")
                    return@setOnClickListener
                }
                dialog.dismiss()
                onSelected(scope)
            }
        }
        dialog.show()
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
                .setMessage("Abbina prima questo telefono dalla sezione Dispositivo Trainer.")
                .setPositiveButton("OK", null)
                .show()
            return null
        }
        return server to trainer
    }

    private fun showError(title: String, error: Throwable) {
        AlertDialog.Builder(activity)
            .setTitle(title)
            .setMessage(error.message?.take(600) ?: error.javaClass.simpleName)
            .setPositiveButton("OK", null)
            .show()
    }

    private fun matchWrap() = LinearLayout.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT,
        ViewGroup.LayoutParams.WRAP_CONTENT
    )

    private fun toast(text: String) = Toast.makeText(activity, text, Toast.LENGTH_LONG).show()
    private fun dp(value: Int): Int = (value * activity.resources.displayMetrics.density).toInt()
}
