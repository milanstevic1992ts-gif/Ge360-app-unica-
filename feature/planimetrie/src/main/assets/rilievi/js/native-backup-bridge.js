/**
 * GE360 App Unica — OpenPlan3D ⇄ Android backup bridge.
 * No network/API key transfer. The HTTPS appassets-only WebMessage listener
 * is installed by AdvancedSurveyActivity, never by an external web page.
 */
export const MIRROR_SCHEMA = 'ge360.openplan3d.mirror.v1';
export const MAX_CHUNK_BYTES = 128 * 1024;

export function referencedPhotoRecords(plans) {
  const found = new Map();
  for (const plan of plans) for (const photo of (plan.photos || [])) {
    if (!photo || !/^[A-Za-z0-9_-]{1,100}$/.test(String(photo.id || ''))) {
      throw new Error('Foto con ID non valido nel rilievo');
    }
    if (found.has(photo.id)) throw new Error('Foto duplicata nei rilievi: ' + photo.id);
    found.set(photo.id, photo);
  }
  return found;
}

export function validatePlans(plans) {
  if (!Array.isArray(plans) || plans.length > 10000) throw new Error('Archivio rilievi non valido');
  const ids = new Set();
  for (const plan of plans) {
    if (!plan || !/^[A-Za-z0-9_-]{1,100}$/.test(String(plan.id || '')) || ids.has(plan.id)) {
      throw new Error('Rilievo duplicato o con identificativo non valido');
    }
    ids.add(plan.id);
  }
  referencedPhotoRecords(plans);
  return plans;
}

export function mergeOpenPlan3DPlans(current, incoming, mode) {
  validatePlans(current);
  validatePlans(incoming);
  if (mode === 'replace') return incoming.slice();
  if (mode !== 'merge') throw new Error('Modalità ripristino non valida');
  const existing = new Set(current.map(plan => plan.id));
  // In "Unisci" le modifiche già presenti sul dispositivo prevalgono.
  return current.concat(incoming.filter(plan => !existing.has(plan.id)));
}

export function byteChunks(bytes, chunkSize = MAX_CHUNK_BYTES) {
  if (!(bytes instanceof Uint8Array) || chunkSize < 1 || chunkSize > MAX_CHUNK_BYTES) {
    throw new Error('Parametri chunk non validi');
  }
  const chunks = [];
  for (let offset = 0; offset < bytes.length; offset += chunkSize) {
    chunks.push(bytes.subarray(offset, Math.min(offset + chunkSize, bytes.length)));
  }
  return chunks;
}

function toBase64(bytes) {
  let result = '';
  for (let i = 0; i < bytes.length; i += 8192) {
    result += String.fromCharCode(...bytes.subarray(i, i + 8192));
  }
  return btoa(result);
}
function fromBase64(encoded) {
  const binary = atob(encoded);
  const bytes = new Uint8Array(binary.length);
  for (let i = 0; i < binary.length; i++) bytes[i] = binary.charCodeAt(i);
  return bytes;
}
async function sha256(bytes) {
  if (!crypto.subtle) throw new Error('SHA-256 non disponibile sul dispositivo');
  const digest = new Uint8Array(await crypto.subtle.digest('SHA-256', bytes));
  return Array.from(digest, item => item.toString(16).padStart(2, '0')).join('');
}

