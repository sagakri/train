// Milestones track the head at nodes and at every position where a tail clears
// a resource. Waiting is allowed only at intermediate nodes, never on an edge.
function motionModel(base) {
  const nodeIntervals = base.intervals.filter(i => i.kind === "node");
  const tail = nodeIntervals[0].end - nodeIntervals[0].start;
  const coords = nodeIntervals.map(i => i.start - base.start);
  const points = [...new Set([...coords, ...coords.map(t => t + tail)])].sort((a, b) => a - b);
  const variables = [];
  const constraints = [];
  const milestones = points.map(x => {
    const arrival = variables.push(x) - 1;
    const stop = coords.findIndex((c, i) => i > 0 && i < coords.length - 1 && Math.abs(c - x) < 1e-8);
    const departure = stop >= 0 ? variables.push(x) - 1 : arrival;
    if (departure !== arrival) constraints.push([arrival, departure, 0]);
    return { x, arrival, departure, node: stop >= 0 ? base.route[stop] : null };
  });
  for (let i = 1; i < milestones.length; i++) {
    const a = milestones[i - 1], b = milestones[i];
    const duration = b.x - a.x;
    constraints.push([a.departure, b.arrival, duration], [b.arrival, a.departure, -duration]);
  }
  const point = x => milestones.find(p => Math.abs(p.x - x) < 1e-8);
  const resources = base.intervals.map(interval => {
    const start = point(interval.start - base.start);
    const end = point(interval.end - base.start);
    return { ...interval, startVar: interval.kind === "edge" ? start.departure : start.arrival,
      endVar: tail === 0 && interval.kind === "node" ? end.departure : end.arrival };
  });
  return { base, variables, constraints, milestones, resources, coords,
    startVar: point(0).arrival, arrivalVar: point(coords.at(-1)).arrival,
    completeVar: point(coords.at(-1) + tail).arrival };
}

function materializeMotion(model, times) {
  const { base, milestones, coords } = model;
  const point = x => milestones.find(p => Math.abs(p.x - x) < 1e-8);
  const intervals = model.resources.map(r => ({ ...r, start: times[r.startVar], end: times[r.endVar] }));
  const dwells = milestones.filter(p => p.node && times[p.departure] > times[p.arrival] + 1e-7)
    .map(p => ({ node: p.node, start: times[p.arrival], end: times[p.departure] }));
  const segments = coords.slice(0, -1).map((x, i) => ({
    from: base.route[i], to: base.route[i + 1],
    start: times[point(x).departure], end: times[point(coords[i + 1]).arrival]
  }));
  const events = intervals.map(r => ({ trainId: base.trainId, resource: r.resource,
    event: r.kind === "edge" ? "занятие / освобождение участка" : "проход / освобождение узла",
    head: r.start, tail: r.end }));
  events.push(...dwells.map(d => ({ trainId: base.trainId, resource: d.node, event: "ожидание у стрелки", head: d.start, tail: d.end })));
  return { ...base, intervals, events, dwells, segments, start: times[model.startVar],
    arrival: times[model.arrivalVar], complete: times[model.completeVar] };
}

function fixedWaitSchedule(base, waits) {
  const model = motionModel(base);
  const timedWaits = waits.map(w => ({ ...w, x: model.coords[base.route.indexOf(w.node)] }))
    .filter(w => Number.isFinite(w.x) && w.x > 0 && w.x < model.coords.at(-1));
  const times = model.variables.map(x => base.start + x + timedWaits.reduce((sum, w) => sum + (w.x < x - 1e-8 ? w.duration : 0), 0));
  for (const p of model.milestones) {
    if (p.departure !== p.arrival) times[p.departure] += timedWaits.filter(w => Math.abs(w.x - p.x) < 1e-8).reduce((sum, w) => sum + w.duration, 0);
  }
  return materializeMotion(model, times);
}

async function solveWithStops(bases, gap, ceiling = Infinity) {
  const models = bases.map(motionModel);
  let count = 0;
  const offsets = models.map(m => { const offset = count; count += m.variables.length; return offset; });
  const constraints = models.flatMap((m, i) => m.constraints.map(([a, b, w]) => [a + offsets[i], b + offsets[i], w]));
  const resources = models.flatMap((m, i) => m.resources.map(r => ({ ...r, startVar: r.startVar + offsets[i], endVar: r.endVar + offsets[i] })));
  const pairs = [];
  for (let i = 0; i < resources.length; i++) for (let j = i + 1; j < resources.length; j++) {
    if (resources[i].trainId !== resources[j].trainId && resources[i].resource === resources[j].resource) pairs.push([resources[i], resources[j]]);
  }
  let cursor = 0;
  let bestTimes = models.flatMap(m => {
    const times = m.variables.map(t => t + cursor);
    cursor += m.base.complete + gap;
    return times;
  });
  const completion = times => Math.max(...models.map((m, i) => times[offsets[i] + m.completeVar]));
  let best = completion(bestTimes);
  if (best >= ceiling) { best = ceiling; bestTimes = null; }
  const stack = [[]];
  let visited = 0;
  while (stack.length) {
    if (++visited % 512 === 0) await new Promise(resolve => setTimeout(resolve, 0));
    const branch = stack.pop();
    const edges = [...constraints, ...branch];
    const times = Array(count).fill(0);
    let feasible = true;
    for (let pass = 0; pass < count; pass++) {
      let changed = false;
      for (const [a, b, w] of edges) {
        if (times[b] + 1e-8 < times[a] + w) { times[b] = times[a] + w; changed = true; }
      }
      if (!changed) break;
      if (pass === count - 1 || completion(times) >= best - 1e-7) { feasible = false; break; }
    }
    if (!feasible || completion(times) >= best - 1e-7) continue;
    const conflict = pairs.find(([a, b]) => Math.max(times[a.startVar], times[b.startVar]) <
      Math.min(times[a.endVar] + gap, times[b.endVar] + gap) - 1e-7);
    if (!conflict) { bestTimes = times; best = completion(times); continue; }
    const [a, b] = conflict;
    stack.push([...branch, [b.endVar, a.startVar, gap]]);
    stack.push([...branch, [a.endVar, b.startVar, gap]]);
  }
  return bestTimes ? { total: best, schedules: models.map((m, i) => materializeMotion(m, bestTimes.slice(offsets[i], offsets[i] + m.variables.length))) } : null;
}

if (typeof module !== "undefined") module.exports = { motionModel, materializeMotion, fixedWaitSchedule, solveWithStops };
