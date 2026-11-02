package it.edilmilan.ge360.core.bridge

import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.util.UUID

data class BridgeHttpResponse(val code: Int, val text: String) {
    fun jsonObject(): JSONObject = JSONObject(text.ifBlank { "{}" })
    fun jsonArray(): JSONArray = JSONArray(text.ifBlank { "[]" })
}

/**
 * Il server ha risposto, ma con un codice fuori da 2xx.
 *
 * Serve a distinguere i tre esiti che prima erano indistinguibili, perche' per
 * chi scrive dati non sono affatto equivalenti:
 *
 * - **[BridgeHttpException] 4xx**: la richiesta e' arrivata ed e' stata
 *   rifiutata. Non ha prodotto nulla: ritentarla identica e' inutile.
 * - **[BridgeHttpException] 5xx**: la richiesta e' arrivata e il server e'
 *   andato in errore. Puo' avere comunque scritto qualcosa.
 * - **[IOException] semplice**: nessuna risposta (timeout, connessione caduta).
 *   La richiesta puo' essere arrivata o no: l'esito e' ignoto.
 *
 * Il messaggio mantiene il formato "HTTP <codice>: <corpo>" di prima, cosi' i
 * testi mostrati all'utente e quelli salvati non cambiano aspetto.
 */
class BridgeHttpException(
    val code: Int,
    val bodySnippet: String
) : IOException("HTTP $code" + if (bodySnippet.isBlank()) "" else ": $bodySnippet") {

    /** True quando il server ha rifiutato la richiesta senza elaborarla. */
    val isClientError: Boolean get() = code in 400..499

    /** True quando il server ha ricevuto la richiesta ma e' andato in errore. */
    val isServerError: Boolean get() = code >= 500
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
            val text = stream?.let { readCapped(it) }.orEmpty()
            if (code !in 200..299) throw BridgeHttpException(code, text.take(ERROR_SNIPPET_CHARS))
            return BridgeHttpResponse(code, text)
        } finally {
            connection.disconnect()
        }
    }

    /**
     * Legge la risposta con un tetto massimo.
     *
     * Prima era `readText()` senza limiti: un server impazzito, o un endpoint
     * sbagliato che restituisce un file, poteva far finire la memoria del
     * telefono. Qui oltre il limite si interrompe con un errore chiaro.
     */
    private fun readCapped(stream: java.io.InputStream): String {
        val reader = stream.bufferedReader(Charsets.UTF_8)
        val builder = StringBuilder()
        val chunk = CharArray(8 * 1024)
        reader.use {
            while (true) {
                val read = it.read(chunk)
                if (read <= 0) break
                if (builder.length + read > MAX_RESPONSE_CHARS) {
                    throw IOException(
                        "Risposta del server troppo grande (oltre ${MAX_RESPONSE_CHARS / 1024} KB)"
                    )
                }
                builder.appendRange(chunk, 0, read)
            }
        }
        return builder.toString()
    }

    companion object {
        private val HEADER_NAME = Regex("^[A-Za-z0-9-]+$")

        /** Tetto alla risposta: generoso per JSON, lontano dai limiti di memoria. */
        private const val MAX_RESPONSE_CHARS = 2 * 1024 * 1024

        /**
         * Quanto del corpo di errore finisce nel messaggio.
         *
         * Il messaggio viene mostrato all'utente e salvato nei record: non e' il
         * posto per riversarci la risposta completa del server.
         */
        private const val ERROR_SNIPPET_CHARS = 400
    }
}
