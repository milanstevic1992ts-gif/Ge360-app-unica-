package it.edilmilan.ge360.jarvis

import android.content.Context
import it.edilmilan.ge360.core.bridge.BridgeService
import it.edilmilan.ge360.core.bridge.JsonHttpClient
import it.edilmilan.ge360.core.bridge.SecureBridgeSettings
import it.edilmilan.ge360.core.bridge.SecureTrainerDeviceSettings
import org.json.JSONObject
import java.net.URLEncoder

object JarvisProactiveActionPolicy {
    fun canPrepare(available: Boolean, requiresApproval: Boolean, autoExecute: Boolean): Boolean =
        available && requiresApproval && !autoExecute

    fun canApprove(status: String, requiresApproval: Boolean, autoExecute: Boolean): Boolean =
        status.trim().equals("proposed", ignoreCase = true) && requiresApproval && !autoExecute
}

/**
 * Fase 9: client esplicito delle proposal proattive.
 * Il worker in background non usa questa classe: le mutazioni possono partire solo dalla UI.
 */
class JarvisProactiveActionClient(context: Context) {
    private val appContext = context.applicationContext
    private val bridge = SecureBridgeSettings(appContext)
    private val http = JsonHttpClient()
    private val deviceId by lazy { SecureTrainerDeviceSettings(appContext).deviceId() }

    fun prepare(alertId: String): JSONObject = post(
        "/api/v1/jarvis/proactive/${segment(alertId)}/proposal"
    )

    fun listPending(): JSONObject = request(
        "/api/v1/jarvis/proactive/proposals?status=proposed&limit=50"
    )

    fun approve(proposalId: String): JSONObject = post(
        "/api/v1/jarvis/proactive/proposals/${segment(proposalId)}/approve"
    )

    fun reject(proposalId: String): JSONObject = post(
        "/api/v1/jarvis/proactive/proposals/${segment(proposalId)}/reject"
    )

    private fun post(path: String): JSONObject = request(path, method = "POST")

    private fun request(path: String, method: String = "GET"): JSONObject {
        val server = bridge.get(BridgeService.JARVIS)
        require(server.configured) { "Backend Jarvis non configurato" }
        return http.request(
            config = server,
            path = path,
            method = method,
            body = if (method == "POST") JSONObject() else null,
            timeoutMs = 15_000,
            extraHeaders = mapOf("X-GE360-Device-Id" to deviceId)
        ).jsonObject()
    }

    private fun segment(value: String): String {
        val clean = value.trim()
        require(clean.isNotBlank()) { "ID mancante" }
        return URLEncoder.encode(clean, Charsets.UTF_8.name()).replace("+", "%20")
    }
}
