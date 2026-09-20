package it.edilmilan.ge360.jarvis

import android.Manifest
import android.os.Build
import android.os.Bundle
import android.view.Gravity
import android.view.ViewGroup
import android.widget.CheckBox
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.button.MaterialButton
import it.edilmilan.ge360.core.bridge.BridgeService
import it.edilmilan.ge360.core.bridge.JsonHttpClient
import it.edilmilan.ge360.core.bridge.SecureBridgeSettings
import org.json.JSONObject
import java.text.DateFormat
import java.util.Date
import kotlin.concurrent.thread

class ProactiveAlertsActivity : AppCompatActivity() {
    private lateinit var proactive: JarvisProactiveSettings
    private lateinit var enabled: CheckBox
    private lateinit var includeInfo: CheckBox
    private lateinit var status: TextView
    private lateinit var alertsContainer: LinearLayout
    private lateinit var proposalsContainer: LinearLayout
    private val bridge by lazy { SecureBridgeSettings(this) }
    private val http = JsonHttpClient()
    private val actionClient by lazy { JarvisProactiveActionClient(this) }

    private val notificationPermission = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            JarvisProactiveScheduler.schedule(applicationContext)
            toast("Notifiche Jarvis abilitate")
            refresh()
        } else {
            toast("Permesso notifiche non concesso: puoi comunque vedere gli avvisi qui")
            refreshStatus()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        proactive = JarvisProactiveSettings(this)
        setContentView(buildScreen())
        refreshStatus()
        requestNotificationPermissionIfNeeded()
        refresh()
    }

    private fun buildScreen(): ScrollView {
        val scroll = ScrollView(this)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(14), dp(16), dp(28))
        }
        scroll.addView(root)

        root.addView(LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(button("←") { finish() }, LinearLayout.LayoutParams(dp(56), dp(48)))
            addView(TextView(this@ProactiveAlertsActivity).apply {
                text = "Jarvis · Avvisi proattivi"
                textSize = 22f
                setPadding(dp(8), 0, 0, 0)
            }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        })

        root.addView(TextView(this).apply {
            text = "Jarvis segnala problemi e, quando possibile, può preparare un'azione GE360. Preparare non significa eseguire: ogni azione resta ferma finché premi APPROVA."
            textSize = 13f
            setPadding(0, dp(10), 0, dp(8))
        })

        enabled = CheckBox(this).apply {
            text = "Avvisi proattivi attivi"
            isChecked = proactive.enabled
            setOnCheckedChangeListener { _, checked ->
                proactive.enabled = checked
                if (checked) {
                    requestNotificationPermissionIfNeeded()
                    JarvisProactiveScheduler.schedule(applicationContext)
                } else {
                    JarvisProactiveScheduler.cancel(applicationContext)
                }
                refreshStatus()
            }
        }
        root.addView(enabled)

        includeInfo = CheckBox(this).apply {
            text = "Mostra anche avvisi informativi (non solo importanti)"
            isChecked = proactive.includeInfo
            setOnCheckedChangeListener { _, checked ->
                proactive.includeInfo = checked
                if (proactive.enabled) JarvisProactiveScheduler.checkNow(applicationContext)
                refresh()
            }
        }
        root.addView(includeInfo)

        status = TextView(this).apply {
            textSize = 12f
            setPadding(0, dp(8), 0, dp(8))
            setTextIsSelectable(true)
        }
        root.addView(status)

        root.addView(LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            addView(button("Controlla ora") {
                JarvisProactiveScheduler.checkNow(applicationContext)
                refresh()
            }, LinearLayout.LayoutParams(0, dp(50), 1f).apply { marginEnd = dp(4) })
            addView(button("Permesso notifiche") {
                requestNotificationPermissionIfNeeded(force = true)
            }, LinearLayout.LayoutParams(0, dp(50), 1f).apply { marginStart = dp(4) })
        })

        root.addView(button("Azzera cronologia avvisi già mostrati") {
            AlertDialog.Builder(this)
                .setTitle("Riproporre gli avvisi?")
                .setMessage("Azzera soltanto la deduplica locale. Nessun dato del backend viene modificato.")
                .setPositiveButton("AZZERA") { _, _ ->
                    proactive.clearSeen()
                    JarvisProactiveScheduler.checkNow(applicationContext)
                    refreshStatus()
                    toast("Cronologia locale azzerata")
                }
                .setNegativeButton("ANNULLA", null)
                .show()
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(50)).apply { topMargin = dp(8) })

        root.addView(sectionLabel("AZIONI DA APPROVARE"))
        proposalsContainer = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        root.addView(proposalsContainer)

        root.addView(sectionLabel("AVVISI DAL BACKEND"))
        alertsContainer = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        root.addView(alertsContainer)
        return scroll
    }

    private fun refresh() {
        val server = bridge.get(BridgeService.JARVIS)
        if (!server.configured) {
            showMessage(alertsContainer, "Backend Jarvis non configurato. Apri Jarvis e configura prima la connessione GE360.")
            showMessage(proposalsContainer, "Nessuna proposta disponibile.")
            refreshStatus()
            return
        }
        showMessage(alertsContainer, "Controllo avvisi…")
        showMessage(proposalsContainer, "Controllo azioni in attesa…")
        val minimum = JarvisProactivePolicy.minimumSeverity(proactive.includeInfo)
        thread(name = "ge360-proactive-manual") {
            val feedResult = runCatching {
                http.request(
                    server,
                    "/api/v1/jarvis/proactive?limit=50&minimum_severity=$minimum",
                    timeoutMs = 15_000
                ).jsonObject()
            }
            val proposalsResult = runCatching { actionClient.listPending() }
            runOnUiThread {
                feedResult.onSuccess { root ->
                    if (root.optBoolean("actionsExecuted", true)) {
                        showMessage(alertsContainer, "Feed rifiutato per sicurezza: il backend non dichiara actionsExecuted=false.")
                    } else {
                        renderFeed(root)
                    }
                }.onFailure { error ->
                    showMessage(alertsContainer, "Errore lettura avvisi: ${error.message ?: error.javaClass.simpleName}")
                }
                proposalsResult.onSuccess(::renderProposals).onFailure { error ->
                    showMessage(proposalsContainer, "Errore lettura proposte: ${error.message ?: error.javaClass.simpleName}")
                }
                refreshStatus()
            }
        }
    }

    private fun renderFeed(root: JSONObject) {
        alertsContainer.removeAllViews()
        val array = root.optJSONArray("items")
        if (array == null || array.length() == 0) {
            showMessage(alertsContainer, "Nessun avviso operativo al momento.")
            return
        }
        alertsContainer.addView(TextView(this).apply {
            text = "Critici ${root.optInt("criticalCount", 0)} · Avvisi ${root.optInt("warningCount", 0)} · Info ${root.optInt("infoCount", 0)}"
            textSize = 12f
            setPadding(dp(4), dp(4), dp(4), dp(8))
        })
        for (index in 0 until array.length()) {
            val item = array.optJSONObject(index) ?: continue
            alertsContainer.addView(alertCard(item))
        }
    }

    private fun alertCard(item: JSONObject): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(12), dp(10), dp(12), dp(10))
        val severity = item.optString("severity", "info").uppercase()
        addView(TextView(this@ProactiveAlertsActivity).apply {
            text = "[$severity] ${item.optString("title", "Avviso")}"
            textSize = 14.5f
        })
        val spoken = item.optString("spokenText").trim()
        if (spoken.isNotBlank()) addView(TextView(this@ProactiveAlertsActivity).apply {
            text = spoken
            textSize = 12.5f
            setPadding(0, dp(4), 0, 0)
        })
        val due = item.optString("dueDate").trim()
        if (due.isNotBlank()) addView(TextView(this@ProactiveAlertsActivity).apply {
            text = "Data: $due"
            textSize = 11.5f
            setPadding(0, dp(3), 0, 0)
        })
        val action = item.optJSONObject("suggestedAction")
        val canPrepare = action != null && JarvisProactiveActionPolicy.canPrepare(
            available = action.optBoolean("available", false),
            requiresApproval = action.optBoolean("requiresApproval", false),
            autoExecute = action.optBoolean("autoExecute", true)
        )
        if (canPrepare) addView(button(action!!.optString("label", "PREPARA AZIONE").uppercase()) {
            prepareAction(item.optString("id"))
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(48)).apply { topMargin = dp(8) })
    }.also { it.layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(8) } }

    private fun renderProposals(root: JSONObject) {
        proposalsContainer.removeAllViews()
        if (root.optBoolean("actionsExecuted", true)) {
            showMessage(proposalsContainer, "Risposta proposte rifiutata per sicurezza.")
            return
        }
        val array = root.optJSONArray("items")
        if (array == null || array.length() == 0) {
            showMessage(proposalsContainer, "Nessuna azione in attesa di approvazione.")
            return
        }
        for (index in 0 until array.length()) {
            val proposal = array.optJSONObject(index) ?: continue
            proposalsContainer.addView(proposalCard(proposal))
        }
    }

    private fun proposalCard(proposal: JSONObject): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(12), dp(10), dp(12), dp(10))
        addView(TextView(this@ProactiveAlertsActivity).apply {
            text = proposal.optString("summary", "Azione Jarvis")
            textSize = 14f
        })
        addView(TextView(this@ProactiveAlertsActivity).apply {
            text = "IN ATTESA · nessuna modifica eseguita"
            textSize = 11.5f
            setPadding(0, dp(3), 0, dp(6))
        })
        addView(LinearLayout(this@ProactiveAlertsActivity).apply {
            orientation = LinearLayout.HORIZONTAL
            addView(button("APPROVA") { showProposalConfirmation(proposal) }, LinearLayout.LayoutParams(0, dp(46), 1f).apply { marginEnd = dp(4) })
            addView(button("RIFIUTA") { rejectProposal(proposal.optString("id")) }, LinearLayout.LayoutParams(0, dp(46), 1f).apply { marginStart = dp(4) })
        })
    }.also { it.layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(8) } }

    private fun prepareAction(alertId: String) {
        if (alertId.isBlank()) return
        toast("Preparo la proposta. Non eseguo nulla.")
        thread(name = "ge360-proactive-prepare") {
            val result = runCatching { actionClient.prepare(alertId) }
            runOnUiThread {
                result.onSuccess { proposal ->
                    if (JarvisProactiveActionPolicy.canApprove(
                            proposal.optString("status"),
                            proposal.optBoolean("requiresApproval", false),
                            proposal.optBoolean("autoExecute", true)
                        )) {
                        showProposalConfirmation(proposal)
                    } else {
                        toast("Proposta non approvabile in sicurezza. Aggiorno gli avvisi.")
                        refresh()
                    }
                }.onFailure { error -> toast("Impossibile preparare: ${error.message}") }
            }
        }
    }

    private fun showProposalConfirmation(proposal: JSONObject) {
        if (!JarvisProactiveActionPolicy.canApprove(
                proposal.optString("status"),
                proposal.optBoolean("requiresApproval", false),
                proposal.optBoolean("autoExecute", true)
            )) {
            toast("Questa proposta non è più in attesa di approvazione")
            refresh()
            return
        }
        val proposalId = proposal.optString("id")
        AlertDialog.Builder(this)
            .setTitle("Confermi questa azione?")
            .setMessage("${proposal.optString("summary", "Azione Jarvis")}\n\nSolo premendo APPROVA il backend proverà a eseguirla. Prima controllerà di nuovo che l'avviso sia ancora valido.")
            .setPositiveButton("APPROVA") { _, _ -> approveProposal(proposalId) }
            .setNegativeButton("ANNULLA", null)
            .setNeutralButton("RIFIUTA") { _, _ -> rejectProposal(proposalId) }
            .show()
    }

    private fun approveProposal(proposalId: String) = mutateProposal("approve", proposalId)
    private fun rejectProposal(proposalId: String) = mutateProposal("reject", proposalId)

    private fun mutateProposal(action: String, proposalId: String) {
        if (proposalId.isBlank()) return
        thread(name = "ge360-proactive-$action") {
            val result = runCatching {
                if (action == "approve") actionClient.approve(proposalId) else actionClient.reject(proposalId)
            }
            runOnUiThread {
                result.onSuccess { response ->
                    val state = response.optString("status")
                    toast(if (state == "executed") "Azione eseguita e registrata" else "Proposta: $state")
                    refresh()
                }.onFailure { error ->
                    toast("Azione non eseguita: ${error.message}")
                    refresh()
                }
            }
        }
    }

    private fun showMessage(container: LinearLayout, message: String) {
        container.removeAllViews()
        container.addView(TextView(this).apply {
            text = message
            textSize = 13f
            setPadding(dp(10), dp(10), dp(10), dp(10))
        })
    }

    private fun sectionLabel(value: String) = TextView(this).apply {
        text = value
        textSize = 12f
        setPadding(0, dp(18), 0, dp(6))
    }

    private fun refreshStatus() {
        val permission = JarvisProactiveNotifications.permissionGranted(this)
        val checkedAt = proactive.lastCheckAt.takeIf { it > 0 }?.let {
            DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(it))
        } ?: "mai"
        status.text = buildString {
            append("Stato: ${if (proactive.enabled) "attivo" else "disattivato"}")
            append(" · notifiche ${if (permission) "consentite" else "non consentite"}")
            append("\nUltimo controllo: $checkedAt")
            if (proactive.lastRevision.isNotBlank()) append(" · rev ${proactive.lastRevision.take(8)}")
            if (proactive.lastError.isNotBlank()) append("\nUltimo problema: ${proactive.lastError}")
        }
    }

    private fun requestNotificationPermissionIfNeeded(force: Boolean = false) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        if (JarvisProactiveNotifications.permissionGranted(this)) {
            if (force) toast("Permesso notifiche già concesso")
            return
        }
        notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
    }

    private fun button(text: String, action: () -> Unit) = MaterialButton(this).apply {
        this.text = text
        setOnClickListener { action() }
    }

    private fun toast(text: String) = Toast.makeText(this, text, Toast.LENGTH_LONG).show()
    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()
}
