import test from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';

const source = readFileSync(
  new URL('../feature/planimetrie/src/main/assets/rilievi/js/survey-mode.js', import.meta.url),
  'utf8'
);
const { SURVEY_MODE, normalizeSurveyMode, surveyModePolicy, freeSketchPolyline, modeLabel } =
  await import('data:text/javascript;base64,' + Buffer.from(source).toString('base64'));

test('vecchi rilievi senza modalità restano guidati', () => {
  assert.equal(normalizeSurveyMode(undefined), SURVEY_MODE.GUIDED);
  assert.equal(normalizeSurveyMode(null), SURVEY_MODE.GUIDED);
  assert.equal(normalizeSurveyMode('something-else'), SURVEY_MODE.GUIDED);
  assert.equal(normalizeSurveyMode('free'), SURVEY_MODE.FREE);
});

test('guidato mantiene misure automatiche e snap storico', () => {
  const p = surveyModePolicy('guided');
  assert.equal(p.free, false);
  assert.equal(p.autoRequestMeasures, true);
  assert.equal(p.straightenOnDraw, true);
  assert.equal(p.snapToExistingWalls, true);
  assert.equal(p.openWorksOnDone, true);
});

test('libero non chiede subito quote e non obbliga a lavori', () => {
  const p = surveyModePolicy('free');
  assert.equal(p.free, true);
  assert.equal(p.autoRequestMeasures, false);
  assert.equal(p.straightenOnDraw, false);
  assert.equal(p.snapToExistingWalls, false);
  assert.equal(p.openWorksOnDone, false);
  assert.match(modeLabel('free'), /LIBERO/);
});

test('non raddrizza né chiude in automatico un tratto libero', () => {
  const points = [{ x: 0, y: 0 }, { x: 24, y: 7 }, { x: 40, y: -3 }, { x: 7, y: 9 }];
  const before = JSON.stringify(points);
  const out = freeSketchPolyline(points, 1, p => p);
  assert.deepEqual(out, points);
  assert.equal(out.at(-1).x, 7);
  assert.equal(out.at(-1).y, 9);
  assert.equal(JSON.stringify(points), before);
});

test('limita vertici senza perdere gli estremi dello schizzo', () => {
  const points = Array.from({ length: 100 }, (_, i) => ({ x: i, y: i % 7 }));
  const out = freeSketchPolyline(points, 1, p => p);
  assert.equal(out.length, 48);
  assert.deepEqual(out[0], points[0]);
  assert.deepEqual(out.at(-1), points.at(-1));
  assert.notEqual(out[0], points[0]);
});

test('schizzo vuoto o segmento insufficiente non produce muri', () => {
  assert.deepEqual(freeSketchPolyline([], 1, p => p), []);
  assert.deepEqual(freeSketchPolyline([{ x: 3, y: 4 }], 1, p => p), []);
});
