/* Each shared resource imposes one of two precedence constraints on departures.
   Branch on a conflict and propagate lower bounds; positive cycles are infeasible. */
async function solveDepartures(bases, gap, ceiling = Infinity) {
  const n = bases.length;
  const pairs = [];
  for (let i = 0; i < n; i++) {
    for (let j = i + 1; j < n; j++) {
      for (const a of bases[i].intervals) {
        for (const b of bases[j].intervals) {
          if (a.resource === b.resource) pairs.push({ i, j, a, b });
        }
      }
    }
  }
  let cursor = 0;
  let bestStarts = bases.map(base => {
    const start = cursor;
    cursor += base.complete + gap;
    return start;
  });
  let best = Math.max(...bases.map((base, i) => base.complete + bestStarts[i]));
  if (best >= ceiling) { best = ceiling; bestStarts = null; }
  const stack = [[]];
  let visited = 0;
  while (stack.length) {
    if (++visited % 2048 === 0) await new Promise(resolve => setTimeout(resolve, 0));
    const constraints = stack.pop();
    const starts = Array(n).fill(0);
    let feasible = true;
    for (let pass = 0; pass < n; pass++) {
      let changed = false;
      for (const [i, j, offset] of constraints) {
        if (starts[j] + 1e-8 < starts[i] + offset) {
          starts[j] = starts[i] + offset;
          changed = true;
        }
      }
      if (!changed) break;
      if (pass === n - 1) feasible = false;
    }
    if (!feasible) continue;
    const total = Math.max(...bases.map((base, i) => base.complete + starts[i]));
    if (total >= best - 1e-7) continue;
    const conflict = pairs.find(({ i, j, a, b }) =>
      Math.max(starts[i] + a.start, starts[j] + b.start) <
      Math.min(starts[i] + a.end + gap, starts[j] + b.end + gap) - 1e-7);
    if (!conflict) {
      best = total;
      bestStarts = starts;
      continue;
    }
    const { i, j, a, b } = conflict;
    stack.push([...constraints, [j, i, b.end + gap - a.start]]);
    stack.push([...constraints, [i, j, a.end + gap - b.start]]);
  }
  return bestStarts ? { starts: bestStarts, total: best, visited } : null;
}

if (typeof module !== "undefined") module.exports = { solveDepartures };
