package it.edilmilan.ge360.jarvis

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Typeface
import android.text.InputType
import android.view.ViewGroup
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.button.MaterialButton
import it.edilmilan.ge360.core.bridge.BridgeService
import it.edilmilan.ge360.core.bridge.JsonHttpClient
import it.edilmilan.ge360.core.bridge.SecureBridgeSettings
import it.edilmilan.ge360.core.bridge.SecureTrainerDeviceSettings
import it.edilmilan.ge360.core.bridge.TrainingBridgeClient
import kotlin.concurrent.thread

/**
 * Pairing lato telefono. La registrazione/rotazione/revoca della credenziale resta volutamente
 * locale al server GE360; Android riceve soltanto il token mostrato una volta e lo conserva nel
 * proprio Android Keystore.
 */
class TrainerPairingDialog(
    private val activity: AppCompatActivity,
    private val bridgeSettings: SecureBridgeSettings,
    http: JsonHttpClient = JsonHttpClient(),
    private val onStateChanged: (() -> Unit)? = null
) {
    private val trainerSettings = SecureTrainerDeviceSettings(activity)
    private val training = TrainingBridgeClient(http)

    fun show() {
        val deviceId = trainerSettings.deviceId()
        val root = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(8), dp(20), dp(4))
        }

        root.addView(TextView(activity).apply {
            text = "ID dispositivo Trainer"
            textSize = 12f
            setTypeface(typeface, Typeface.BOLD)
        })
        val idView = TextView(activity).apply {
            text = deviceId
            textSize = 12f
            setTextIsSelectable(true)
            setPadding(0, dp(5), 0, dp(6))
        }
        root.addView(idView)
        root.addView(MaterialButton(activity).apply {
            text = "COPIA ID DISPOSITIVO"
            setOnClickListener { copyDeviceId(deviceId) }
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(48)))

        root.addView(TextView(activity).apply {
            text = "Sul server GE360 locale registra questo ID come dispositivo Trainer. Il server mostrera' una credenziale una sola volta: incollala qui."
            textSize = 12f
            setPadding(0, dp(10), 0, dp(8))
        })

        val tokenInput = EditText(activity).apply {
            hint = if (trainerSettings.hasTrainerToken()) {
                "Credenziale gia' salvata · lascia vuoto per verificarla"
            } else {
                "Credenziale Trainer trn_..."
            }
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            isSingleLine = true
        }
        root.addView(tokenInput, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(54)))

        val status = TextView(activity).apply {
            text = if (trainerSettings.hasTrainerToken()) {
                "Credenziale protetta nel Keystore · verifica consigliata"
            } else {
                "Telefono non ancora abbinato come Trainer"
            }
            textSize = 12f
            setPadding(0, dp(10), 0, dp(4))
        }
        root.addView(status)

        root.addView(TextView(activity).apply {
            text = "La credenziale Trainer e' separata dal token normale di Jarvis, non viene mostrata dopo il salvataggio e non entra nei backup Android."
            textSize = 11f
            setPadding(0, dp(6), 0, 0)
        })

        val dialog = AlertDialog.Builder(activity)
            .setTitle("Dispositivo Trainer")
            .setView(root)
            .setPositiveButton("ABBINA / VERIFICA", null)
            .setNeutralButton("DISACCOPPIA", null)
            .setNegativeButton("CHIUDI", null)
            .create()

        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                pairOrVerify(tokenInput, status)
            }
            dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener {
                confirmClear(status, tokenInput)
            }
        }
        dialog.show()
    }

    private fun pairOrVerify(tokenInput: EditText, status: TextView) {
        val typed = tokenInput.text.toString().trim()
        if (typed.isNotBlank()) {
            val saved = runCatching { trainerSettings.saveTrainerToken(typed) }
            if (saved.isFailure) {
                tokenInput.text?.clear()
                status.text = saved.exceptionOrNull()?.message ?: "Credenziale Trainer non valida"
                return
            }
            tokenInput.text?.clear()
            tokenInput.hint = "Credenziale salvata · lascia vuoto per verificarla"
        }

        val trainer = trainerSettings.get()
        if (!trainer.paired) {
            status.text = "Inserisci prima la credenziale Trainer generata dal server GE360"
            return
        }
        val server = bridgeSettings.get(BridgeService.JARVIS)
        if (!server.configured) {
            status.text = "Configura prima la connessione Jarvis"
            return
        }

        status.text = "Verifica autorizzazione Trainer in corso..."
        thread(name = "ge360-trainer-pairing") {
            val check = runCatching { training.me(server, trainer) }
            activity.runOnUiThread {
                check.onSuccess { me ->
                    val returnedId = me.optString("deviceId")
                    val enabled = me.optBoolean("enabled", false)
                    if (returnedId != trainer.deviceId || !enabled) {
                        trainerSettings.clearTrainerToken()
                        tokenInput.hint = "Credenziale Trainer trn_..."
                        status.text = "Il server non ha confermato questo dispositivo Trainer"
                        onStateChanged?.invoke()
                        return@onSuccess
                    }
                    val version = me.optInt("credentialVersion", 0)
                    val label = me.optString("label", trainerSettings.deviceLabel())
                    status.text = "Trainer autorizzato · $label · credenziale v$version"
                    onStateChanged?.invoke()
                }.onFailure { error ->
                    if (error.message.orEmpty().startsWith("HTTP 403")) {
                        trainerSettings.clearTrainerToken()
                        tokenInput.hint = "Credenziale Trainer trn_..."
                        status.text = "Credenziale rifiutata o revocata dal server · abbinamento locale rimosso"
                        onStateChanged?.invoke()
                    } else {
                        status.text = "Impossibile verificare ora il Trainer: ${safeMessage(error)}"
                    }
                }
            }
        }
    }

    private fun confirmClear(status: TextView, tokenInput: EditText) {
        if (!trainerSettings.hasTrainerToken()) {
            status.text = "Nessuna credenziale Trainer salvata sul telefono"
            return
        }
        AlertDialog.Builder(activity)
            .setTitle("Disaccoppiare questo telefono?")
            .setMessage("Viene cancellata solo la credenziale locale. La revoca definitiva del dispositivo resta disponibile sul server GE360.")
            .setPositiveButton("DISACCOPPIA") { _, _ ->
                trainerSettings.clearTrainerToken()
                tokenInput.text?.clear()
                tokenInput.hint = "Credenziale Trainer trn_..."
                status.text = "Credenziale Trainer rimossa dal telefono"
                onStateChanged?.invoke()
            }
            .setNegativeButton("ANNULLA", null)
            .show()
    }

    private fun copyDeviceId(deviceId: String) {
        val clipboard = activity.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText("GE360 Trainer device ID", deviceId))
        Toast.makeText(activity, "ID dispositivo copiato", Toast.LENGTH_SHORT).show()
    }

    private fun safeMessage(error: Throwable): String {
        val message = error.message.orEmpty().trim()
        return when {
            message.isBlank() -> error.javaClass.simpleName
            message.length > 180 -> message.take(180) + "..."
            else -> message
        }
    }

    private fun dp(value: Int): Int = (value * activity.resources.displayMetrics.density).toInt()
}
