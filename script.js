const nodes = {
  A: { x: 120, y: 70 },
  D: { x: 640, y: 70 },
  B: { x: 120, y: 490 },
  C: { x: 640, y: 490 },
  O: { x: 380, y: 280 },
  E: { x: 265, y: 187 },
  S: { x: 495, y: 373 },
  F: { x: 495, y: 187 },
  G: { x: 265, y: 373 }
};

const initialEdges = [
  { id: "A-E", a: "A", b: "E", km: 23, enabled: true, certain: true },
  { id: "E-O", a: "E", b: "O", km: 2, enabled: true, certain: true },
  { id: "O-S", a: "O", b: "S", km: 2, enabled: true, certain: true },
  { id: "S-C", a: "S", b: "C", km: 23, enabled: true, certain: true },
  { id: "D-F", a: "D", b: "F", km: 23, enabled: true, certain: true },
  { id: "F-O", a: "F", b: "O", km: 2, enabled: true, certain: true },
  { id: "O-G", a: "O", b: "G", km: 2, enabled: true, certain: true },
  { id: "G-B", a: "G", b: "B", km: 23, enabled: true, certain: true },
  { id: "E-F", a: "E", b: "F", km: 2, enabled: true, certain: true },
  { id: "E-G", a: "E", b: "G", km: 2, enabled: true, certain: true },
  { id: "F-S", a: "F", b: "S", km: 2, enabled: true, certain: true },
  { id: "G-S", a: "G", b: "S", km: 2, enabled: true, certain: true }
];

const initialTrains = [
  { id: 1, from: "D", to: "B", speed: 50, cars: 10, tanks: 2, color: "var(--t1)", start: "00:00:00", routeMode: "auto", route: "D-F-O-G-B" },
  { id: 2, from: "C", to: "D", speed: 45, cars: 15, tanks: 5, color: "var(--t2)", start: "00:00:00", routeMode: "auto", route: "C-S-O-F-D" },
  { id: 3, from: "A", to: "C", speed: 30, cars: 0, tanks: 30, color: "var(--t3)", start: "00:00:00", routeMode: "auto", route: "A-E-O-S-C" },
  { id: 4, from: "B", to: "A", speed: 40, cars: 20, tanks: 10, color: "var(--t4)", start: "00:00:00", routeMode: "auto", route: "B-G-O-E-A" }
];

let state = {
  lengthMode: "withCars",
  edges: structuredClone(initialEdges),
  trains: structuredClone(initialTrains),
  carLength: 20,
  tankLength: 20,
  safetyGap: 1,
  legendText: "ц = цистерны, в = вагоны",
  selectedPlan: null,
  currentTime: 0,
  playing: false,
  lastFrame: 0
};
const comparisonCache = new Map();

const edgeControls = document.getElementById("edgeControls");
const trainControls = document.getElementById("trainControls");
const derivedDistances = document.getElementById("derivedDistances");
const edgeLayer = document.getElementById("edgeLayer");
const labelLayer = document.getElementById("labelLayer");
const nodeLayer = document.getElementById("nodeLayer");
const trainLayer = document.getElementById("trainLayer");
const eventsBody = document.querySelector("#eventsTable tbody");
const warnings = document.getElementById("warnings");
const results = document.getElementById("results");
const overallStatus = document.getElementById("overallStatus");
const timeChart = document.getElementById("timeChart");
const clock = document.getElementById("clock");
const timeSlider = document.getElementById("timeSlider");

function edgeKey(a, b) {
  return [a, b].sort().join("-");
}

function edgeById(id) {
  return state.edges.find(edge => edge.id === id);
}

function activeEdges() {
  return state.edges.filter(edge => edge.enabled);
}

function graph() {
  const g = {};
  Object.keys(nodes).forEach(node => g[node] = []);
  activeEdges().forEach(edge => {
    g[edge.a].push({ node: edge.b, edge });
    g[edge.b].push({ node: edge.a, edge });
  });
  return g;
}

function findSimplePaths(from, to) {
  const g = graph();
  const paths = [];
  function dfs(node, path) {
    if (node === to) {
      paths.push([...path]);
      return;
    }
    for (const next of g[node] || []) {
      if (path.includes(next.node)) continue;
      dfs(next.node, [...path, next.node]);
    }
  }
  dfs(from, [from]);
  return paths.sort((a, b) => routeLength(a) - routeLength(b));
}

function routeLength(route) {
  let sum = 0;
  for (let i = 0; i < route.length - 1; i++) {
    const edge = state.edges.find(item => edgeKey(item.a, item.b) === edgeKey(route[i], route[i + 1]) && item.enabled);
    if (!edge) return Infinity;
    sum += Number(edge.km);
  }
  return sum;
}

function routeIsValid(route) {
  if (!Array.isArray(route) || route.length < 2) return false;
  for (let i = 0; i < route.length - 1; i++) {
    const edge = state.edges.find(item => edgeKey(item.a, item.b) === edgeKey(route[i], route[i + 1]) && item.enabled);
    if (!edge || Number(edge.km) <= 0) return false;
  }
  return true;
}

