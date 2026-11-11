import test from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';

const source = readFileSync(
  new URL('../feature/planimetrie/src/main/assets/rilievi/js/native-backup-bridge.js', import.meta.url),
  'utf8'
);
const { MIRROR_SCHEMA, validatePlans, referencedPhotoRecords,
  mergeOpenPlan3DPlans, byteChunks } =
  await import('data:text/javascript;base64,' + Buffer.from(source).toString('base64'));

function plan(id, photos = []) {
  return { id, name: id, walls: [{ id: 'w1', length: 2.5 }],
    notes: [{ text: 'Sopralluogo bagno' }], photos: photos.map(id => ({ id, mimeType: 'image/jpeg' })) };
}

test('schema esplicito e nessun riferimento alle credenziali del backend', () => {
  assert.equal(MIRROR_SCHEMA, 'ge360.openplan3d.mirror.v1');
  assert.equal(source.includes('apiKey'), false);
  assert.equal(source.includes('SETTINGS_KEY'), false);
});

test('merge conserva rilievi locali e aggiunge solo quelli nuovi', () => {
  const local = [plan('plan-1', ['photo-old']), plan('plan-2')];
  const incoming = [plan('plan-1', ['photo-other']), plan('plan-3', ['photo-new'])];
  const merged = mergeOpenPlan3DPlans(local, incoming, 'merge');
  assert.deepEqual(merged.map(x => x.id), ['plan-1', 'plan-2', 'plan-3']);
  assert.deepEqual(merged[0].photos.map(x => x.id), ['photo-old']);
  assert.equal(merged[2].notes[0].text, 'Sopralluogo bagno');
});

test('replace usa solo i rilievi del backup esplicitamente scelto', () => {
  const replaced = mergeOpenPlan3DPlans([plan('old')], [plan('new')], 'replace');
  assert.deepEqual(replaced.map(x => x.id), ['new']);
});

test('nessuna modalità sconosciuta può sovrascrivere rilievi', () => {
  assert.throws(() => mergeOpenPlan3DPlans([plan('old')], [plan('new')], 'overwrite'));
});

test('rifiuta UUID duplicati e fotografie duplicate tra piani', () => {
  assert.throws(() => validatePlans([plan('a'), plan('a')]));
  assert.throws(() => validatePlans([plan('a', ['photo1']), plan('b', ['photo1'])]));
});

test('rifiuta path traversal anche quando compare in un ID allegato', () => {
  assert.throws(() => validatePlans([plan('../x')]));
  assert.throws(() => validatePlans([plan('a', ['../../secret'])]));
  assert.throws(() => validatePlans([plan('a', ['photo/x'])]));
});

test('foto referenziate mantengono metadati MIME per ricreare Blob', () => {
  const p = plan('bathroom', ['photo-1', 'photo-2']);
  const refs = referencedPhotoRecords([p]);
  assert.deepEqual([...refs.keys()], ['photo-1', 'photo-2']);
  assert.equal(refs.get('photo-1').mimeType, 'image/jpeg');
});

test('taglio chunk binari senza byte perduti o duplicati', () => {
  const bytes = Uint8Array.from({length: 262145}, (_,i) => i%251);
  const chunks = byteChunks(bytes, 128*1024);
  assert.equal(chunks.length, 3);
  assert.deepEqual(chunks.map(c=>c.length), [131072,131072,1]);
  assert.deepEqual(Buffer.concat(chunks.map(c=>Buffer.from(c))), Buffer.from(bytes));
});

test('rifiuta dimensioni di chunk non sicure', () => {
  assert.throws(() => byteChunks(new Uint8Array(2), 128*1024+1));
  assert.throws(() => byteChunks(new Uint8Array(2), 0));
});
