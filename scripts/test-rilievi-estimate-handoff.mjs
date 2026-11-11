import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import test from 'node:test';

const path = new URL(
  '../feature/planimetrie/src/main/assets/rilievi/js/estimate-handoff.js',
  import.meta.url
);
const source = readFileSync(path, 'utf8');
const { buildEstimateHandoff, ESTIMATE_HANDOFF_SCHEMA } = await import(
  'data:text/javascript;base64,' + Buffer.from(source).toString('base64')
);

const PLAN = {
  id: 'local-plan', name: 'Bagno cliente',
  backend: { planId: 'plan-server-001' }
};
const FINGERPRINT = 'abc123-1x';
function result() {
  return {
    status: 'PROCESSED', version: 2, fingerprint: FINGERPRINT,
    rooms: [{
      roomId: 'room-001', name: 'Bagno', quality: 'OK', confidence: 0.97,
      floorAreaM2: 5.5, ceilingAreaM2: 5.5, netWallAreaM2: 19.4,
      grossWallAreaM2: 22.0, paintAreaM2: 7.2, tilingAreaM2: 12.1,
      skirtingM: 4.3
    }]
  };
}
test('passaggio soltanto delle misure tecniche dal risultato calcolato', () => {
  const data = buildEstimateHandoff(PLAN, result(), FINGERPRINT);
  assert.equal(data.schema, ESTIMATE_HANDOFF_SCHEMA);
  assert.equal(data.type, 'estimate-draft');
  assert.equal(data.planId, 'plan-server-001');
  assert.equal(data.version, 2);
  assert.equal(data.rooms[0].floorAreaM2, 5.5);
  assert.equal(data.rooms[0].netWallAreaM2, 19.4);
  assert.equal(data.rooms[0].tilingAreaM2, 12.1);
  assert.equal(data.rooms[0].roomId, 'room-001');
  assert.ok(!('unitPrice' in data.rooms[0]));
  assert.ok(!('clientName' in data));
  assert.ok(!('apiKey' in data));
});
test('blocco del calcolo NON completato', () => {
  const raw = result(); raw.status = 'NEEDS_REVIEW';
  assert.throws(() => buildEstimateHandoff(PLAN, raw, FINGERPRINT), /Completa/);
});
test('blocco geometria cambiata dopo il calcolo', () => {
  assert.throws(() => buildEstimateHandoff(PLAN, result(), 'xyz-900'), /RICALCOLA/);
});
test('blocco di ambienti duplicati', () => {
  const raw = result(); raw.rooms.push({ ...raw.rooms[0] });
  assert.throws(() => buildEstimateHandoff(PLAN, raw, FINGERPRINT), /duplicato/);
});
test('rifiuta metrature negative e mancanti', () => {
  let raw = result(); raw.rooms[0].floorAreaM2 = -1;
  assert.throws(() => buildEstimateHandoff(PLAN, raw, FINGERPRINT), /Metratura/);
  raw = result(); delete raw.rooms[0].floorAreaM2;
  assert.throws(() => buildEstimateHandoff(PLAN, raw, FINGERPRINT), /pavimento/);
});
test('non usa prezzo o note da backend per creare importi', () => {
  const raw = result();
  raw.rooms[0].unitPrice = 400;
  raw.rooms[0].internalNote = 'NON TRASFERIRE';
  raw.rooms[0].priceEuro = 35000;
  const data = buildEstimateHandoff(PLAN, raw, FINGERPRINT);
  const text = JSON.stringify(data);
  assert.ok(!text.includes('NON TRASFERIRE'));
  assert.ok(!text.includes('35000'));
  assert.ok(!text.includes('400'));
});