function parseTime(text) {
  const parts = String(text).trim().split(":").map(Number);
  if (parts.length === 1 && Number.isFinite(parts[0])) return Math.max(0, parts[0]);
  if (parts.length !== 3 || parts.some(part => !Number.isFinite(part) || part < 0)) return NaN;
  return parts[0] * 3600 + parts[1] * 60 + parts[2];
}

function formatTime(seconds) {
  if (!Number.isFinite(seconds)) return "-";
  const value = Math.max(0, Math.round(seconds));
  const h = Math.floor(value / 3600);
  const m = Math.floor((value % 3600) / 60);
  const s = value % 60;
  return [h, m, s].map(item => String(item).padStart(2, "0")).join(":");
}

function trainLength(train) {
  if (state.lengthMode === "withoutCars") return 0;
  return train.cars * state.carLength + train.tanks * state.tankLength;
}

function lengthModeLabel(mode = state.lengthMode) {
  return mode === "withoutCars" ? "Без вагонов" : "С вагонами";
}

function comparisonKey() {
  return JSON.stringify({ edges: state.edges, trains: state.trains.map(({ from, to, speed, cars, tanks }) => ({ from, to, speed, cars, tanks })),
    carLength: state.carLength, tankLength: state.tankLength, safetyGap: state.safetyGap,
    routing: document.getElementById("routingMode").value });
}

function renderComparison() {
  const entry = comparisonCache.get(comparisonKey()) || {};
  document.getElementById("comparisonWith").textContent = entry.withCars === undefined ? "Не рассчитано" : formatTime(entry.withCars);
  document.getElementById("comparisonWithout").textContent = entry.withoutCars === undefined ? "Не рассчитано" : formatTime(entry.withoutCars);
  document.getElementById("metricMode").textContent = `Активный режим: ${lengthModeLabel().toLowerCase()}`;
  document.getElementById("withCarsBtn").setAttribute("aria-pressed", String(state.lengthMode !== "withoutCars"));
  document.getElementById("withoutCarsBtn").setAttribute("aria-pressed", String(state.lengthMode === "withoutCars"));
}

function changeLengthMode(mode) {
  syncStateFromControls();
  if (state.lengthMode === mode) return;
  state.lengthMode = mode;
  state.trains.forEach(train => train.waits = []);
  renderAll(false);
}

function buildSchedule(train, route, startSeconds) {
  const intervals = [];
  const events = [];
  let t = startSeconds;
  const tailDelay = 3.6 * trainLength(train) / train.speed;
  intervals.push({ trainId: train.id, kind: "node", resource: route[0], start: t, end: t + tailDelay, label: `стрелка ${route[0]}` });
  events.push({ trainId: train.id, resource: route[0], event: "отправление / освобождение узла", head: t, tail: t + tailDelay });

  for (let i = 0; i < route.length - 1; i++) {
    const a = route[i];
    const b = route[i + 1];
    const edge = state.edges.find(item => edgeKey(item.a, item.b) === edgeKey(a, b) && item.enabled);
    if (!edge) throw new Error(`Нет включённого ребра ${a}-${b}`);
    const travel = 3600 * Number(edge.km) / train.speed;
    const edgeStart = t;
    const edgeEndHead = t + travel;
    const edgeEndTail = edgeEndHead + tailDelay;
    intervals.push({ trainId: train.id, kind: "edge", resource: edgeKey(a, b), start: edgeStart, end: edgeEndTail, label: `участок ${a}-${b}` });
    events.push({ trainId: train.id, resource: `${a}-${b}`, event: "проход участка", head: edgeStart, tail: edgeEndTail });
    t = edgeEndHead;
    intervals.push({ trainId: train.id, kind: "node", resource: b, start: t, end: t + tailDelay, label: `стрелка ${b}` });
    events.push({ trainId: train.id, resource: b, event: i === route.length - 2 ? "прибытие / освобождение узла" : "проход стрелки", head: t, tail: t + tailDelay });
  }

  return {
    trainId: train.id,
    train,
    route,
    start: startSeconds,
    arrival: t,
    complete: t + tailDelay,
    intervals,
    events
  };
}

function validateInputs() {
  const errors = [];
  state.edges.forEach(edge => {
    if (edge.enabled && !(Number(edge.km) > 0)) errors.push(`Длина ${edge.id} должна быть положительной.`);
  });
  state.trains.forEach(train => {
    if (train.from === train.to) errors.push(`Поезд №${train.id}: выберите разные станции.`);
    if (!(Number(train.speed) > 0)) errors.push(`Скорость поезда №${train.id} должна быть положительной.`);
    if (!Number.isInteger(Number(train.cars)) || train.cars < 0) errors.push(`Вагоны поезда №${train.id}: нужно неотрицательное целое число.`);
    if (!Number.isInteger(Number(train.tanks)) || train.tanks < 0) errors.push(`Цистерны поезда №${train.id}: нужно неотрицательное целое число.`);
  });
  if (!(Number(state.carLength) > 0) || !(Number(state.tankLength) > 0)) errors.push("Длины вагона и цистерны должны быть положительными.");
  if (!(Number(state.safetyGap) >= 0)) errors.push("Запас безопасности не может быть отрицательным.");
  return errors;
}

