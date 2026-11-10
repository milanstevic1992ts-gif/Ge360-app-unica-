/**
 * Contratto GE360: un risultato OpenPlan3D verificabile -> bozza Preventivi.
 * Non trasferisce prezzi, accordi economici o dati privati del browser.
 */
export const ESTIMATE_HANDOFF_SCHEMA = 'ge360.rilievi.to_estimate.v1';

const MEASURE_FIELDS = [
  'floorAreaM2', 'ceilingAreaM2', 'grossWallAreaM2', 'netWallAreaM2',
  'paintAreaM2', 'tilingAreaM2', 'skirtingM'
];
const VALID_ID = /^[A-Za-z0-9_-]{1,64}$/;
const VALID_FINGERPRINT = /^[a-z0-9]+-[a-z0-9]+$/;

export function buildEstimateHandoff(plan, result, currentFingerprint) {
  if (!plan || !result || result.status !== 'PROCESSED') {
    throw new Error('Completa il calcolo professionale sul Debian prima del trasferimento');
  }
  const planId = (plan.backend && plan.backend.planId) || plan.id;
  if (!VALID_ID.test(String(planId || ''))) throw new Error('Identificativo rilievo non valido');
  if (!Number.isInteger(result.version) || result.version < 1) {
    throw new Error('Il calcolo non possiede una versione valida');
  }
  if (typeof result.fingerprint !== 'string' ||
      !VALID_FINGERPRINT.test(result.fingerprint) || result.fingerprint.length > 100 ||
      result.fingerprint !== currentFingerprint) {
    throw new Error('Il rilievo è cambiato dopo il calcolo: premi RICALCOLA');
  }
  if (!Array.isArray(result.rooms) || result.rooms.length < 1 || result.rooms.length > 50) {
    throw new Error('Il calcolo non contiene ambienti importabili');
  }
  const known = new Set();
  const rooms = result.rooms.map((room, i) => {
    const fallback = 'Ambiente ' + (i + 1);
    if (!room || !VALID_ID.test(String(room.roomId || '')) || known.has(room.roomId)) {
      throw new Error('Ambiente duplicato o privo di identificativo');
    }
    known.add(room.roomId);
    const metrics = {};
    for (const key of MEASURE_FIELDS) {
      if (room[key] == null) continue;
      if (typeof room[key] !== 'number' ||
          !Number.isFinite(room[key]) || room[key] < 0 || room[key] > 100000) {
        throw new Error('Metratura non valida in ' + String(room.name || fallback));
      }
      if (room[key] > 0) metrics[key] = room[key];
    }
    if (!Number.isFinite(metrics.floorAreaM2) || metrics.floorAreaM2 <= 0) {
      throw new Error('Manca la superficie del pavimento per ' + String(room.name || fallback));
    }
    return {
      roomId: room.roomId,
      name: String(room.name || fallback).slice(0, 100),
      quality: String(room.quality || '').slice(0, 40),
      confidence: Number.isFinite(room.confidence) ? room.confidence : null,
      ...metrics
    };
  });
  return {
    type: 'estimate-draft',
    schema: ESTIMATE_HANDOFF_SCHEMA,
    planId,
    planName: String(plan.name || 'Rilievo').slice(0, 100),
    version: result.version,
    status: result.status,
    fingerprint: result.fingerprint,
    rooms
  };
}