export function createOpenPlan3DMirror({
  nativeObject, getPlans, setPlans, getExtras = () => ({}), setExtras = () => {},
  readPhoto, writePhoto, onStatus = () => {}
}) {
  if (!nativeObject || typeof nativeObject.postMessage !== 'function') return null;
  const waiting = new Map();
  let nextId = 0;
  let initialized = false;
  let suspended = true;
  let pendingSync = false;
  let syncing = false;
  let knownPhotos = new Set();
  let photoPipeline = Promise.resolve();

  nativeObject.onmessage = event => {
    try {
      const response = JSON.parse(event.data);
      if (response.type !== 'mirror-reply') return;
      const callback = waiting.get(response.requestId);
      if (!callback) return;
      waiting.delete(response.requestId);
      clearTimeout(callback.timer);
      if (response.ok) callback.resolve(response.result || {});
      else callback.reject(new Error(response.error || 'Backup mirror fallito'));
    } catch (err) { onStatus('Risposta mirror non valida: ' + err.message); }
  };

  function request(type, fields = {}) {
    return new Promise((resolve, reject) => {
      const requestId = 'm' + String(++nextId);
      const timer = setTimeout(() => {
        waiting.delete(requestId);
        reject(new Error('Risposta Android assente (' + type + ')'));
      }, 30000);
      waiting.set(requestId, { resolve, reject, timer });
      try {
        nativeObject.postMessage(JSON.stringify({ ...fields, type, requestId }));
      } catch (error) {
        clearTimeout(timer); waiting.delete(requestId); reject(error);
      }
    });
  }

  function markDirty() {
    try { nativeObject.postMessage(JSON.stringify({ type: 'mirror-dirty' })); }
    catch (_) { /* The next backup must refuse an unsynchronized mirror. */ }
  }

  async function sendLibrary() {
    const library = validatePlans(getPlans());
    const raw = JSON.stringify({ schema: MIRROR_SCHEMA, plans: library, extras: getExtras() });
    const bytes = new TextEncoder().encode(raw);
    if (bytes.length > 50 * 1024 * 1024) throw new Error('Libreria OpenPlan3D oltre 50 MB');
    const digest = await sha256(bytes);
    await request('mirror-library-begin');
    const chunks = byteChunks(bytes);
    for (let i = 0; i < chunks.length; i++) {
      await request('mirror-library-chunk', { index: i, base64: toBase64(chunks[i]) });
    }
    await request('mirror-library-end', { sha256: digest });
  }

  async function syncLibrary() {
    if (!initialized || suspended || syncing) return;
    syncing = true;
    try {
      while (pendingSync && !suspended) {
        pendingSync = false;
        await sendLibrary();
      }
      onStatus('Progetti OpenPlan3D sincronizzati con il backup Android');
    } catch (err) {
      pendingSync = true;
      onStatus('⚠ OpenPlan3D non ancora nel backup: ' + err.message);
    } finally { syncing = false; }
  }

  async function sendPhoto(id, blob) {
    if (knownPhotos.has(id)) return;
    if (!blob || typeof blob.arrayBuffer !== 'function') throw new Error('Foto non leggibile: ' + id);
    const bytes = new Uint8Array(await blob.arrayBuffer());
    if (bytes.length < 1 || bytes.length > 25 * 1024 * 1024) {
      throw new Error('Foto OpenPlan3D fuori limite (25 MB): ' + id);
    }
    await request('mirror-photo-begin', { id, size: bytes.length, sha256: await sha256(bytes) });
    const chunks = byteChunks(bytes);
    for (let i = 0; i < chunks.length; i++) {
      await request('mirror-photo-chunk', { id, index: i, base64: toBase64(chunks[i]) });
    }
    await request('mirror-photo-end', { id });
    knownPhotos.add(id);
  }

  function queuePhoto(id, blob) {
    photoPipeline = photoPipeline.catch(() => {}).then(() => sendPhoto(id, blob))
      .catch(err => onStatus('⚠ Foto OpenPlan3D non ancora copiata: ' + err.message));
    return photoPipeline;
  }

  async function synchronizeMissingPhotos() {
    const refs = referencedPhotoRecords(getPlans());
    for (const [id] of refs) {
      if (knownPhotos.has(id)) continue;
      const stored = await readPhoto(id);
      if (!stored || !stored.blob) {
        onStatus('⚠ Foto non leggibile in WebView: ' + id);
        continue;
      }
      await queuePhoto(id, stored.blob);
    }
  }

  async function readRestoredLibrary(length) {
    let text = '';
    for (let offset = 0; offset < length; offset += 48 * 1024) {
      const chunk = await request('mirror-restore-chunk', { offset });
      text += chunk.data;
    }
    if (text.length !== length) throw new Error('Lunghezza libreria ripristinata errata');
    const parsed = JSON.parse(text);
    if (parsed.schema !== MIRROR_SCHEMA) throw new Error('Schema restore non compatibile');
    validatePlans(parsed.plans);
    return parsed;
  }

  async function downloadRestoredPhoto(id, meta) {
    const result = await request('mirror-restore-photo-meta', { id });
    const bytes = [];
    let total = 0;
    for (let offset = 0; offset < result.size; offset += MAX_CHUNK_BYTES) {
      const count = Math.min(MAX_CHUNK_BYTES, result.size - offset);
      const part = await request('mirror-restore-photo-chunk', { id, offset, count });
      const block = fromBase64(part.base64);
      if (block.length !== count) throw new Error('Chunk foto incompleto: ' + id);
      bytes.push(block);
      total += block.length;
    }
    if (total !== result.size) throw new Error('Foto incompleta: ' + id);
    return new Blob(bytes, { type: meta.mimeType || 'image/jpeg' });
  }

  async function restoreFromNative() {
    const manifest = await request('mirror-restore-meta');
    const restored = await readRestoredLibrary(manifest.length);
    const incoming = restored.plans;
    const incomingPhotos = referencedPhotoRecords(incoming);
    const selected = mergeOpenPlan3DPlans(getPlans(), incoming, manifest.mode);
    const desired = referencedPhotoRecords(selected);

    // Tutte le foto devono essere scritte in IndexedDB prima di pubblicare
    // la nuova libreria nel localStorage, così non compaiono rimandi vuoti.
    for (const [id, meta] of desired) {
      const existing = await readPhoto(id);
      if (existing && existing.blob && manifest.mode === 'merge') continue;
      if (!incomingPhotos.has(id)) {
        if (existing && existing.blob) continue;
        throw new Error('Foto già locale mancante: ' + id);
      }
      const blob = await downloadRestoredPhoto(id, meta);
      await writePhoto({ ...meta, blob });
    }
    setExtras(restored.extras || {}, manifest.mode);
    setPlans(selected);
    await request('mirror-restore-complete');
    knownPhotos = new Set((await request('mirror-status', { planCount: selected.length })).knownPhotoIds);
    onStatus('✓ Rilievi avanzati e foto ripristinati nel WebView');
  }

  async function initialize() {
    if (initialized) return;
    initialized = true;
    try {
      const status = await request('mirror-status', { planCount: getPlans().length });
      knownPhotos = new Set(status.knownPhotoIds || []);
      if (status.pending) await restoreFromNative();
      suspended = false;
      pendingSync = true;
      await syncLibrary();
      await synchronizeMissingPhotos();
    } catch (err) {
      suspended = true; // A blocked restore must never be overwritten by a new mirror.
      onStatus('⚠ Ripristino/sincronizzazione OpenPlan3D: ' + err.message);
    }
  }

  function queueLibrary() {
    pendingSync = true;
    if (suspended) return;
    markDirty();
    // Yield to the WebView loop so the drawing UI stays responsive.
    Promise.resolve().then(syncLibrary);
  }

  return { initialize, queueLibrary, queuePhoto, syncLibrary };
}