function findConflicts(schedules) {
  const intervals = schedules.flatMap(schedule => schedule.intervals);
  const conflicts = [];
  for (let i = 0; i < intervals.length; i++) {
    for (let j = i + 1; j < intervals.length; j++) {
      const a = intervals[i];
      const b = intervals[j];
      if (a.trainId === b.trainId || a.resource !== b.resource) continue;
      const latestStart = Math.max(a.start, b.start);
      const earliestEnd = Math.min(a.end + state.safetyGap, b.end + state.safetyGap);
    if (latestStart < earliestEnd - 1e-7) {
        conflicts.push({
          resource: a.resource,
          kind: a.kind,
          trains: [a.trainId, b.trainId],
          start: latestStart,
          end: earliestEnd
        });
      }
    }
  }
  return conflicts;
}

function currentRoutesForManual() {
  return state.trains.map(train => {
    const route = train.routeMode === "manual"
      ? train.route.split("-").map(item => item.trim().toUpperCase()).filter(Boolean)
      : (findSimplePaths(train.from, train.to, 1)[0] || []);
    return { train, route };
  });
}

function manualPlan() {
  const errors = validateInputs();
  const schedules = [];
  if (errors.length) return { schedules, conflicts: [], errors, method: "Ручная проверка", proven: true };
  for (const item of currentRoutesForManual()) {
    if (!routeIsValid(item.route)) {
      errors.push(`Маршрут поезда №${item.train.id} невозможен: ${item.route.join("-") || "не задан"}.`);
      continue;
    }
    if (item.route[0] !== item.train.from || item.route.at(-1) !== item.train.to) {
      errors.push(`Маршрут поезда №${item.train.id} не соответствует станциям.`);
      continue;
    }
    const start = parseTime(item.train.start);
    if (!Number.isFinite(start)) {
      errors.push(`Время отправления поезда №${item.train.id} задано неверно.`);
      continue;
    }
    const base = buildSchedule(item.train, item.route, start);
    schedules.push(item.train.waits?.length ? fixedWaitSchedule(base, item.train.waits) : base);
  }
  const conflicts = errors.length ? [] : findConflicts(schedules);
  return { schedules, conflicts, errors, method: "Ручная проверка", proven: true };
}

function routeCombinations(routeSets) {
  let combos = [[]];
  routeSets.forEach(set => {
    combos = combos.flatMap(combo => set.map(route => [...combo, route]));
  });
  return combos;
}

async function calculateVariants() {
  const errors = validateInputs();
  const routeSets = state.trains.map(train => findSimplePaths(train.from, train.to, 5));
  routeSets.forEach((set, index) => {
    if (!set.length) errors.push(`Для поезда №${state.trains[index].id} нет пути по включённым рёбрам.`);
  });
  if (errors.length) return { variants: [], errors };

  const combos = routeCombinations(routeSets);
  const variants = [];
  const allowStops = document.getElementById("routingMode").value === "stops";
  let bounded = 0;
  for (let index = 0; index < combos.length; index++) {
    const combo = combos[index];
    const bases = state.trains.map((train, i) => buildSchedule(train, combo[i], 0));
    const ceiling = variants.length >= 6 ? variants[5].total : Infinity;
    if (Math.max(...bases.map(base => base.complete)) >= ceiling - 1e-7) {
      bounded++;
      if (index % 64 === 0) {
        document.getElementById("searchProgress").textContent = `Оценено сочетаний: ${index + 1} / ${combos.length}`;
        await new Promise(resolve => setTimeout(resolve, 0));
      }
      continue;
    }
    const solved = allowStops ? await solveWithStops(bases, state.safetyGap, ceiling)
      : await solveDepartures(bases, state.safetyGap, ceiling);
    if (!solved) { bounded++; continue; }
    const schedules = allowStops ? solved.schedules : state.trains.map((train, i) => buildSchedule(train, combo[i], solved.starts[i]));
    const conflicts = findConflicts(schedules);
    if (conflicts.length) throw new Error("Независимая проверка обнаружила конфликт.");
    variants.push({ schedules, conflicts, total: solved.total, method: "Ветви и границы", proven: true, allowStops, lengthMode: state.lengthMode });
    variants.sort((a, b) => a.total - b.total);
    if (variants.length > 6) variants.length = 6;
    document.getElementById("searchProgress").textContent = `Проверено сочетаний: ${index + 1} / ${combos.length}`;
    await new Promise(resolve => setTimeout(resolve, 0));
  }

  const seen = new Set();
  const unique = variants
    .sort((a, b) => a.total - b.total || a.schedules.reduce((sum, s) => sum + s.start, 0) - b.schedules.reduce((sum, s) => sum + s.start, 0))
    .filter(variant => {
      const key = variant.schedules.map(schedule => `${schedule.trainId}:${schedule.route.join("-")}:${Math.round(schedule.start)}`).join("|");
      if (seen.has(key)) return false;
      seen.add(key);
      return true;
    })
    .slice(0, 6);

  return { variants: unique, errors: [], combinations: combos.length, bounded, allowStops };
}

