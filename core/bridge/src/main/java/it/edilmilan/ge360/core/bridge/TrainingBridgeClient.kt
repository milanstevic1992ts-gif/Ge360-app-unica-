package it.edilmilan.ge360.core.bridge

import org.json.JSONArray
import org.json.JSONObject
import java.text.Normalizer

data class TrainerScope(
    val type: String = "global",
    val id: String = "",
    val label: String = "Globale"
)

object TrainerScopes {
    val supported = setOf("global", "jobsite", "work_type", "tool", "material")

    fun validate(type: String, id: String): TrainerScope {
        val cleanType = type.trim().lowercase().ifBlank { "global" }
        require(cleanType in supported) { "Ambito Trainer non supportato" }
        val cleanId = id.trim().take(128)
        if (cleanType != "global") {
            require(cleanId.isNotBlank()) { "Seleziona il contesto per l'insegnamento" }
        }
        return TrainerScope(cleanType, if (cleanType == "global") "" else cleanId)
    }

    /** Deve restare identico a canonical_work_type_scope_id() del backend. */
    fun workTypeId(value: String): String {
        val normalized = Normalizer.normalize(value.trim().lowercase(), Normalizer.Form.NFD)
            .replace(Regex("\\p{Mn}+"), "")
        return normalized
            .replace(Regex("[^a-z0-9]+"), "-")
            .trim('-')
            .take(128)
    }
}

class TrainingBridgeClient(private val http: JsonHttpClient = JsonHttpClient()) {
    fun request(
        server: BridgeServiceConfig,
        trainer: TrainerDeviceConfig,
        path: String,
        method: String = "GET",
        body: JSONObject? = null,
        timeoutMs: Int = 10_000
    ): BridgeHttpResponse {
        require(trainer.deviceId.isNotBlank()) { "Identita' Trainer non disponibile" }
        require(trainer.trainerToken.isNotBlank()) { "Dispositivo Trainer non abbinato" }
        require(TrainerDeviceIdentity.validTrainerToken(trainer.trainerToken)) {
            "Credenziale Trainer non valida"
        }
        return http.request(
            config = server,
            path = trainingPath(path),
            method = method,
            body = body,
            timeoutMs = timeoutMs,
            extraHeaders = authenticatedHeaders(trainer)
        )
    }

    fun me(
        server: BridgeServiceConfig,
        trainer: TrainerDeviceConfig,
        timeoutMs: Int = 10_000
    ): JSONObject = request(server, trainer, "device/me", timeoutMs = timeoutMs).jsonObject()

    fun interpretVoice(
        server: BridgeServiceConfig,
        trainer: TrainerDeviceConfig,
        transcript: String,
        timeoutMs: Int = 10_000
    ): JSONObject {
        val clean = transcript.trim().take(12_000)
        require(clean.length >= 2) { "Trascrizione vocale troppo breve" }
        return request(
            server = server,
            trainer = trainer,
            path = "voice/interpret",
            method = "POST",
            body = JSONObject().put("transcript", clean),
            timeoutMs = timeoutMs
        ).jsonObject()
    }

    fun teach(
        server: BridgeServiceConfig,
        trainer: TrainerDeviceConfig,
        instruction: String,
        category: String = "general",
        note: String = "",
        tags: List<String> = emptyList(),
        sessionId: String = "",
        modelName: String = "",
        scopeType: String = "global",
        scopeId: String = "",
        scopeLabel: String = "",
        source: String = "android-trainer",
        timeoutMs: Int = 15_000
    ): JSONObject = request(
        server = server,
        trainer = trainer,
        path = "teach",
        method = "POST",
        body = TrainingPayloads.teach(
            instruction = instruction,
            category = category,
            note = note,
            tags = tags,
            sessionId = sessionId,
            modelName = modelName,
            scopeType = scopeType,
            scopeId = scopeId,
            scopeLabel = scopeLabel,
            source = source
        ),
        timeoutMs = timeoutMs
    ).jsonObject()

