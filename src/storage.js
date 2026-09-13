import { Capacitor, registerPlugin } from '@capacitor/core';
import { MODES, acceptPoint, validateBackup } from './geo.js';
export const native = Capacitor.getPlatform() === 'ios';
const Tracker = registerPlugin('TerraTracker');
const KEY = 'terra-state-v1';
export let state = { sessions: [], active: null };
let watch = null, change = () => {}, error = () => {};
let healthy = false, durable = JSON.stringify(state);
function ensureHealthy() { if (!healthy) throw new Error('Хранилище недоступно. Перезапись данных отключена.'); }
function persist() {
  const next = JSON.stringify(state);
  try { localStorage.setItem(KEY, next); durable = next; }
  catch (e) {
    stopWatch(); state = JSON.parse(durable);
    if (state.active && !state.active.pausedAt) state.active.pausedAt = Date.now();
    change(); throw e;
  }
  change();
}
export async function init(onChange, onError) {
  change = onChange; error = onError;
  if (native) {
    state = await Tracker.snapshot();
    await Tracker.addListener('updated', data => { state = data; change(); });
    await Tracker.addListener('failure', data => error(data.message));
    document.addEventListener('visibilitychange', async () => {
      if (!document.hidden) { try { state = await Tracker.snapshot(); change(); } catch (e) { error(e.message); } }
    });
  } else {
    const saved = localStorage.getItem(KEY);
    if (saved) {
      const parsed = JSON.parse(saved);
      validateBackup({ version: 1, sessions: parsed.sessions });
      if (parsed.active) validateBackup({ version: 1, sessions: [{ ...parsed.active, endedAt: Date.now() }] });
      if (parsed.active?.pausedAt != null && (!Number.isFinite(parsed.active.pausedAt) || parsed.active.pausedAt < parsed.active.startedAt || parsed.active.pausedAt > Date.now())) throw new Error('Некорректное время паузы');
      state = parsed;
      durable = JSON.stringify(state);
      // A reloaded browser cannot account for the time when it wasn't recording.
      if (state.active && !state.active.pausedAt) {
        state.active.pausedAt = state.active.lastRecordedAt || state.active.points.at(-1)?.t || state.active.startedAt;
        persist();
      }
    }
  }
  healthy = true;
}
function beginWatch() {
  if (!navigator.geolocation) throw new Error('Геолокация недоступна. Открой приложение через HTTPS.');
  watch = navigator.geolocation.watchPosition(pos => {
    const s = state.active; if (!s || s.pausedAt) return;
    const p = { lat: pos.coords.latitude, lng: pos.coords.longitude, accuracy: pos.coords.accuracy, t: pos.timestamp };
    const prev = s.points.at(-1);
    if (p.t >= s.startedAt && p.t <= Date.now() + 5000 && acceptPoint(p, prev, s.mode)) {
      if (s.breakNext) { p.break = true; s.breakNext = false; }
      s.points.push(p);
      s.lastRecordedAt = p.t;
      try { persist(); } catch { stopWatch(); error('Память заполнена. Запись остановлена: экспортируй маршруты.'); }
    }
  }, e => {
    if (e.code === 1 && state.active) {
      stopWatch(); state.active.pausedAt = Date.now(); persist();
    }
    error(e.code === 1 ? 'Разреши доступ к геопозиции в настройках браузера.' : 'Нет точной геопозиции. Попробуй выйти на открытое место.');
  }, { enableHighAccuracy: true, maximumAge: 0, timeout: 20000 });
}
function stopWatch() { if (watch !== null) navigator.geolocation.clearWatch(watch); watch = null; }
export async function start(mode) {
  ensureHealthy();
  if (!MODES[mode]) throw new Error('Неизвестный режим');
  if (state.active) return;
  if (native) state = await Tracker.start({ mode });
  else {
    state.active = { id: crypto.randomUUID(), mode, startedAt: Date.now(), pausedMs: 0, pausedAt: null, points: [] };
    try { beginWatch(); persist(); } catch (e) { stopWatch(); state.active = null; throw e; }
  }
  change();
}
export async function pause() {
  ensureHealthy();
  if (native) state = await Tracker.pause();
  else { stopWatch(); state.active.pausedAt = Date.now(); state.active.lastRecordedAt = state.active.pausedAt; persist(); }
  change();
}
export async function resume() {
  ensureHealthy();
  if (native) state = await Tracker.resume();
  else {
    const s = state.active; s.pausedMs += Date.now() - s.pausedAt; s.pausedAt = null; s.breakNext = true;
    s.lastRecordedAt = Date.now();
    beginWatch(); persist();
  }
  change();
}
export async function finish() {
  ensureHealthy();
  if (native) state = await Tracker.finish();
  else {
    stopWatch(); const s = state.active;
    if (s.pausedAt) s.pausedMs += Date.now() - s.pausedAt;
    s.endedAt = Date.now(); s.pausedAt = null;
    state.sessions.push(s); state.active = null; persist();
  }
  change();
}
export async function importSessions(data) {
  ensureHealthy();
  const sessions = validateBackup(data);
  if (state.active) throw new Error('Сначала заверши прогулку');
  if (native) state = await Tracker.importSessions({ sessions });
  else {
    const all = new Map(state.sessions.map(s => [s.id, s]));
    for (const s of sessions) if (!all.has(s.id)) all.set(s.id, s);
    const next = { ...state, sessions: [...all.values()] };
    const serialized = JSON.stringify(next);
    localStorage.setItem(KEY, serialized); state = next; durable = serialized;
  }
  change();
}
export async function exportBackup() {
  if (native) return Tracker.exportBackup();
  const url = URL.createObjectURL(new Blob([JSON.stringify({ version: 1, sessions: state.sessions }, null, 2)], { type: 'application/json' }));
  const a = document.createElement('a'); a.href = url; a.download = `terra-${new Date().toISOString().slice(0, 10)}.json`; a.click();
  setTimeout(() => URL.revokeObjectURL(url), 5000);
}
