import test from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';

const source = readFileSync(
  new URL('../feature/planimetrie/src/main/assets/rilievi/js/native-backup-bridge.js', import.meta.url),
  'utf8'
);
const { MIRROR_SCHEMA, validatePlans, referencedPhotoRecords,
  mergeOpenPlan3DPlans, byteChunks, createOpenPlan3DMirror } =
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

test('modifiche prima della prima sincronizzazione bloccano il backup precedente', () => {
  const sent = [];
  const native = { postMessage: raw => sent.push(JSON.parse(raw)) };
  const mirror = createOpenPlan3DMirror({
    nativeObject: native,
    getPlans: () => [],
    setPlans: () => {},
    readPhoto: async () => null,
    writePhoto: async () => {}
  });
  mirror.queueLibrary();
  mirror.queueLibrary();
  assert.deepEqual(
    sent.filter(x => x.type === 'mirror-dirty').map(x => x.revision),
    [1, 2]
  );
});

test('una modifica durante il trasferimento non certifica una copia vecchia', async () => {
  const plans = [];
  const sent = [];
  let dirtyRevision = 0;
  let activeRevision = 0;
  let releaseFirst = null;
  let firstEndWasUnsafe = false;
  const native = {
    onmessage: null,
    postMessage(raw) {
      const message = JSON.parse(raw);
      sent.push(message);
      if (message.type === 'mirror-dirty') {
        dirtyRevision = Math.max(dirtyRevision, message.revision);
        return;
      }
      const reply = () => this.onmessage?.({ data: JSON.stringify({
        type: 'mirror-reply', requestId: message.requestId, ok: true,
        result: message.type === 'mirror-status'
          ? { pending: false, knownPhotoIds: [] } : {}
      }) });
      if (message.type === 'mirror-library-begin') {
        activeRevision = message.revision;
        dirtyRevision = Math.max(dirtyRevision, activeRevision);
        if (activeRevision === 1 && !releaseFirst) {
          releaseFirst = reply;
          return;
        }
      }
      if (message.type === 'mirror-library-end') {
        if (activeRevision === 1 && dirtyRevision < 2) firstEndWasUnsafe = true;
        if (activeRevision === dirtyRevision) dirtyRevision = 0;
      }
      Promise.resolve().then(reply);
    }
  };
  const mirror = createOpenPlan3DMirror({
    nativeObject: native, getPlans: () => plans, setPlans: () => {},
    readPhoto: async () => null, writePhoto: async () => {},
    onStatus: () => {}
  });
  assert.equal(await mirror.initialize(), true);
  plans.push(plan('one'));
  mirror.queueLibrary();
  for (let attempt = 0; attempt < 50 && !releaseFirst; attempt++) {
    await new Promise(resolve => setTimeout(resolve, 2));
  }
  assert.ok(releaseFirst, 'La revisione 1 deve essere in trasferimento');
  plans.push(plan('two'));
  mirror.queueLibrary();
  assert.equal(dirtyRevision, 2, 'Il nativo deve sapere che esiste una modifica più recente');
  releaseFirst();
  for (let attempt = 0; attempt < 50 &&
      sent.filter(x => x.type === 'mirror-library-end').length < 3; attempt++) {
    await new Promise(resolve => setTimeout(resolve, 2));
  }
  const revisions = sent.filter(x => x.type === 'mirror-library-begin').map(x => x.revision);
  assert.deepEqual(revisions, [0, 1, 2], 'La vecchia versione va seguita dall’ultima');
  assert.equal(firstEndWasUnsafe, false);
  assert.equal(dirtyRevision, 0, 'La copia può diventare pulita solo dopo la revisione 2');
});

test('restore resta pendente se una foto locale non torna nel mirror', async () => {
  const localPhoto = { id: 'local-photo', mimeType: 'image/jpeg' };
  const local = [plan('local-plan', [localPhoto.id])];
  const incoming = JSON.stringify({ schema: MIRROR_SCHEMA, plans: [plan('remote-plan')], extras: {} });
  const requests = [];
  const warnings = [];
  let plans = local;
  const native = {
    onmessage: null,
    postMessage(raw) {
      const msg = JSON.parse(raw);
      requests.push(msg.type);
      if (msg.type === 'mirror-dirty') return;
      const result = msg.type === 'mirror-status' ?
        { pending: true, mode: 'merge', knownPhotoIds: [] } :
        msg.type === 'mirror-restore-meta' ? { length: incoming.length, mode: 'merge' } :
        msg.type === 'mirror-restore-chunk' ? { data: incoming.slice(msg.offset, msg.offset + 48 * 1024) } : {};
      const rejected = msg.type === 'mirror-photo-begin';
      Promise.resolve().then(() => this.onmessage({ data: JSON.stringify({
        type: 'mirror-reply', requestId: msg.requestId,
        ok: !rejected, result,
        error: rejected ? 'Foto non scrivibile' : undefined
      }) }));
    }
  };
  const mirror = createOpenPlan3DMirror({
    nativeObject: native, getPlans: () => plans, setPlans: next => { plans = next; },
    readPhoto: async id => id === localPhoto.id
      ? { blob: new Blob([new Uint8Array([1, 2, 3])], { type: 'image/jpeg' }) } : null,
    writePhoto: async () => {},
    onStatus: message => warnings.push(message)
  });
  const ready = await mirror.initialize();
  assert.equal(ready, false, 'Un errore foto deve impedire la conferma del ripristino');
  assert.equal(mirror.isReady(), false);
  assert.equal(requests.includes('mirror-restore-complete'), false, 'Il flag restore non va cancellato');
  assert.equal(requests.includes('mirror-photo-begin'), true, 'La foto locale va sincronizzata');
  assert.ok(warnings.some(x => x.includes('non ancora copiata')));
});