    fun correct(
        server: BridgeServiceConfig,
        trainer: TrainerDeviceConfig,
        userText: String,
        assistantText: String,
        correction: String,
        category: String = "general",
        note: String = "",
        tags: List<String> = emptyList(),
        sessionId: String = "",
        modelName: String = "",
        scopeType: String = "global",
        scopeId: String = "",
        scopeLabel: String = "",
        source: String = "android-trainer",
        timeoutMs: Int = 15_000
    ): JSONObject = request(
        server = server,
        trainer = trainer,
        path = "correct",
        method = "POST",
        body = TrainingPayloads.correction(
            userText = userText,
            assistantText = assistantText,
            correction = correction,
            category = category,
            note = note,
            tags = tags,
            sessionId = sessionId,
            modelName = modelName,
            scopeType = scopeType,
            scopeId = scopeId,
            scopeLabel = scopeLabel,
            source = source
        ),
        timeoutMs = timeoutMs
    ).jsonObject()

    fun rate(
        server: BridgeServiceConfig,
        trainer: TrainerDeviceConfig,
        positive: Boolean,
        userText: String,
        assistantText: String,
        category: String = "general",
        note: String = "",
        sessionId: String = "",
        modelName: String = "",
        scopeType: String = "global",
        scopeId: String = "",
        scopeLabel: String = "",
        source: String = "android-trainer",
        timeoutMs: Int = 15_000
    ): JSONObject = request(
        server = server,
        trainer = trainer,
        path = "rate",
        method = "POST",
        body = TrainingPayloads.rating(
            positive = positive,
            userText = userText,
            assistantText = assistantText,
            category = category,
            note = note,
            sessionId = sessionId,
            modelName = modelName,
            scopeType = scopeType,
            scopeId = scopeId,
            scopeLabel = scopeLabel,
            source = source
        ),
        timeoutMs = timeoutMs
    ).jsonObject()

    fun inbox(
        server: BridgeServiceConfig,
        trainer: TrainerDeviceConfig,
        status: String = "pending",
        limit: Int = 50,
        timeoutMs: Int = 15_000
    ): JSONArray {
        require(status.matches(Regex("^[a-z]{3,24}$"))) { "Stato inbox non valido" }
        require(limit in 1..200) { "Limite inbox non valido" }
        return request(
            server = server,
            trainer = trainer,
            path = "inbox?status=$status&limit=$limit",
            timeoutMs = timeoutMs
        ).jsonArray()
    }

    fun approve(
        server: BridgeServiceConfig,
        trainer: TrainerDeviceConfig,
        entryId: String,
        note: String = "",
        timeoutMs: Int = 15_000
    ): JSONObject = review(
        server = server,
        trainer = trainer,
        entryId = entryId,
        action = "approve",
        note = note,
        timeoutMs = timeoutMs
    )

    fun reject(
        server: BridgeServiceConfig,
        trainer: TrainerDeviceConfig,
        entryId: String,
        note: String = "",
        timeoutMs: Int = 15_000
    ): JSONObject = review(
        server = server,
        trainer = trainer,
        entryId = entryId,
        action = "reject",
        note = note,
        timeoutMs = timeoutMs
    )

    fun authenticatedHeaders(trainer: TrainerDeviceConfig): Map<String, String> = mapOf(
        "X-GE360-Device-Id" to trainer.deviceId,
        "X-GE360-Trainer-Token" to trainer.trainerToken
    )

    fun trainingPath(path: String): String {
        val clean = path.trim()
        require(clean.isNotBlank()) { "Percorso Training Bridge vuoto" }
        val relative = when {
            clean.startsWith("/api/v1/training/") -> clean.removePrefix("/api/v1/training/")
            clean == "/api/v1/training" -> ""
            clean.startsWith("api/v1/training/") -> clean.removePrefix("api/v1/training/")
            clean == "api/v1/training" -> ""
            clean.startsWith("/training/") -> clean.removePrefix("/training/")
            clean.startsWith("training/") -> clean.removePrefix("training/")
            else -> clean.trimStart('/')
        }
        require(relative.isNotBlank()) { "Percorso Training Bridge incompleto" }
        val pathOnly = relative.substringBefore('?')
        require(!pathOnly.split('/').any { it == ".." || it == "." }) { "Percorso Training Bridge non valido" }
        return "/api/v1/training/$relative"
    }

