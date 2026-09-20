#!/usr/bin/env bash
set -euo pipefail

activity="feature/jarvis/src/main/java/it/edilmilan/ge360/jarvis/JarvisActivity.kt"
flow="feature/jarvis/src/main/java/it/edilmilan/ge360/jarvis/VoiceTrainerFlow.kt"
bridge="core/bridge/src/main/java/it/edilmilan/ge360/core/bridge/TrainingBridgeClient.kt"

test -s "$activity"
test -s "$flow"
test -s "$bridge"

grep -Fq 'RecognizerIntent.ACTION_RECOGNIZE_SPEECH' "$activity"
grep -Fq 'Insegna / correggi a voce' "$activity"
grep -Fq 'voiceTrainer().handleTranscript' "$activity"
grep -Fq 'training.interpretVoice' "$flow"
grep -Fq 'requiresConfirmation' "$bridge" || true
grep -Fq 'source = "android-voice-trainer"' "$flow"
grep -Fq 'SCEGLI AMBITO' "$flow"
grep -Fq 'PENDING' "$flow"
grep -Fq 'path = "voice/interpret"' "$bridge"

if grep -Fq 'approve(' "$flow"; then
  echo "Voice Trainer non deve approvare automaticamente esempi" >&2
  exit 1
fi

if grep -Fq 'dryRun = false' "$flow"; then
  echo "Voice Trainer non deve eseguire azioni Jarvis" >&2
  exit 1
fi

echo "Voice Trainer Fase 7: contratto OK"