function syncStateFromControls() {
  state.edges.forEach(edge => {
    const kmInput = document.getElementById(`edge-${edge.id}-km`);
    const enabledInput = document.getElementById(`edge-${edge.id}-enabled`);
    if (kmInput) edge.km = kmInput.value === "" ? null : Number(kmInput.value);
    if (enabledInput) edge.enabled = enabledInput.checked;
  });
  state.carLength = Number(document.getElementById("carLength").value);
  state.tankLength = Number(document.getElementById("tankLength").value);
  state.safetyGap = Number(document.getElementById("safetyGap").value);
  state.legendText = document.getElementById("legendText").value;
  state.trains.forEach(train => {
    ["from", "to", "speed", "cars", "tanks", "start", "routeMode", "route"].forEach(field => {
      const input = document.getElementById(`train-${train.id}-${field}`);
      if (!input) return;
      if (["speed", "cars", "tanks"].includes(field)) train[field] = Number(input.value);
      else train[field] = input.value;
    });
  });
}

function renderEdgeControls() {
  edgeControls.innerHTML = "";
  const header = document.createElement("div");
  header.className = "edge-row muted";
  header.innerHTML = "<span>Ребро</span><span>км</span><span>вкл.</span>";
  edgeControls.append(header);
  state.edges.forEach(edge => {
    const row = document.createElement("div");
    row.className = "edge-row";
    row.innerHTML = `
      <label for="edge-${edge.id}-km">${edge.id}${edge.certain ? "" : "*"}</label>
      <input id="edge-${edge.id}-km" type="number" min="0.1" step="0.1" value="${edge.km ?? ""}" placeholder="?" aria-label="Длина ${edge.id}, км">
      <input id="edge-${edge.id}-enabled" type="checkbox" ${edge.enabled ? "checked" : ""} aria-label="Включить ${edge.id}">
    `;
    edgeControls.append(row);
  });
  document.getElementById("carLength").value = state.carLength;
  document.getElementById("tankLength").value = state.tankLength;
  document.getElementById("safetyGap").value = state.safetyGap;
  document.getElementById("legendText").value = state.legendText;
}

function renderTrainControls() {
  document.querySelector(".map-legend").innerHTML = state.trains.map(train => `<span style="--train:${train.color}">№${train.id} ${train.from} → ${train.to}</span>`).join("");
  trainControls.innerHTML = "";
  state.trains.forEach(train => {
    const paths = findSimplePaths(train.from, train.to, 8).map(path => path.join("-"));
    if (!paths.includes(train.route) && paths.length) train.route = paths[0];
    const card = document.createElement("div");
    card.className = "train-card";
    card.innerHTML = `
      <div class="train-title"><span class="swatch" style="background:${train.color}"></span>Поезд №${train.id}</div>
      <div class="train-fields">
        <label>Откуда
          <select id="train-${train.id}-from">${nodeOptions(train.from)}</select>
        </label>
        <label>Куда
          <select id="train-${train.id}-to">${nodeOptions(train.to)}</select>
        </label>
        <label>Скорость, км/ч
          <input id="train-${train.id}-speed" type="number" min="1" step="1" value="${train.speed}">
        </label>
        <label>Вагоны
          <input id="train-${train.id}-cars" type="number" min="0" step="1" value="${train.cars}">
        </label>
        <label>Цистерны
          <input id="train-${train.id}-tanks" type="number" min="0" step="1" value="${train.tanks}">
        </label>
        <label>Отправление
          <input id="train-${train.id}-start" type="text" value="${train.start}">
        </label>
        <label>Режим маршрута
          <select id="train-${train.id}-routeMode">
            <option value="auto" ${train.routeMode === "auto" ? "selected" : ""}>Авто</option>
            <option value="manual" ${train.routeMode === "manual" ? "selected" : ""}>Ручной</option>
          </select>
        </label>
        <label>Расчётная длина
          <input type="text" value="${Math.round(trainLength(train))} м" disabled>
        </label>
      </div>
      <label class="route-line">Маршрут
        <select id="train-${train.id}-route">
          ${paths.map(path => `<option value="${path}" ${path === train.route ? "selected" : ""}>${path} (${routeLength(path.split("-")).toFixed(1)} км)</option>`).join("")}
        </select>
      </label>
      ${train.waits?.length ? `<div class="muted dwell-summary">Ожидание: ${train.waits.map(w => `${w.node} · ${formatTime(w.duration)}`).join(", ")}</div>` : ""}
    `;
    trainControls.append(card);
  });
}

