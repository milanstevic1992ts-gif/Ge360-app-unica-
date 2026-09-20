package it.edilmilan.ge360.core.bridge

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.assertThrows
import org.junit.Test

class TrainerBridgeTest {
    @Test
    fun stableIdentityIsDeterministicPrivateAndBackendCompatible() {
        val raw = "0123456789abcdef"
        val first = TrainerDeviceIdentity.derive("it.edilmilan.ge360", raw)
        val second = TrainerDeviceIdentity.derive("it.edilmilan.ge360", raw)

        assertEquals(first, second)
        assertTrue(first!!.matches(Regex("^[A-Za-z0-9._:-]{3,128}$")))
        assertFalse(first.contains(raw))
        assertEquals(48, first.length)
        assertNotEquals(first, TrainerDeviceIdentity.derive("it.edilmilan.altro", raw))
    }

    @Test
    fun brokenLegacyAndroidIdIsNotUsed() {
        assertNull(TrainerDeviceIdentity.derive("it.edilmilan.ge360", "9774d56d682e549c"))
        assertNull(TrainerDeviceIdentity.derive("it.edilmilan.ge360", ""))
    }

    @Test
    fun trainerTokenValidationMatchesBackendContract() {
        assertTrue(TrainerDeviceIdentity.validTrainerToken("trn_ABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789_-abc"))
        assertFalse(TrainerDeviceIdentity.validTrainerToken("ordinary-api-token"))
        assertFalse(TrainerDeviceIdentity.validTrainerToken("trn_short"))
        assertFalse(TrainerDeviceIdentity.validTrainerToken("trn_bad token with spaces________________"))
    }

    @Test
    fun trainingClientAlwaysBuildsTrainerNamespaceAndHeaders() {
        val client = TrainingBridgeClient()
        val trainer = TrainerDeviceConfig(
            deviceId = "android-1234567890abcdef1234567890abcdef12345678",
            trainerToken = "trn_ABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789_-abc"
        )

        assertEquals("/api/v1/training/device/me", client.trainingPath("device/me"))
        assertEquals(
            "/api/v1/training/device/me",
            client.trainingPath("/api/v1/training/device/me")
        )
        assertEquals(
            "/api/v1/training/inbox?status=pending&limit=50",
            client.trainingPath("inbox?status=pending&limit=50")
        )
        assertEquals(
            mapOf(
                "X-GE360-Device-Id" to trainer.deviceId,
                "X-GE360-Trainer-Token" to trainer.trainerToken
            ),
            client.authenticatedHeaders(trainer)
        )
    }

    @Test
    fun trainingClientRejectsPathTraversal() {
        val client = TrainingBridgeClient()
        assertThrows(IllegalArgumentException::class.java) {
            client.trainingPath("../health")
        }
        assertThrows(IllegalArgumentException::class.java) {
            client.trainingPath("inbox/../health?status=pending")
        }
    }

    @Test
    fun teachPayloadMatchesBackendSnakeCaseContract() {
        val body = TrainingPayloads.teach(
            instruction = "  Usa sempre il primer quando serve  ",
            category = " cantiere ",
            note = " nota ",
            tags = listOf("posa", "posa", " primer "),
            sessionId = "session-1",
            modelName = "qwen3:8b"
        )

        assertEquals("Usa sempre il primer quando serve", body.getString("instruction"))
        assertEquals("cantiere", body.getString("category"))
        assertEquals("global", body.getString("scope_type"))
        assertEquals("", body.getString("scope_id"))
        assertEquals("session-1", body.getString("session_id"))
        assertEquals("qwen3:8b", body.getString("model_name"))
        assertEquals("", body.getString("device_id"))
        assertEquals("android-trainer", body.getJSONObject("metadata").getString("source"))
        assertEquals(2, body.getJSONArray("tags").length())
    }

    @Test
    fun contextualScopePayloadsMatchBackendPhase6Contract() {
        val jobsite = TrainingPayloads.teach(
            instruction = "Proteggi il pianerottolo",
            scopeType = "jobsite",
            scopeId = "job-pagano",
            scopeLabel = "Bagno Via Pagano"
        )
        assertEquals("jobsite", jobsite.getString("scope_type"))
        assertEquals("job-pagano", jobsite.getString("scope_id"))
        assertEquals("Bagno Via Pagano", jobsite.getJSONObject("metadata").getString("scope_label"))

        val workType = TrainingPayloads.correction(
            userText = "Come preparo un bagno?",
            assistantText = "Male",
            correction = "Segui il ciclo corretto",
            scopeType = "work_type",
            scopeId = "bagno"
        )
        assertEquals("work_type", workType.getString("scope_type"))
        assertEquals("bagno", workType.getString("scope_id"))

        val tool = TrainingPayloads.rating(
            positive = true,
            userText = "Come tratto il laser?",
            assistantText = "Controlla batteria e taratura",
            scopeType = "tool",
            scopeId = "tool-laser"
        )
        assertEquals("tool", tool.getString("scope_type"))
        assertEquals("tool-laser", tool.getString("scope_id"))

        val material = TrainingPayloads.teach(
            instruction = "Controlla la scheda tecnica",
            scopeType = "material",
            scopeId = "mat-primer"
        )
        assertEquals("material", material.getString("scope_type"))
        assertEquals("mat-primer", material.getString("scope_id"))
    }

    @Test
    fun contextualScopesRejectUnsupportedTypeOrMissingTarget() {
        assertThrows(IllegalArgumentException::class.java) {
            TrainingPayloads.teach(
                instruction = "Regola",
                scopeType = "jobsite",
                scopeId = ""
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            TrainingPayloads.teach(
                instruction = "Regola",
                scopeType = "unknown",
                scopeId = "abc"
            )
        }
    }

    @Test
    fun workTypeScopeIdsAreStableAndMatchBackendSlugContract() {
        assertEquals("bagno", TrainerScopes.workTypeId("Bagno"))
        assertEquals("opere-murarie", TrainerScopes.workTypeId("Opere murarie"))
        assertEquals("tinteggiatura", TrainerScopes.workTypeId("Tinteggiatura"))
        assertEquals("ristrutturazione", TrainerScopes.workTypeId("Ristrutturazione"))
    }

    @Test
    fun correctionRatingAndReviewPayloadsMatchBackendContract() {
        val correction = TrainingPayloads.correction(
            userText = " domanda ",
            assistantText = " risposta sbagliata ",
            correction = " risposta corretta ",
            sessionId = "s2"
        )
        assertEquals("domanda", correction.getString("user_text"))
        assertEquals("risposta sbagliata", correction.getString("assistant_text"))
        assertEquals("risposta corretta", correction.getString("correction"))
        assertEquals("s2", correction.getString("session_id"))

        val positive = TrainingPayloads.rating(
            positive = true,
            userText = "domanda",
            assistantText = "risposta"
        )
        assertEquals("positive", positive.getString("rating"))

        val negative = TrainingPayloads.rating(
            positive = false,
            userText = "domanda",
            assistantText = "risposta"
        )
        assertEquals("negative", negative.getString("rating"))

        val review = TrainingPayloads.review(" verificato sul campo ")
        assertEquals("owner", review.getString("reviewer"))
        assertEquals("verificato sul campo", review.getString("note"))
    }
}
