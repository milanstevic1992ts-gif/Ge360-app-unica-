/**
 * GE360 modalità rilievo:
 * - guided: comportamento storico (raddrizzamento, aggancio, misura dopo disegno)
 * - free: schizzo come tracciato, misure/appunti facoltativi.
 *
 * I vecchi rilievi senza surveyMode rimangono GUIDATI.
 * Le pareti disegnate prima del cambio modalità non vengono mutate.
 */
export const SURVEY_MODE = Object.freeze({ FREE: 'free', GUIDED: 'guided' });

export function normalizeSurveyMode(value) {
  return value === SURVEY_MODE.FREE ? SURVEY_MODE.FREE : SURVEY_MODE.GUIDED;
}

export function surveyModePolicy(value) {
  const free = normalizeSurveyMode(value) === SURVEY_MODE.FREE;
  return {
    free,
    autoRequestMeasures: !free,
    straightenOnDraw: !free,
    snapToExistingWalls: !free,
    openWorksOnDone: !free
  };
}

/**
 * Semplifica SOLO per mantenere un numero gestibile di pareti.
 * Non raddrizza, non aggancia angoli e non forza la chiusura dello schizzo.
 * Preserva il primo e l'ultimo punto, anche quando sono vicini.
 */
export function freeSketchPolyline(points, zoom, rdp) {
  if (!Array.isArray(points) || points.length < 2) return [];
  const z = Number.isFinite(zoom) && zoom > 0 ? zoom : 1;
  let reduced = points;
  for (let eps = 3; eps <= 27; eps += 3) {
    reduced = rdp(points, eps / z);
    if (reduced.length <= 48) break;
  }
  if (reduced.length > 48) {
    const source = reduced;
    reduced = Array.from({ length: 48 }, (_, i) =>
      source[Math.round(i * (source.length - 1) / 47)]
    );
  }
  return reduced.map(p => ({ x: p.x, y: p.y }));
}

export function modeLabel(value) {
  return normalizeSurveyMode(value) === SURVEY_MODE.FREE
    ? '✏ RILIEVO LIBERO · Misure facoltative'
    : '📐 RILIEVO GUIDATO · Quote passo passo';
}