function nodeOptions(selected) {
  return Object.keys(nodes).map(node => `<option value="${node}" ${node === selected ? "selected" : ""}>${node}</option>`).join("");
}

function renderDerived() {
  const pairs = [
    ["A-O", ["A-E", "E-O"]],
    ["B-O", ["G-B", "O-G"]],
    ["C-O", ["S-C", "O-S"]],
    ["D-O", ["D-F", "F-O"]]
  ];
  derivedDistances.innerHTML = pairs.map(([label, ids]) => {
    const value = ids.reduce((sum, id) => sum + Number(edgeById(id)?.km || 0), 0);
    return `<div class="derived-item">${label}: <strong>${value.toFixed(1)} км</strong></div>`;
  }).join("");
  const pending = state.edges.filter(edge => !(Number(edge.km) > 0)).map(edge => edge.id);
  document.getElementById("networkNotice").textContent = pending.length
    ? `Не задана длина: ${pending.join(", ")}. Выключенные участки не участвуют в поиске. Длина единицы состава по умолчанию: 20 м (предположение).`
    : "Длина единицы состава по умолчанию: 20 м (предположение).";
}

function renderSvg() {
  edgeLayer.innerHTML = "";
  labelLayer.innerHTML = "";
  nodeLayer.innerHTML = "";
  trainLayer.innerHTML = "";

  state.edges.forEach(edge => {
    const a = nodes[edge.a];
    const b = nodes[edge.b];
    const length = Math.hypot(b.x - a.x, b.y - a.y);
    const nx = -(b.y - a.y) / length * 6;
    const ny = (b.x - a.x) / length * 6;
    const sleepers = document.createElementNS("http://www.w3.org/2000/svg", "path");
    let d = "";
    for (let distance = 12; distance < length - 10; distance += 12) {
      const x = a.x + (b.x - a.x) * distance / length;
      const y = a.y + (b.y - a.y) * distance / length;
      d += `M${x - nx},${y - ny}L${x + nx},${y + ny}`;
    }
    sleepers.setAttribute("d", d);
    sleepers.setAttribute("class", "sleepers");
    sleepers.style.opacity = edge.enabled ? "1" : ".25";
    edgeLayer.append(sleepers);
    const line = document.createElementNS("http://www.w3.org/2000/svg", "line");
    line.setAttribute("x1", a.x);
    line.setAttribute("y1", a.y);
    line.setAttribute("x2", b.x);
    line.setAttribute("y2", b.y);
    line.setAttribute("class", `rail ${edge.enabled ? "" : "disabled"}`);
    line.dataset.edge = edgeKey(edge.a, edge.b);
    edgeLayer.append(line);
    const inset = line.cloneNode();
    inset.removeAttribute("data-edge");
    inset.setAttribute("class", "rail-inset");
    edgeLayer.append(inset);

    const label = document.createElementNS("http://www.w3.org/2000/svg", "text");
    label.setAttribute("x", (a.x + b.x) / 2 + labelOffset(edge).x);
    label.setAttribute("y", (a.y + b.y) / 2 + labelOffset(edge).y);
    label.setAttribute("text-anchor", "middle");
    label.setAttribute("class", "edge-label");
    label.dataset.edgeId = edge.id;
    label.textContent = Number(edge.km) > 0 ? `${edge.km} км` : "? км";
    label.addEventListener("click", () => {
      const value = prompt(`Длина ${edge.id}, км`, edge.km);
      if (value === null) return;
      const number = Number(value.replace(",", "."));
      if (Number.isFinite(number) && number > 0) {
        edge.km = number;
        renderAll(false);
      }
    });
    labelLayer.append(label);
  });

  Object.entries(nodes).forEach(([id, point]) => {
    const circle = document.createElementNS("http://www.w3.org/2000/svg", "circle");
    circle.setAttribute("cx", point.x);
    circle.setAttribute("cy", point.y);
    circle.setAttribute("r", id.length === 1 ? 15 : 12);
    circle.setAttribute("class", `node ${"ABCDO".includes(id) ? "station" : ""}`);
    circle.dataset.node = id;
    nodeLayer.append(circle);
    const text = document.createElementNS("http://www.w3.org/2000/svg", "text");
    text.setAttribute("x", point.x);
    text.setAttribute("y", point.y + (["O", "G", "S"].includes(id) ? 35 : -25));
    text.setAttribute("text-anchor", "middle");
    text.setAttribute("class", "node-label");
    text.textContent = id;
    nodeLayer.append(text);
  });
}

