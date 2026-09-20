package it.edilmilan.ge360.core.bridge

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class VoiceTrainerBridgeTest {
    @Test
    fun voiceInterpretUsesProtectedTrainingNamespace() {
        val client = TrainingBridgeClient()
        assertEquals(
            "/api/v1/training/voice/interpret",
            client.trainingPath("voice/interpret")
        )
    }

    @Test
    fun voiceTrainingSourceIsAuditableWithoutChangingScopeContract() {
        val payload = TrainingPayloads.teach(
            instruction = "Controlla il laser prima di partire",
            scopeType = "tool",
            scopeId = "tool-laser",
            scopeLabel = "Laser",
            source = "android-voice-trainer"
        )
        assertEquals("tool", payload.getString("scope_type"))
        assertEquals("tool-laser", payload.getString("scope_id"))
        assertEquals(
            "android-voice-trainer",
            payload.getJSONObject("metadata").getString("source")
        )
        assertEquals("Laser", payload.getJSONObject("metadata").getString("scope_label"))
    }

    @Test
    fun voiceTrainingRejectsInvalidAuditSource() {
        assertThrows(IllegalArgumentException::class.java) {
            TrainingPayloads.teach(
                instruction = "Regola",
                source = "bad source with spaces"
            )
        }
    }
}
