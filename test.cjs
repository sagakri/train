const assert = require('node:assert/strict');
const fs = require('node:fs');
const vm = require('node:vm');
const { solveDepartures } = require('./optimizer.js');
const { solveWithStops, fixedWaitSchedule } = require('./optimizer-waits.js');

async function main() {
  // Exhaustive integer-time oracle for a small disjunctive scheduling problem.
  const bases = [
    { complete: 8, intervals: [{ resource: 'X', start: 0, end: 3 }, { resource: 'Y', start: 5, end: 8 }] },
    { complete: 7, intervals: [{ resource: 'Y', start: 0, end: 2 }, { resource: 'X', start: 4, end: 7 }] },
    { complete: 5, intervals: [{ resource: 'X', start: 1, end: 5 }] }
  ];
  for (const gap of [0, 1, 2]) {
    let oracle = Infinity;
    for (let a = 0; a <= 25; a++) for (let b = 0; b <= 25; b++) for (let c = 0; c <= 25; c++) {
      const starts = [a, b, c];
      let valid = true;
      for (let i = 0; i < 3; i++) for (let j = i + 1; j < 3; j++) {
        for (const x of bases[i].intervals) for (const y of bases[j].intervals) {
          if (x.resource === y.resource && Math.max(x.start + starts[i], y.start + starts[j]) <
            Math.min(x.end + starts[i] + gap, y.end + starts[j] + gap)) valid = false;
        }
      }
      if (valid) oracle = Math.min(oracle, Math.max(...bases.map((base, i) => base.complete + starts[i])));
    }
    assert.equal((await solveDepartures(bases, gap)).total, oracle);
  }
  const routingControl = { value: 'continuous' };
  const context = vm.createContext({ console, structuredClone, setTimeout, solveDepartures, solveWithStops, fixedWaitSchedule,
    document: { getElementById: id => id === 'routingMode' ? routingControl : ({}), querySelector: () => ({}) } });
  const source = fs.readFileSync('script.js', 'utf8').split('document.getElementById("manualBtn").addEventListener')[0];
  vm.runInContext(source, context);
  const result = await vm.runInContext(`(async () => {
    const result = await calculateVariants();
    return { count: result.combinations, variants: result.variants.map(v => ({
      total: v.total, conflicts: findConflicts(v.schedules).length,
      starts: v.schedules.map(s => s.start), arrivals: v.schedules.map(s => s.arrival), routes: v.schedules.map(s => s.route.join('-'))
    })), edges: state.edges.length, paths: state.trains.map(t => ({ train: t.id, routes: findSimplePaths(t.from,t.to).map(r => r.join('-')) })),
    shortest: routeLength(findSimplePaths('A','C')[0]), manual: manualPlan().conflicts.length };
  })()`, context);
  assert.equal(result.edges, 12);
  assert.ok(result.paths[1].routes.includes('C-S-F-D'));
  assert.ok(result.paths[3].routes.includes('B-G-E-A'));
  assert.equal(result.count, result.paths.reduce((count, item) => count * item.routes.length, 1));
  assert.equal(result.shortest, 50);
  assert.ok(result.manual > 0);
  assert.ok(result.variants.length > 0);
  result.variants.forEach(v => { assert.equal(v.conflicts, 0); assert.equal(Math.min(...v.starts), 0); });
  const editCheck = vm.runInContext(`(() => {
    const edge = edgeById('E-G');
    edge.enabled = true;
    edge.km = null;
    const missingLengthRejected = validateInputs().length > 0;
    edge.km = 3;
    const routes = findSimplePaths('B', 'A');
    return { missingLengthRejected, valid: validateInputs().length === 0,
      direct: routes.some(r => r.join('-') === 'B-G-E-A'), distance: routeLength(['B','G','E','A']) };
  })()`, context);
  assert.equal(editCheck.missingLengthRejected, true);
  assert.equal(editCheck.valid, true);
  assert.equal(editCheck.direct, true);
  assert.equal(editCheck.distance, 49);
  vm.runInContext("edgeById('E-G').km = 2", context);
  routingControl.value = 'stops';
  const stopped = await vm.runInContext(`(async () => {
    const result = await calculateVariants();
    for (const variant of result.variants) {
      if (findConflicts(variant.schedules).length) throw new Error('Conflict in waiting plan');
      for (const schedule of variant.schedules) {
        const waits = schedule.dwells.map(d => ({ node: d.node, duration: d.end - d.start }));
        const rebuilt = fixedWaitSchedule(buildSchedule(schedule.train, schedule.route, schedule.start), waits);
        if (Math.abs(rebuilt.complete - schedule.complete) > 1e-6) throw new Error('Tail mismatch');
        rebuilt.intervals.forEach((interval, i) => {
          if (Math.abs(interval.start - schedule.intervals[i].start) > 1e-6 || Math.abs(interval.end - schedule.intervals[i].end) > 1e-6) throw new Error('Interval mismatch');
        });
      }
    }
    return { count: result.combinations, bounded: result.bounded, total: result.variants[0].total,
      trains: result.variants[0].schedules.map(s => ({ id: s.trainId, route: s.route, start: s.start, arrival: s.arrival, complete: s.complete, dwells: s.dwells })) };
  })()`, context);
  assert.ok(stopped.total <= result.variants[0].total + 1e-7);
  const occupancy = vm.runInContext(`(() => {
    const train = state.trains[0];
    const a = buildSchedule(train, ['E','F'], 0);
    const bTrain = { ...train, id: 99 };
    return [
      findConflicts([a, buildSchedule(bTrain, ['E','F'], 1)]).some(c => c.resource === 'E-F'),
      findConflicts([a, buildSchedule(bTrain, ['F','E'], 1)]).some(c => c.resource === 'E-F'),
      findConflicts([a, buildSchedule(bTrain, ['F','E'], a.complete + state.safetyGap)]).length === 0
    ];
  })()`, context);
  assert.ok(occupancy.every(Boolean));
  const longTail = vm.runInContext(`(() => {
    const train = { ...state.trains[0], speed: 36, cars: 150, tanks: 0 };
    const schedule = fixedWaitSchedule(buildSchedule(train, ['E','O','S'], 0), [{ node: 'O', duration: 100 }]);
    return { startClear: schedule.intervals.find(i => i.resource === 'E').end,
      edgeClear: schedule.intervals.find(i => i.resource === 'E-O').end, complete: schedule.complete };
  })()`, context);
  assert.equal(longTail.startClear, 400);
  assert.equal(longTail.edgeClear, 600);
  assert.equal(longTail.complete, 800);
  const stopOracle = await vm.runInContext(`(async () => {
    const train = { ...state.trains[0], speed: 7200, cars: 100, tanks: 0 };
    const bases = [buildSchedule(train, ['E','F','O'], 0), buildSchedule({ ...train, id: 99 }, ['O','F','E'], 0)];
    let oracle = Infinity;
    for (let a = 0; a <= 8; a++) for (let b = 0; b <= 8; b++) {
      for (let wa = 0; wa <= 8; wa++) for (let wb = 0; wb <= 8; wb++) {
        const schedules = [a, b].map((start, i) => fixedWaitSchedule(
          buildSchedule(bases[i].train, bases[i].route, start), [{ node: 'F', duration: i === 0 ? wa : wb }]));
        if (!findConflicts(schedules).length) oracle = Math.min(oracle, Math.max(...schedules.map(s => s.complete)));
      }
    }
    return { oracle, actual: (await solveWithStops(bases, state.safetyGap)).total };
  })()`, context);
  assert.equal(stopOracle.actual, stopOracle.oracle);
  const zeroLength = await vm.runInContext(`(async () => {
    const saved = JSON.stringify(state.trains.map(t => [t.cars, t.tanks, t.speed, t.from, t.to]));
    state.lengthMode = 'withoutCars';
    const first = state.trains[0];
    const a = buildSchedule(first, ['E','F'], 0);
    const second = { ...first, id: 99 };
    const sameDirectionBlocked = findConflicts([a, buildSchedule(second, ['E','F'], 1)]).some(c => c.resource === 'E-F');
    const oppositeBlocked = findConflicts([a, buildSchedule(second, ['F','E'], 1)]).some(c => c.resource === 'E-F');
    const gapBlocked = findConflicts([a, buildSchedule(second, ['F','E'], a.complete + state.safetyGap / 2)]).length > 0;
    const afterGapAllowed = findConflicts([a, buildSchedule(second, ['F','E'], a.complete + state.safetyGap)]).length === 0;
    const result = await calculateVariants();
    const noConflicts = result.variants.every(v => findConflicts(v.schedules).length === 0);
    const zeroTail = state.trains.every(t => trainLength(t) === 0) && a.complete === a.arrival;
    state.lengthMode = 'withCars';
    const restored = await calculateVariants();
    return { total: result.variants[0].total, restored: restored.variants[0].total, zeroTail,
      sameDirectionBlocked, oppositeBlocked, gapBlocked, afterGapAllowed, noConflicts,
      saved: saved === JSON.stringify(state.trains.map(t => [t.cars, t.tanks, t.speed, t.from, t.to])) };
  })()`, context);
  assert.equal(zeroLength.total, 6000);
  assert.equal(zeroLength.restored, 6072);
  for (const key of ['zeroTail', 'sameDirectionBlocked', 'oppositeBlocked', 'gapBlocked', 'afterGapAllowed', 'noConflicts', 'saved']) assert.equal(zeroLength[key], true, key);
  console.log('Length modes:', JSON.stringify(zeroLength));
  console.log(JSON.stringify(result, null, 2));
  console.log('With node waiting:', JSON.stringify(stopped, null, 2));
  console.log('Oracle and network checks passed.');
}
main().catch(error => { console.error(error); process.exitCode = 1; });