function labelOffset(edge) {
  const offsets = {
    "E-F": { x: 0, y: -12 },
    "E-G": { x: -34, y: 5 },
    "F-S": { x: 34, y: 5 },
    "G-S": { x: 0, y: 22 },
    "E-O": { x: -28, y: 0 },
    "F-O": { x: 28, y: 0 },
    "O-G": { x: -28, y: 0 },
    "O-S": { x: 28, y: 0 }
  };
  return offsets[edge.id] || { x: 0, y: -15 };
}

function renderPlan(plan) {
  state.playing = false;
  state.selectedPlan = plan;
  const errors = plan.errors || [];
  const conflicts = plan.conflicts || [];
  const schedules = plan.schedules || [];
  document.getElementById("metricTime").textContent = formatTime(Math.max(0, ...schedules.map(s => s.complete)));
  document.getElementById("metricWait").textContent = formatTime(schedules.reduce((sum, s) => sum + s.start, 0));
  document.getElementById("metricStops").textContent = formatTime(schedules.reduce((sum, s) => sum + (s.dwells || []).reduce((total, d) => total + d.end - d.start, 0), 0));
  document.getElementById("metricConflicts").textContent = errors.length ? "—" : conflicts.length;
  if (errors.length) {
    warnings.innerHTML = errors.map(error => `<div>${error}</div>`).join("");
    overallStatus.textContent = "Есть ошибки ввода";
    overallStatus.className = "status-pill bad";
  } else if (conflicts.length) {
    warnings.innerHTML = conflicts.map(conflict => `<div>Конфликт: поезда №${conflict.trains.join(" и №")} на ${conflict.resource}, ${formatTime(conflict.start)}-${formatTime(conflict.end)}</div>`).join("");
    overallStatus.textContent = `Конфликтов: ${conflicts.length}`;
    overallStatus.className = "status-pill bad";
  } else {
    warnings.innerHTML = `<span class="good">Конфликтов не найдено. Проверка выполнена по неокруглённым интервалам.</span>`;
    overallStatus.textContent = "График допустим";
    overallStatus.className = "status-pill";
  }
  renderEvents(plan.schedules || []);
  renderChart(plan.schedules || []);
  updateAnimation(state.currentTime);
}

function renderEvents(schedules) {
  const rows = schedules.flatMap(schedule => schedule.events.map(event => ({ ...event, trainId: schedule.trainId })))
    .sort((a, b) => a.head - b.head);
  eventsBody.innerHTML = rows.map(row => `
    <tr>
      <td>№${row.trainId}</td>
      <td>${row.resource}</td>
      <td>${row.event}</td>
      <td>${formatTime(row.head)}</td>
      <td>${formatTime(row.tail)}</td>
    </tr>
  `).join("");
}

function renderChart(schedules) {
  if (!schedules.length) {
    timeChart.innerHTML = "<div class='muted'>Нет данных для диаграммы.</div>";
    return;
  }
  const maxTime = Math.max(1, ...schedules.map(schedule => schedule.complete));
  timeSlider.max = Math.ceil(maxTime);
  timeChart.innerHTML = schedules.map(schedule => {
    const edgeIntervals = schedule.intervals.filter(interval => interval.kind === "edge");
    const bars = edgeIntervals.map(interval => {
      const left = interval.start / maxTime * 100;
      const width = Math.max(.3, (interval.end - interval.start) / maxTime * 100);
      return `<span class="chart-bar" style="left:${left}%;width:${width}%;background:${schedule.train.color}" title="${interval.resource} ${formatTime(interval.start)}-${formatTime(interval.end)}"></span>`;
    }).join("");
    return `<div class="chart-row"><strong>№${schedule.trainId}</strong><div class="chart-lane">${bars}</div></div>`;
  }).join("");
}

function renderResults(variants, errors = []) {
  if (errors.length) {
    results.innerHTML = errors.map(error => `<div class="bad">${error}</div>`).join("");
    return;
  }
  if (!variants.length) {
    results.innerHTML = "<div class='bad'>Нет допустимых маршрутов.</div>";
    return;
  }
  results.innerHTML = variants.map((variant, index) => `
    <div class="variant">
      <strong>${index === 0 ? "Лучший график" : `Альтернатива ${index}`}: ${formatTime(variant.total)}</strong>
      <div class="mode-caption">${lengthModeLabel(variant.lengthMode)}</div>
      <div class="muted">${variant.allowStops ? "Ожидание у стрелок разрешено" : "Без остановок в пути"} · Конфликтов: ${variant.conflicts.length}</div>
      ${variant.schedules.map(schedule => `<div class="result-train"><span class="swatch" style="background:${schedule.train.color}"></span><span>№${schedule.trainId} · ${schedule.route.join(" → ")}<small>Отправление ${formatTime(schedule.start)} · прибытие ${formatTime(schedule.arrival)} · ${routeLength(schedule.route)} км</small>${(schedule.dwells || []).map(d => `<small>Ожидание ${d.node}: ${formatTime(d.start)}–${formatTime(d.end)}</small>`).join("")}</span></div>`).join("")}
      <button type="button" data-variant="${index}">Выбрать вариант</button>
    </div>
  `).join("");
  results.querySelectorAll("button[data-variant]").forEach(button => {
    button.addEventListener("click", () => {
      const variant = variants[Number(button.dataset.variant)];
      applyVariant(variant);
    });
  });
}

