import { test } from 'node:test';
import assert from 'node:assert/strict';

test('web session survives pauses, completion, reload, and duplicate import', async () => {
  const values = new Map(); let failWrite = false;
  globalThis.localStorage = {
    getItem: k => values.get(k) ?? null,
    setItem: (k,v) => { if (failWrite) throw new Error('QuotaExceededError'); values.set(k,v); },
  };
  let callback, onError, stopped = false;
  Object.defineProperty(globalThis, 'navigator', { configurable:true, value:{ geolocation:{
    watchPosition: (cb,err) => { callback=cb; onError=err; return 1; },
    clearWatch: () => { stopped=true; },
  } } });
  const storage = await import('../src/storage.js');
  await storage.init(() => {}, () => {});
  await storage.start('walk');
  const t=Date.now();
  callback({coords:{latitude:55,longitude:37,accuracy:8},timestamp:t});
  assert.equal(storage.state.active.points.length,1);
  await storage.pause(); assert.equal(stopped,true);
  const before=storage.state.active.points.length;
  callback({coords:{latitude:55,longitude:37.0001,accuracy:8},timestamp:t+10000});
  assert.equal(storage.state.active.points.length,before);
  await storage.resume();
  // Use a real, increasing timestamp without waiting for a physical movement.
  storage.state.active.points[0].t=t-10000;
  storage.state.active.startedAt=t-10000;
  callback({coords:{latitude:55,longitude:37.0001,accuracy:8},timestamp:Date.now()});
  assert.equal(storage.state.active.points[1].break,true);
  await storage.finish();
  assert.equal(storage.state.active,null);
  assert.equal(storage.state.sessions.length,1);
  await storage.importSessions({version:1,sessions:storage.state.sessions});
  assert.equal(storage.state.sessions.length,1);
  await storage.init(() => {},() => {});
  assert.equal(storage.state.sessions[0].points.length,2);
  await storage.start('bike');
  onError({code:1});
  assert.ok(storage.state.active.pausedAt);
  await storage.resume();
  failWrite=true;
  await assert.rejects(storage.finish);
  assert.equal(storage.state.sessions.length,1);
  assert.ok(storage.state.active);
  assert.ok(storage.state.active.pausedAt);
});
