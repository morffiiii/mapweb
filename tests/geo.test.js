import { test } from 'node:test';
import assert from 'node:assert/strict';
import { distance, acceptPoint, routeDistance, duration, revealedCells, validateBackup } from '../src/geo.js';
const p = (lat, lng, t = 1000) => ({ lat, lng, t, accuracy: 8 });
test('distance uses metres and handles the date line', () => {
  assert.ok(Math.abs(distance(p(0,0),p(0,.001)) - 111.195) < .01);
  assert.ok(distance(p(0,179.999),p(0,-179.999)) < 223);
});
test('reject inaccurate coordinates, duplicate timestamps and GPS teleportation', () => {
  const a = p(55,37);
  assert.equal(acceptPoint({...a, accuracy:90},null,'walk'),false);
  assert.equal(acceptPoint({...a, lat:NaN},null,'walk'),false);
  assert.equal(acceptPoint(p(56,37,2000),a,'walk'),false);
  assert.equal(acceptPoint(p(55,37.0001,11000),a,'walk'),true);
  assert.equal(acceptPoint(p(55,37.0001,1000),a,'walk'),false);
});
test('pauses and gaps never join distinct route segments', () => {
  const a = p(0,0), b = p(0,.001,21000);
  assert.ok(routeDistance([a,b]) > 110);
  assert.equal(routeDistance([a,{...b,break:true}]),0);
  assert.equal(routeDistance([a,{...b,t:90000}]),0);
});
test('duration excludes pauses and stops at completion', () => {
  assert.equal(duration({startedAt:1000,pausedMs:2000,pausedAt:10000},50000),7000);
  assert.equal(duration({startedAt:1000,pausedMs:2000,endedAt:20000},50000),17000);
});
test('revisiting territory does not inflate discovered area', () => {
  const route = {points:[p(55,37),p(55,37.001,51000)]};
  const single = revealedCells([route]);
  assert.ok(single.size > 5);
  assert.deepEqual(revealedCells([route,route]),single);
  const gap = revealedCells([{points:[route.points[0],{...route.points[1],break:true}]}]);
  assert.ok(gap.size <= single.size);
});
test('backup rejects malformed modes, timestamps and duplicate IDs', () => {
  const session = {id:'a',mode:'walk',startedAt:1000,endedAt:10000,pausedMs:0,points:[p(55,37)]};
  assert.equal(validateBackup({version:1,sessions:[session]}).length,1);
  assert.throws(() => validateBackup({version:1,sessions:[{...session,mode:'bad'}]}));
  assert.throws(() => validateBackup({version:1,sessions:[{...session,endedAt:0}]}));
  assert.throws(() => validateBackup({version:1,sessions:[session,session]}));
  assert.throws(() => validateBackup({version:1,sessions:[{...session,points:[p(55,37,50000)]}]}));
});