function applyVariant(variant) {
  variant.schedules.forEach(schedule => {
    const train = state.trains.find(item => item.id === schedule.trainId);
    const t = schedule.start;
    train.start = [Math.floor(t / 3600), Math.floor(t % 3600 / 60), Number((t % 60).toFixed(8))]
      .map(value => String(value).padStart(2, "0")).join(":");
    train.route = schedule.route.join("-");
    train.routeMode = "manual";
    train.waits = (schedule.dwells || []).map(d => ({ node: d.node, duration: d.end - d.start }));
  });
  state.currentTime = 0;
  renderTrainControls();
  bindInputs();
  renderPlan({ ...variant, errors: [] });
}

function interpolateOnRoute(schedule, t) {
  if (t < schedule.start || t > schedule.arrival) return null;
  if (schedule.segments) {
    const dwell = schedule.dwells.find(d => t >= d.start && t < d.end);
    if (dwell) return { ...nodes[dwell.node], prev: nodes[schedule.route[schedule.route.indexOf(dwell.node) - 1]], color: schedule.train.color, status: "ожидает" };
    const segment = schedule.segments.find(s => t >= s.start && t <= s.end);
    if (!segment) return null;
    const a = nodes[segment.from], b = nodes[segment.to];
    const ratio = (t - segment.start) / (segment.end - segment.start || 1);
    return { x: a.x + (b.x - a.x) * ratio, y: a.y + (b.y - a.y) * ratio, prev: a, color: schedule.train.color, status: "движется" };
  }
  let current = schedule.start;
  for (let i = 0; i < schedule.route.length - 1; i++) {
    const aId = schedule.route[i];
    const bId = schedule.route[i + 1];
    const edge = state.edges.find(item => edgeKey(item.a, item.b) === edgeKey(aId, bId) && item.enabled);
    const travel = 3600 * Number(edge.km) / schedule.train.speed;
    if (t <= current + travel) {
      const ratio = travel ? (t - current) / travel : 1;
      const a = nodes[aId];
      const b = nodes[bId];
      return {
        x: a.x + (b.x - a.x) * ratio,
        y: a.y + (b.y - a.y) * ratio,
        prev: a,
        color: schedule.train.color,
        status: "движется"
      };
    }
    current += travel;
  }
  return null;
}

function updateAnimation(time) {
  state.currentTime = Number(time) || 0;
  clock.textContent = formatTime(state.currentTime);
  timeSlider.value = Math.min(Number(timeSlider.max) || 1, state.currentTime);
  trainLayer.innerHTML = "";
  document.querySelectorAll(".rail").forEach(line => line.classList.remove("occupied"));
  document.querySelectorAll(".node").forEach(node => node.classList.remove("occupied"));
  const plan = state.selectedPlan;
  if (!plan || !plan.schedules) return;
  plan.schedules.forEach(schedule => {
    schedule.intervals.forEach(interval => {
      if (state.currentTime >= interval.start && state.currentTime <= interval.end) {
        if (interval.kind === "edge") {
          const line = document.querySelector(`.rail[data-edge="${CSS.escape(interval.resource)}"]`);
          if (line) line.classList.add("occupied");
        } else {
          const node = document.querySelector(`.node[data-node="${CSS.escape(interval.resource)}"]`);
          if (node) node.classList.add("occupied");
        }
      }
    });
    const pos = interpolateOnRoute(schedule, state.currentTime);
    if (!pos) return;
    const trail = document.createElementNS("http://www.w3.org/2000/svg", "line");
    const dx = pos.x - pos.prev.x;
    const dy = pos.y - pos.prev.y;
    const len = Math.hypot(dx, dy) || 1;
    const visualLength = Math.min(44, Math.max(16, trainLength(schedule.train) / 18));
    trail.setAttribute("x1", pos.x);
    trail.setAttribute("y1", pos.y);
    trail.setAttribute("x2", pos.x - dx / len * visualLength);
    trail.setAttribute("y2", pos.y - dy / len * visualLength);
    trail.setAttribute("class", "train-tail");
    trail.setAttribute("stroke", pos.color);
    if (trainLength(schedule.train) > 0) trainLayer.append(trail);
    const head = document.createElementNS("http://www.w3.org/2000/svg", "circle");
    head.setAttribute("cx", pos.x);
    head.setAttribute("cy", pos.y);
    head.setAttribute("r", 10);
    head.setAttribute("class", "train-head");
    head.setAttribute("fill", pos.color);
    trainLayer.append(head);
    const label = document.createElementNS("http://www.w3.org/2000/svg", "text");
    label.setAttribute("x", pos.x + 18);
    label.setAttribute("y", pos.y - 12);
    label.setAttribute("text-anchor", "start");
    label.setAttribute("class", "edge-label");
    label.textContent = `№${schedule.trainId}`;
    trainLayer.append(label);
  });
}

