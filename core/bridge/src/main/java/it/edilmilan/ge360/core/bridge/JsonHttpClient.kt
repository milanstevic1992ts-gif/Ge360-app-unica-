package it.edilmilan.ge360.core.bridge

import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.UUID

data class BridgeHttpResponse(val code: Int, val text: String) {
    fun jsonObject(): JSONObject = JSONObject(text.ifBlank { "{}" })
    fun jsonArray(): JSONArray = JSONArray(text.ifBlank { "[]" })
}

class JsonHttpClient {
    fun request(
        config: BridgeServiceConfig,
        path: String,
        method: String = "GET",
        body: JSONObject? = null,
        timeoutMs: Int = 10_000,
        extraHeaders: Map<String, String> = emptyMap()
    ): BridgeHttpResponse {
        require(config.configured) { "Servizio non configurato" }
        if (config.baseUrl.startsWith("http://", true) && !config.allowInsecureHttp) {
            error("HTTP non sicuro bloccato: abilitalo esplicitamente oppure usa HTTPS")
        }
        val cleanPath = if (path.startsWith('/')) path else "/$path"
        val connection = (URL(config.baseUrl.trimEnd('/') + cleanPath).openConnection() as HttpURLConnection).apply {
            requestMethod = method.uppercase()
            connectTimeout = timeoutMs
            readTimeout = timeoutMs
            setRequestProperty("Accept", "application/json")
            setRequestProperty("Content-Type", "application/json")
            setRequestProperty("X-GE360-Protocol", "2")
            setRequestProperty("X-GE360-Api-Version", "1")
            setRequestProperty("X-GE360-Request-Id", UUID.randomUUID().toString())
            if (config.token.isNotBlank()) setRequestProperty("Authorization", "Bearer ${config.token}")
            extraHeaders.forEach { (name, value) ->
                require(HEADER_NAME.matches(name)) { "Nome header HTTP non valido" }
                require(!value.contains('\r') && !value.contains('\n')) { "Valore header HTTP non valido" }
                setRequestProperty(name, value)
            }
            doInput = true
            if (body != null) doOutput = true
        }
        try {
            if (body != null) connection.outputStream.bufferedWriter(Charsets.UTF_8).use { it.write(body.toString()) }
            val code = connection.responseCode
            val stream = if (code in 200..299) connection.inputStream else connection.errorStream
            val text = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()
            if (code !in 200..299) error("HTTP $code${if (text.isBlank()) "" else ": $text"}")
            return BridgeHttpResponse(code, text)
        } finally {
            connection.disconnect()
        }
    }

    companion object {
        private val HEADER_NAME = Regex("^[A-Za-z0-9-]+$")
    }
}
