export const MODES = {
  walk: { name: 'Пешком', action: 'Начать прогулку', icon: 'walk', maxSpeed: 12 },
  bike: { name: 'Велосипед', action: 'Начать поездку', icon: 'bike', maxSpeed: 35 },
  car: { name: 'Авто', action: 'Начать поездку', icon: 'car', maxSpeed: 90 },
  moto: { name: 'Мото', action: 'Начать поездку', icon: 'moto', maxSpeed: 90 },
  other: { name: 'Другое', action: 'Начать маршрут', icon: 'compass', maxSpeed: 100 },
};
export const RADIUS = 35;
const rad = Math.PI / 180;
export function distance(a, b) {
  const dlat = (b.lat - a.lat) * rad, dlon = (b.lng - a.lng) * rad;
  const h = Math.sin(dlat / 2) ** 2 + Math.cos(a.lat * rad) * Math.cos(b.lat * rad) * Math.sin(dlon / 2) ** 2;
  return 6371000 * 2 * Math.atan2(Math.sqrt(h), Math.sqrt(Math.max(0, 1 - h)));
}
export function validPoint(p) {
  return p && ['lat', 'lng', 't', 'accuracy'].every(k => Number.isFinite(p[k])) && Math.abs(p.lat) <= 85 && Math.abs(p.lng) <= 180 && p.accuracy >= 0 && p.accuracy <= 60;
}
export function acceptPoint(p, prev, mode) {
  if (!validPoint(p)) return false;
  if (!prev) return true;
  const dt = (p.t - prev.t) / 1000;
  return dt > 0 && distance(prev, p) >= 4 && distance(prev, p) / dt <= MODES[mode].maxSpeed;
}
export function connected(a, b) { return !b.break && b.t - a.t <= 60000 && b.t > a.t; }
export function routeDistance(points) {
  return points.reduce((sum, p, i) => sum + (i && connected(points[i - 1], p) ? distance(points[i - 1], p) : 0), 0);
}
export function duration(session, now = Date.now()) {
  return Math.max(0, (session.endedAt || session.pausedAt || now) - session.startedAt - (session.pausedMs || 0));
}
// Equal-height latitude rows; longitude cell widths vary with latitude.
// Cell union estimates discovered area without counting revisited places twice.
export function revealedCells(sessions) {
  const cells = new Set(), step = 20, deg = 111195;
  function stamp(lat, lng) {
    const row = Math.floor(lat * deg / step);
    for (let dy = -2; dy <= 2; dy++) {
      const y = row + dy, cy = (y + .5) * step / deg;
      const dxdeg = step / (deg * Math.cos(cy * rad));
      const col = Math.floor(lng / dxdeg);
      for (let dx = -2; dx <= 2; dx++) {
        const x = col + dx;
        if (distance({ lat, lng }, { lat: cy, lng: (x + .5) * dxdeg }) <= RADIUS) cells.add(`${y}:${x}`);
      }
    }
  }
  for (const s of sessions) for (let i = 0; i < s.points.length; i++) {
    const p = s.points[i], prev = s.points[i - 1]; stamp(p.lat, p.lng);
    if (prev && connected(prev, p)) {
      const n = Math.ceil(distance(prev, p) / 15);
      let dlng = p.lng - prev.lng;
      if (dlng > 180) dlng -= 360; if (dlng < -180) dlng += 360;
      for (let j = 1; j < n; j++) stamp(prev.lat + (p.lat - prev.lat) * j / n, ((prev.lng + dlng * j / n + 540) % 360) - 180);
    }
  }
  return cells;
}
export function validateBackup(data) {
  if (data?.version !== 1 || !Array.isArray(data.sessions) || data.sessions.length > 10000) throw new Error('Неподдерживаемый файл резервной копии');
  let count = 0;
  const ids = new Set();
  for (const s of data.sessions) {
    if (typeof s.id !== 'string' || ids.has(s.id) || !MODES[s.mode] || !Number.isFinite(s.startedAt) || !Number.isFinite(s.endedAt) || s.endedAt < s.startedAt || !Array.isArray(s.points) || !Number.isFinite(s.pausedMs) || s.pausedMs < 0 || s.pausedMs > s.endedAt - s.startedAt) throw new Error('Некорректный маршрут');
    ids.add(s.id); count += s.points.length;
    if (count > 200000) throw new Error('Слишком большой файл');
    s.points.forEach((p, i) => {
      if (!validPoint(p) || p.t < s.startedAt || p.t > s.endedAt || (i && !acceptPoint(p, s.points[i - 1], s.mode))) throw new Error('Некорректные координаты');
    });
  }
  return data.sessions;
}