function animationLoop(timestamp) {
  if (!state.playing) return;
  if (!state.lastFrame) state.lastFrame = timestamp;
  const dt = (timestamp - state.lastFrame) / 1000;
  state.lastFrame = timestamp;
  const rate = Number(document.getElementById("playbackRate").value);
  const max = Number(timeSlider.max) || 1;
  const next = Math.min(max, state.currentTime + dt * rate * 60);
  updateAnimation(next);
  if (next >= max) {
    state.playing = false;
    return;
  }
  requestAnimationFrame(animationLoop);
}

function renderAll(preservePlan = true) {
  renderComparison();
  if (!preservePlan) {
    state.currentTime = 0;
    results.innerHTML = "";
    document.getElementById("searchProgress").textContent = "Параметры обновлены. Требуется новый расчёт.";
  }
  renderEdgeControls();
  renderDerived();
  renderTrainControls();
  renderSvg();
  if (!preservePlan || !state.selectedPlan) {
    renderPlan(manualPlan());
  } else {
    renderPlan(state.selectedPlan);
  }
  bindInputs();
}

function bindInputs() {
  document.querySelectorAll(".network-panel input, .train-panel input, .train-panel select").forEach(input => {
    input.onchange = () => {
      syncStateFromControls();
      if (input.id === "carLength" || input.id === "tankLength") {
        state.carLength = state.tankLength = Number(input.value);
      }
      results.innerHTML = "";
      document.getElementById("searchProgress").textContent = "Параметры изменены";
      renderAll(false);
    };
  });
}

document.getElementById("manualBtn").addEventListener("click", () => {
  syncStateFromControls();
  renderPlan(manualPlan());
});

document.getElementById("autoBtn").addEventListener("click", async () => {
  syncStateFromControls();
  state.playing = false;
  const controls = [...document.querySelectorAll("button, input, select")];
  const disabled = controls.map(control => control.disabled);
  controls.forEach(control => control.disabled = true);
  try {
    const result = await calculateVariants();
    renderResults(result.variants, result.errors);
    if (result.variants[0]) {
      const key = comparisonKey();
      comparisonCache.set(key, { ...comparisonCache.get(key), [state.lengthMode]: result.variants[0].total });
      renderComparison();
      applyVariant(result.variants[0]);
      document.getElementById("searchProgress").textContent = `Оценены все ${result.combinations} сочетаний; ${result.bounded} отсечены по временным границам. Оптимум для простых маршрутов ${result.allowStops ? "с ожиданием у стрелок" : "без остановок"}.`;
    }
  } catch (error) {
    warnings.textContent = error.message;
  } finally {
    controls.forEach((control, i) => control.disabled = disabled[i]);
  }
});

document.getElementById("randomBtn").addEventListener("click", () => {
  state.trains.forEach(train => {
    train.speed = Math.floor(25 + Math.random() * 46);
    train.cars = Math.floor(Math.random() * 24);
    train.tanks = Math.floor(Math.random() * 18);
    train.start = formatTime(Math.floor(Math.random() * 900));
    train.waits = [];
  });
  renderAll(false);
});

document.getElementById("defaultsBtn").addEventListener("click", () => {
  state = {
    lengthMode: "withCars",
    edges: structuredClone(initialEdges),
    trains: structuredClone(initialTrains),
    carLength: 20,
    tankLength: 20,
    safetyGap: 1,
    legendText: "ц = цистерны, в = вагоны",
    selectedPlan: null,
    currentTime: 0,
    playing: false,
    lastFrame: 0
  };
  results.innerHTML = "";
  renderAll(false);
});

document.getElementById("playBtn").addEventListener("click", () => {
  if (state.playing) return;
  if (state.currentTime >= Number(timeSlider.max)) updateAnimation(0);
  state.playing = true;
  state.lastFrame = 0;
  requestAnimationFrame(animationLoop);
});

document.getElementById("pauseBtn").addEventListener("click", () => {
  state.playing = false;
});

document.getElementById("resetAnimBtn").addEventListener("click", () => {
  state.playing = false;
  updateAnimation(0);
});

timeSlider.addEventListener("input", event => {
  state.playing = false;
  updateAnimation(Number(event.target.value));
});

document.getElementById("routingMode").addEventListener("change", () => {
  results.innerHTML = "";
  state.trains.forEach(train => train.waits = []);
  renderAll(false);
  document.getElementById("searchProgress").textContent = "Режим изменён. Требуется новый расчёт.";
});

document.getElementById("withCarsBtn").addEventListener("click", () => changeLengthMode("withCars"));
document.getElementById("withoutCarsBtn").addEventListener("click", () => changeLengthMode("withoutCars"));

renderAll(false);
