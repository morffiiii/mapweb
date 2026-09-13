import maplibregl from 'maplibre-gl';
import 'maplibre-gl/dist/maplibre-gl.css';
import './style.css';
import { MODES, RADIUS, connected, duration, routeDistance, revealedCells } from './geo.js';
import * as storage from './storage.js';

const paths = {
  compass: '<path d="m15.5 8.5-2 5-5 2 2-5z"/><circle cx="12" cy="12" r="9"/>',
  walk: '<circle cx="14" cy="4" r="2"/><path d="m7 21 3-7-1-5 4-2 3 5 4 1M5 12l4-3m1 5 5 3 1 5m-3-15-1 6"/>',
  bike: '<circle cx="5" cy="16" r="4"/><circle cx="19" cy="16" r="4"/><path d="m5 16 5-9 5 9H5m10 0-4-11H8m8-1h3l2 4"/>',
  car: '<path d="m4 10 2-6h12l2 6M4 17v3m16-3v3M3 10h18v7H3zM6 13h2m8 0h2"/>',
  moto: '<circle cx="5" cy="17" r="4"/><circle cx="19" cy="17" r="4"/><path d="m5 17 5-7h6l-2-6h4m-8 6-3-2H4m6 2 4 7h5l-3-7"/>',
  layers: '<path d="m12 3 10 6-10 6L2 9zm-9 11 9 5 9-5M3 18l9 5 9-5"/>',
  locate: '<circle cx="12" cy="12" r="6"/><circle cx="12" cy="12" r="2"/><path d="M12 2v4m0 12v4M2 12h4m12 0h4"/>',
  arrow: '<path d="M5 12h14m-5-5 5 5-5 5"/>',
  history: '<path d="M3 11a9 9 0 1 1 2 7M3 4v7h7m2-5v6l4 2"/>',
  chart: '<path d="M4 20V10m8 10V4m8 16v-7"/>',
  close: '<path d="m6 6 12 12M6 18 18 6"/>',
  pause: '<path d="M9 5v14M15 5v14"/>',
  play: '<path d="m8 5 11 7-11 7z"/>',
  stop: '<rect x="6" y="6" width="12" height="12" rx="2"/>',
  download: '<path d="M12 3v12m-5-5 5 5 5-5M4 16v5h16v-5"/>',
  upload: '<path d="M12 16V4m-5 5 5-5 5 5M4 16v5h16v-5"/>',
  check: '<path d="m5 12 4 4L19 6"/>',
  sun: '<circle cx="12" cy="12" r="4"/><path d="M12 2v2m0 16v2M2 12h2m16 0h2M5 5l1 1m12 12 1 1M5 19l1-1M18 6l1-1"/>',
  moon: '<path d="M20.7 13.1A9 9 0 0 1 10.9 3.3 9 9 0 1 0 20.7 13.1Z"/>',
};
const icon = (name, cls = '') => `<svg class="icon ${cls}" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.6" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true">${paths[name] || paths.compass}</svg>`;
const $ = s => document.querySelector(s);
let theme = document.documentElement.dataset.theme || 'dark';
const mapStyle = () => `https://tiles.openfreemap.org/styles/${theme === 'dark' ? 'dark' : 'liberty'}`;
let mode = 'walk', layer = 'all', demo = false, sheet = null, busy = false, cells = new Set(), pointCount = -1;
let lastLocation = null, follow = true, ready = false, demoTick = null;
let baselineKey = '', baselineCells = new Set(), newArea = 0;
const demoPoints = [[37.6004,55.7524],[37.6015,55.7529],[37.603,55.7534],[37.6044,55.754],[37.6048,55.7549],[37.6034,55.7556],[37.6018,55.7562],[37.6004,55.7568],[37.5989,55.7574],[37.5974,55.758],[37.5958,55.7587],[37.594,55.7592],[37.5923,55.7594],[37.5907,55.759],[37.5892,55.7585],[37.5877,55.758],[37.5862,55.7574],[37.5857,55.7566],[37.5864,55.7559],[37.588,55.7556],[37.5898,55.7552],[37.5916,55.7548],[37.5933,55.7543],[37.5951,55.7538],[37.5968,55.7533],[37.5985,55.7528]];
const sample = { id: 'demo', mode: 'walk', startedAt: Date.now() - 1560000, endedAt: Date.now(), pausedMs: 0, points: [] };
let toastTimer;
function toast(text) { $('#toast').textContent = text; $('#toast').classList.add('visible'); clearTimeout(toastTimer); toastTimer = setTimeout(() => $('#toast').classList.remove('visible'), 5500); }
const km = n => (n / 1000).toLocaleString('ru-RU', { maximumFractionDigits: 2 });
const area = n => (n * 400 / 1e6).toLocaleString('ru-RU', { maximumFractionDigits: 3 });
function clock(ms) { const sec = Math.floor(ms / 1000); return `${Math.floor(sec / 3600) ? `${Math.floor(sec / 3600)}:` : ''}${String(Math.floor(sec / 60) % 60).padStart(2, '0')}:${String(sec % 60).padStart(2, '0')}`; }
function sessions() { return demo ? [sample] : [...storage.state.sessions, ...(storage.state.active ? [storage.state.active] : [])]; }
function visibleSessions() { return sessions().filter(s => layer === 'all' || s.mode === layer); }
function escapeHtml(s) { return String(s).replace(/[&<>"']/g, c => ({ '&':'&amp;', '<':'&lt;', '>':'&gt;', '"':'&quot;', "'":'&#39;' }[c])); }

$('#app').innerHTML = `
  <header class="header"><a class="brand" href="/" aria-label="Terra — карта открытий"><span class="brand-mark">↗</span><span>terra</span></a><span class="brand-note">ЛИЧНЫЙ АТЛАС</span><div class="header-actions"><button id="theme-toggle" class="theme-toggle" aria-label="Включить светлую тему">${icon('sun')}</button><button class="avatar" data-sheet="profile" aria-label="Мой прогресс">${icon('chart')}</button></div></header>
  <nav class="rail" aria-label="Навигация"><button class="rail-button selected" data-sheet="map" aria-label="Карта">${icon('compass')}<span>Карта</span></button><button class="rail-button" data-sheet="history" aria-label="Маршруты">${icon('history')}<span>Маршруты</span></button><button class="rail-button" data-sheet="profile" aria-label="Прогресс">${icon('chart')}<span>Прогресс</span></button></nav>
  <aside class="overview"><div class="overview-heading"><h1>Моя карта</h1><span class="edition">01 / EXPLORE</span></div><div class="overview-stat"><strong id="total-area">0</strong><span>км²<br>открыто</span></div><div class="overview-footer"><span>Территория, где ты был</span><span class="estimate">≈</span></div></aside>
  <div class="map-top"><span id="map-status" class="map-pill"><span class="signal-dot"></span> Готов к записи</span><button id="demo" class="demo-button">${icon('play')} Демо-маршрут</button></div>
  <div id="coordinates" class="coordinates" aria-hidden="true"></div>
  <div class="map-tools"><button id="layers" aria-label="Слои карты" title="Слои карты">${icon('layers')}</button><button id="locate" aria-label="Моё местоположение" title="Моё местоположение">${icon('locate')}</button><div class="zoom"><button id="zoom-in" aria-label="Приблизить">+</button><button id="zoom-out" aria-label="Отдалить">−</button></div></div>
  <div class="map-caption"><span class="fog-dot"></span> Неизведанное <span class="revealed-dot"></span> Твои открытия</div>
  <section class="journey-card" aria-label="Управление прогулкой"><div class="card-heading"><div><span class="eyebrow" id="card-kicker">НОВЫЙ МАРШРУТ</span><h2 id="card-title">Способ передвижения</h2></div><span class="radius-tag">↔ 70 м</span></div><div class="modes" role="group" aria-label="Способ передвижения">${Object.entries(MODES).map(([key,m]) => `<button data-mode="${key}" aria-pressed="${key === mode}" class="mode ${key === mode ? 'active' : ''}">${icon(m.icon)}<span>${m.name}</span></button>`).join('')}</div><div id="trip-stats" class="trip-stats" hidden></div><div class="trip-actions"><button id="start" class="primary">${icon('walk')}<span>Начать прогулку</span>${icon('arrow')}</button><button id="finish" class="finish" hidden aria-label="Завершить маршрут">${icon('stop')}</button></div><p class="card-foot" id="recording-note"><span class="tiny-dot"></span> Полоса открытия · 35 м в каждую сторону</p></section>
  <div class="platform-note" id="platform-note">${icon('compass')} Веб-версия · запись при открытом экране</div>
  <div id="sheet-backdrop" hidden></div><section id="sheet" class="sheet" role="dialog" aria-modal="true" aria-labelledby="sheet-title" hidden></section>
  <input type="file" id="import-file" accept="application/json,.json" hidden />`;

const map = new maplibregl.Map({ container: 'map', style: mapStyle(), center: [37.596, 55.7556], zoom: 14.5, pitch: 0, bearing: -15, attributionControl: { compact: true }, maxPitch: 0, minZoom: 2, maxZoom: 19 });
map.touchZoomRotate.disableRotation();
map.dragRotate.disable();
map.on('dragstart', () => { follow = false; });
map.on('error', () => { if (!ready) $('#map-status').textContent = 'Карта загружается · нужен интернет'; });
map.on('style.load', () => {
  ready = true;
  if (theme === 'dark') {
    // Keep revealed streets legible, with distinct night colours for water and parks.
    for (const l of map.getStyle().layers) {
      if (l.type === 'background') map.setPaintProperty(l.id, 'background-color', '#171b22');
      if (l.type === 'fill') {
        if (/water/.test(l.id)) map.setPaintProperty(l.id, 'fill-color', '#213e55');
        else if (/park|landcover|landuse/.test(l.id)) map.setPaintProperty(l.id, 'fill-color', '#253a38');
        else if (/building/.test(l.id)) map.setPaintProperty(l.id, 'fill-color', '#38414e');
      }
      if (l.type === 'line' && /road|transportation/.test(l.id)) map.setPaintProperty(l.id, 'line-color', /casing/.test(l.id) ? '#242b35' : '#737988');
      if (l.type === 'symbol' && l.layout?.['text-field']) {
        map.setPaintProperty(l.id, 'text-color', '#c8cdd6');
        map.setPaintProperty(l.id, 'text-halo-color', '#171b22');
      }
    }
  }
  if (!map.getSource('routes')) map.addSource('routes', { type: 'geojson', data: { type:'FeatureCollection', features:[] } });
  if (!map.getLayer('routes-line')) map.addLayer({ id: 'routes-line', type: 'line', source: 'routes', paint: { 'line-color': theme === 'dark' ? '#ff8147' : '#e94f17', 'line-width':3, 'line-opacity':1 }, layout: { 'line-cap':'round', 'line-join':'round' } });
  updateRoutes(); drawFog();
});
function syncThemeUI() {
  document.documentElement.dataset.theme = theme;
  const label = theme === 'dark' ? 'Включить светлую тему' : 'Включить тёмную тему';
  $('#theme-toggle').innerHTML = icon(theme === 'dark' ? 'sun' : 'moon');
  $('#theme-toggle').setAttribute('aria-label', label);
  $('#theme-toggle').title = label;
  document.querySelector('meta[name="theme-color"]').content = theme === 'dark' ? '#17191d' : '#f8f8f8';
}
$('#theme-toggle').onclick = () => {
  theme = theme === 'dark' ? 'light' : 'dark';
  try { localStorage.setItem('terra-theme', theme); } catch { toast('Тема изменена, но сохранить выбор не удалось.'); }
  syncThemeUI(); ready = false; map.setStyle(mapStyle(), { diff: false }); drawFog();
};
syncThemeUI();
function updateCoordinates() {
  const c = map.getCenter();
  $('#coordinates').textContent = `${Math.abs(c.lat).toFixed(4)}° ${c.lat >= 0 ? 'N' : 'S'} / ${Math.abs(c.lng).toFixed(4)}° ${c.lng >= 0 ? 'E' : 'W'}`;
}
map.on('move', updateCoordinates); updateCoordinates();
const markerEl = document.createElement('div'); markerEl.className = 'position-marker'; markerEl.innerHTML = '<span></span>';
const marker = new maplibregl.Marker({ element: markerEl });
const canvas = $('#fog'), ctx = canvas.getContext('2d');
function drawFog() {
  const dpr = Math.min(window.devicePixelRatio || 1, 2), w = window.innerWidth, h = window.innerHeight;
  if (canvas.width !== w * dpr || canvas.height !== h * dpr) { canvas.width = w * dpr; canvas.height = h * dpr; }
  ctx.setTransform(dpr,0,0,dpr,0,0); ctx.clearRect(0,0,w,h);
  ctx.globalCompositeOperation = 'source-over'; ctx.fillStyle = theme === 'dark' ? 'rgba(24,27,33,0.84)' : 'rgba(222,225,230,0.80)'; ctx.fillRect(0,0,w,h);
  ctx.globalCompositeOperation = 'destination-out'; ctx.lineCap = 'round'; ctx.lineJoin = 'round';
  for (const s of visibleSessions()) {
    for (let i = 0; i < s.points.length; i++) {
      const p = s.points[i], pixel = map.project([p.lng,p.lat]);
      const r = Math.max(.4, RADIUS / (Math.cos(p.lat * Math.PI / 180) * 40075016.686 / (512 * 2 ** map.getZoom())));
      ctx.fillStyle = '#000'; ctx.strokeStyle = '#000'; ctx.shadowColor = '#000'; ctx.shadowBlur = 3;
      ctx.beginPath(); ctx.arc(pixel.x,pixel.y,r,0,Math.PI*2); ctx.fill();
      const prev = s.points[i - 1];
      if (prev && connected(prev,p)) {
        const a = map.project([prev.lng,prev.lat]); ctx.lineWidth = r * 2; ctx.beginPath(); ctx.moveTo(a.x,a.y); ctx.lineTo(pixel.x,pixel.y); ctx.stroke();
      }
    }
  }
  ctx.shadowBlur = 0; ctx.globalCompositeOperation = 'source-over';
}
map.on('render', drawFog);
window.addEventListener('resize', drawFog);
function updateRoutes() {
  if (!ready || !map.getSource('routes')) return;
  const features = [];
  for (const s of visibleSessions()) {
    let line = [];
    const flush = () => { if (line.length > 1) features.push({ type:'Feature', properties:{}, geometry:{ type:'LineString', coordinates:line } }); line = []; };
    s.points.forEach((p,i) => { if (i && !connected(s.points[i-1],p)) flush(); line.push([p.lng,p.lat]); }); flush();
  }
  map.getSource('routes').setData({ type:'FeatureCollection', features });
}
function refresh() {
  const all = sessions(), active = storage.state.active;
  const count = all.reduce((n,s) => n + s.points.length,0);
  const key = storage.state.sessions.map(s => `${s.id}:${s.points.length}`).join('|');
  if (key !== baselineKey) { baselineKey = key; baselineCells = revealedCells(storage.state.sessions); }
  if (count !== pointCount) { pointCount = count; cells = revealedCells(all); updateRoutes(); drawFog(); }
  newArea = [...cells].reduce((n,id) => n + (baselineCells.has(id) ? 0 : 1),0);
  $('#total-area').textContent = area(cells.size);
  const last = (demo ? sample : active)?.points.at(-1);
  if (last) {
    lastLocation = [last.lng,last.lat]; marker.setLngLat(lastLocation).addTo(map);
    if (follow) map.easeTo({ center:lastLocation, duration:700 });
  }
  $('.modes').hidden = Boolean(active);
  $('#trip-stats').hidden = !active;
  $('#finish').hidden = !active;
  $('#card-kicker').textContent = active ? (active.pausedAt ? 'ПАУЗА' : 'ЗАПИСЬ МАРШРУТА') : demo ? 'ПРЕДПРОСМОТР' : 'НОВЫЙ МАРШРУТ';
  $('#card-title').textContent = active ? (active.pausedAt ? 'Запись приостановлена' : MODES[active.mode].name) : demo ? 'Пример прогулки' : 'Способ передвижения';
  $('#start').innerHTML = active ? `${icon(active.pausedAt ? 'play' : 'pause')}<span>${active.pausedAt ? 'Продолжить' : 'Пауза'}</span>` : `${icon(MODES[mode].icon)}<span>${MODES[mode].action}</span>${icon('arrow')}`;
  $('#recording-note').innerHTML = `<span class="tiny-dot"></span> ${active ? (active.pausedAt ? 'Запись приостановлена' : active.points.length ? `GPS ±${Math.round(active.points.at(-1).accuracy)} м · ${storage.native ? 'можно блокировать экран' : 'держи страницу открытой'}` : 'Ищем точную геопозицию…') : 'Полоса открытия · 35 м в каждую сторону'}`;
  $('#map-status').innerHTML = `<span class="signal-dot ${active && !active.pausedAt ? 'pulse' : ''}"></span> ${demo ? 'Демо · не сохраняется' : active ? (active.pausedAt ? 'На паузе' : 'Идёт запись') : 'Готов к записи'}`;
  $('#demo').innerHTML = `${icon(demo ? 'close' : 'play')} ${demo ? 'Закрыть демо' : 'Демо-маршрут'}`;
  $('#demo').hidden = Boolean(active);
  updateTime();
}
function updateTime() {
  const a = storage.state.active; if (!a) return;
  $('#trip-stats').innerHTML = `<div><strong>${clock(duration(a))}</strong><span>в пути</span></div><div><strong>${km(routeDistance(a.points))}</strong><span>километров</span></div><div><strong>${area(newArea)}</strong><span>новых км² ≈</span></div>`;
}
setInterval(updateTime, 1000);
async function action(fn) {
  if (busy) return; busy = true;
  try { await fn(); } catch (e) { toast(e.message || 'Не удалось выполнить действие'); }
  finally { busy = false; refresh(); }
}
function stopDemo() { clearInterval(demoTick); demo = false; pointCount = -1; marker.remove(); refresh(); }
$('#demo').onclick = () => {
  if (demo) return stopDemo();
  demo = true; layer = 'all'; sample.points = []; pointCount = -1; follow = false;
  map.fitBounds([[37.5857,55.7524],[37.6048,55.7594]], { padding: window.innerWidth <= 720 ? { top:200,bottom:350,left:25,right:65 } : {top:140,bottom:100,left:430,right:120}, maxZoom:14.7, bearing:-15, duration:1000 });
  let i = 0;
  demoTick = setInterval(() => {
    const p = demoPoints[i]; sample.points.push({ lng:p[0],lat:p[1],accuracy:8,t:sample.startedAt + i*60000 });
    i++; refresh(); if (i === demoPoints.length) clearInterval(demoTick);
  }, 160);
  refresh();
};
document.querySelectorAll('[data-mode]').forEach(b => b.onclick = () => {
  mode = b.dataset.mode;
  document.querySelectorAll('[data-mode]').forEach(el => { el.classList.toggle('active', el === b); el.setAttribute('aria-pressed',String(el === b)); }); refresh();
});
$('#start').onclick = () => action(async () => {
  if (demo) stopDemo();
  if (storage.state.active) { if (storage.state.active.pausedAt) await storage.resume(); else await storage.pause(); }
  else { follow = true; layer = 'all'; await storage.start(mode); if (!storage.native) toast('В браузере запись работает только при открытой странице.'); }
});
$('#finish').onclick = () => action(async () => { await storage.finish(); toast('Маршрут сохранён. Ещё немного мира стало твоим.'); openSheet('history'); });
$('#zoom-in').onclick = () => map.zoomIn(); $('#zoom-out').onclick = () => map.zoomOut();
$('#locate').onclick = () => {
  if (lastLocation && storage.state.active) { follow = true; map.flyTo({ center:lastLocation, zoom:16 }); return; }
  if (!navigator.geolocation) return toast('Геолокация недоступна');
  navigator.geolocation.getCurrentPosition(p => { lastLocation = [p.coords.longitude,p.coords.latitude]; marker.setLngLat(lastLocation).addTo(map); follow = true; map.flyTo({ center:lastLocation, zoom:16 }); }, () => toast('Не удалось определить положение. Проверь разрешение на геолокацию.'), { enableHighAccuracy:true, timeout:15000, maximumAge:10000 });
};
let previousFocus;
function closeSheet() { sheet = null; $('#sheet').hidden = true; $('#sheet-backdrop').hidden = true; previousFocus?.focus(); }
function openSheet(type) {
  previousFocus = document.activeElement; sheet = type;
  if (type === 'map') return closeSheet();
  const title = { history:'Твои маршруты',profile:'Твой мир в цифрах',layers:'Что покажем на карте?' }[type];
  let body = '';
  if (type === 'history') {
    body = '<p class="sheet-intro">Каждая прогулка оставляет след.</p>';
    if (!storage.state.sessions.length) body += `<div class="empty">${icon('history')}<h3>Здесь начнётся твоя история</h3><p>Заверши первый маршрут — мы сохраним<br>его здесь вместе с твоими открытиями.</p><button class="secondary" id="back-map">Вернуться к карте ${icon('arrow')}</button></div>`;
    body += `<div class="route-list">${[...storage.state.sessions].reverse().map(s => `<button class="route-item" data-route="${escapeHtml(s.id)}"><span class="route-icon">${icon(MODES[s.mode].icon)}</span><span><strong>${MODES[s.mode].name}</strong><small>${new Date(s.startedAt).toLocaleString('ru-RU',{ day:'numeric',month:'long',hour:'2-digit',minute:'2-digit' })}</small></span><span class="route-numbers"><strong>${km(routeDistance(s.points))} км</strong><small>${clock(duration(s))}</small></span>${icon('arrow')}</button>`).join('')}</div>`;
  }
  if (type === 'profile') {
    const saved = storage.state.sessions, actualCells = revealedCells([...saved,...(storage.state.active ? [storage.state.active] : [])]);
    body = `<p class="sheet-intro">Не нужно покорять мир. Просто выходи гулять.</p><div class="profile-hero">${icon('sun')}<strong>${area(actualCells.size)} <span>км²</span></strong><p>мира уже открыто тобой · оценка</p></div><div class="profile-metrics"><div><strong>${saved.length}</strong><span>маршрутов</span></div><div><strong>${km(saved.reduce((n,s) => n + routeDistance(s.points),0))}</strong><span>километров</span></div></div><h3>Каждому пути — свой ритм</h3><div class="mode-totals">${Object.entries(MODES).map(([key,m]) => `<div>${icon(m.icon)}<span>${m.name}</span><strong>${km(saved.filter(s => s.mode === key).reduce((n,s) => n + routeDistance(s.points),0))} км</strong></div>`).join('')}</div><div class="backup"><h3>Твои данные — у тебя</h3><p>Маршруты хранятся на этом устройстве. Сохрани копию перед удалением приложения или очисткой браузера. Экспорт включает завершённые маршруты.</p><div><button id="export" class="secondary">${icon('download')} Экспорт</button><button id="import" class="secondary">${icon('upload')} Импорт</button></div></div>`;
  }
  if (type === 'layers') {
    body = `<p class="sheet-intro">Смотри все открытия или выбирай свой способ исследовать.</p><div class="layer-list">${[['all',{name:'Все открытия',icon:'layers'}],...Object.entries(MODES)].map(([key,m]) => `<button data-layer="${key}" class="${layer === key ? 'chosen' : ''}">${icon(m.icon)}<span>${m.name}</span>${layer === key ? icon('check') : ''}</button>`).join('')}</div><p class="sheet-note">Открывается полоса примерно 70 м шириной. Площадь оценивается по сетке 20 × 20 м; повторные проходы считаются один раз.</p>`;
  }
  $('#sheet').innerHTML = `<div class="sheet-handle"></div><div class="sheet-header"><div><span class="eyebrow">TERRA / ${type === 'profile' ? 'ПРОГРЕСС' : type === 'history' ? 'ИСТОРИЯ' : 'СЛОИ'}</span><h2 id="sheet-title">${title}</h2></div><button id="close-sheet" aria-label="Закрыть">${icon('close')}</button></div>${body}`;
  $('#sheet').hidden = false; $('#sheet-backdrop').hidden = false;
  $('#close-sheet').onclick = closeSheet; $('#close-sheet').focus();
  $('#back-map')?.addEventListener('click',closeSheet);
  $('#export')?.addEventListener('click',() => action(storage.exportBackup));
  $('#import')?.addEventListener('click',() => $('#import-file').click());
  document.querySelectorAll('[data-layer]').forEach(b => b.onclick = () => { layer = b.dataset.layer; updateRoutes(); drawFog(); closeSheet(); toast(layer === 'all' ? 'Показаны все открытия' : `Слой: ${MODES[layer].name}`); });
  document.querySelectorAll('[data-route]').forEach(b => b.onclick = () => {
    const s = storage.state.sessions.find(s => s.id === b.dataset.route); if (!s.points.length) return toast('В этом маршруте нет точек GPS');
    if (demo) stopDemo(); layer = 'all'; updateRoutes(); drawFog();
    const bounds = new maplibregl.LngLatBounds(); s.points.forEach(p => bounds.extend([p.lng,p.lat]));
    follow = false; closeSheet(); map.fitBounds(bounds,{ padding:{ top:120,bottom:320,left:40,right:40 },maxZoom:16 });
  });
}
$('#layers').onclick = () => openSheet('layers');
document.querySelectorAll('[data-sheet]').forEach(b => b.onclick = () => openSheet(b.dataset.sheet));
$('#sheet-backdrop').onclick = closeSheet;
document.addEventListener('keydown',e => {
  if (!sheet) return;
  if (e.key === 'Escape') closeSheet();
  if (e.key === 'Tab') {
    const buttons = [...$('#sheet').querySelectorAll('button')], first = buttons[0], last = buttons.at(-1);
    if (e.shiftKey && document.activeElement === first) { e.preventDefault(); last.focus(); }
    else if (!e.shiftKey && document.activeElement === last) { e.preventDefault(); first.focus(); }
  }
});
$('#import-file').onchange = e => action(async () => {
  const file = e.target.files[0]; if (!file) return;
  try {
    if (file.size > 25e6) throw new Error('Файл слишком большой. Максимум 25 МБ.');
    await storage.importSessions(JSON.parse(await file.text())); pointCount = -1; refresh(); openSheet('profile'); toast('Маршруты восстановлены');
  } finally { e.target.value = ''; }
});
document.addEventListener('visibilitychange', () => { if (!document.hidden && !storage.native && storage.state.active && !storage.state.active.pausedAt) toast('Пока страница была скрыта, запись могла прерываться.'); });
try { await storage.init(refresh,toast); }
catch { toast('Не удалось прочитать сохранение. Запись отключена, чтобы не перезаписать данные.'); $('#start').disabled = true; }
$('#platform-note').innerHTML = `${icon('compass')} ${storage.native ? 'На устройстве · фоновая запись iOS' : 'Веб-версия · запись при открытом экране'}`;
refresh();