    private fun review(
        server: BridgeServiceConfig,
        trainer: TrainerDeviceConfig,
        entryId: String,
        action: String,
        note: String,
        timeoutMs: Int
    ): JSONObject {
        require(entryId.matches(Regex("^[A-Za-z0-9_-]{3,64}$"))) { "ID insegnamento non valido" }
        require(action == "approve" || action == "reject") { "Azione revisione non valida" }
        return request(
            server = server,
            trainer = trainer,
            path = "$entryId/$action",
            method = "POST",
            body = TrainingPayloads.review(note),
            timeoutMs = timeoutMs
        ).jsonObject()
    }
}

object TrainingPayloads {
    fun teach(
        instruction: String,
        category: String = "general",
        note: String = "",
        tags: List<String> = emptyList(),
        sessionId: String = "",
        modelName: String = "",
        scopeType: String = "global",
        scopeId: String = "",
        scopeLabel: String = "",
        source: String = "android-trainer"
    ): JSONObject = common(
        category, note, tags, sessionId, modelName, scopeType, scopeId, scopeLabel, source
    ).put("instruction", instruction.trim())

    fun correction(
        userText: String,
        assistantText: String,
        correction: String,
        category: String = "general",
        note: String = "",
        tags: List<String> = emptyList(),
        sessionId: String = "",
        modelName: String = "",
        scopeType: String = "global",
        scopeId: String = "",
        scopeLabel: String = "",
        source: String = "android-trainer"
    ): JSONObject = common(
        category, note, tags, sessionId, modelName, scopeType, scopeId, scopeLabel, source
    )
        .put("user_text", userText.trim())
        .put("assistant_text", assistantText.trim())
        .put("correction", correction.trim())

    fun rating(
        positive: Boolean,
        userText: String,
        assistantText: String,
        category: String = "general",
        note: String = "",
        sessionId: String = "",
        modelName: String = "",
        scopeType: String = "global",
        scopeId: String = "",
        scopeLabel: String = "",
        source: String = "android-trainer"
    ): JSONObject = common(
        category, note, emptyList(), sessionId, modelName, scopeType, scopeId, scopeLabel, source
    )
        .put("rating", if (positive) "positive" else "negative")
        .put("user_text", userText.trim())
        .put("assistant_text", assistantText.trim())

    fun review(note: String = ""): JSONObject = JSONObject()
        .put("reviewer", "owner")
        .put("note", note.trim())

    private fun common(
        category: String,
        note: String,
        tags: List<String>,
        sessionId: String,
        modelName: String,
        scopeType: String,
        scopeId: String,
        scopeLabel: String,
        source: String
    ): JSONObject {
        val cleanCategory = category.trim().ifBlank { "general" }.take(64)
        val cleanTags = tags.map { it.trim() }.filter { it.isNotBlank() }.distinct().take(20)
        val scope = TrainerScopes.validate(scopeType, scopeId)
        val cleanSource = source.trim().lowercase().take(64).ifBlank { "android-trainer" }
        require(cleanSource.matches(Regex("^[a-z0-9._-]+$"))) { "Origine Trainer non valida" }
        val metadata = JSONObject().put("source", cleanSource)
        val cleanLabel = scopeLabel.trim().take(160)
        if (cleanLabel.isNotBlank()) metadata.put("scope_label", cleanLabel)
        return JSONObject()
            .put("category", cleanCategory)
            .put("scope_type", scope.type)
            .put("scope_id", scope.id)
            .put("tags", JSONArray(cleanTags))
            .put("note", note.trim())
            .put("session_id", sessionId.trim())
            .put("device_id", "")
            .put("model_name", modelName.trim())
            .put("supersedes_id", "")
            .put("metadata", metadata)
    }
}
