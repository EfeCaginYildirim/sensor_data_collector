// Sensor Data Collector — front end. Plain JavaScript, no build step, no framework.
"use strict";

const $ = (selector, root = document) => root.querySelector(selector);
const $$ = (selector, root = document) => Array.from(root.querySelectorAll(selector));

const FEATURE_NAMES = (() => {
  const axes = { X: " X", Y: " Y", Z: " Z" };
  const families = {
    KURTACCL: "Kurtosis", PPACCL: "Peak-to-peak acceleration", CRESTACCL: "Crest factor",
    GRMSACCL: "RMS acceleration (g)", PPSW: "Shock-wave peak-to-peak", SWRMS: "Shock-wave RMS",
    VRMS: "RMS velocity (mm/s)",
  };
  const single = {
    IDLE: "Idle fraction", RPM: "Speed (rpm)", BPFI: "BPFI amplitude (inner race)",
    BPFO: "BPFO amplitude (outer race)", BSF: "BSF amplitude (ball spin)", FTF: "FTF amplitude (cage)",
    LOAD: "Load (%)", TA: "Temperature",
  };
  return (name) => {
    if (single[name]) return single[name];
    const axis = name.slice(-1);
    const family = families[name.slice(0, -1)];
    return family ? family + (axes[axis] || "") : name;
  };
})();

const dateTime = new Intl.DateTimeFormat(undefined, { dateStyle: "medium", timeStyle: "short" });
const formatTime = (iso) => (iso ? dateTime.format(new Date(iso)) : "never");
const formatNumber = (v, digits = 2) => (v === null || v === undefined ? "–" : Number(v).toFixed(digits));

const state = {
  view: "monitors",
  monitors: [],
  features: [],
  selected: null,
  chart: null,
  wasRunning: false,
  settings: null,
};

// ── HTTP ─────────────────────────────────────────────────────────────────

class ApiError extends Error {
  constructor(status, body) {
    super(body && body.error ? body.error : `HTTP ${status}`);
    this.status = status;
    this.body = body;
  }
}

async function api(path, { method = "GET", body } = {}) {
  const options = { method, credentials: "same-origin", headers: {} };
  if (method !== "GET") {
    options.headers["Content-Type"] = "application/json";
    options.body = body === undefined ? "{}" : JSON.stringify(body);
  }
  let response;
  try {
    response = await fetch(path, options);
  } catch (e) {
    showNotice("The app is not running. Start it again to continue.");
    throw e;
  }
  const data = response.status === 204 ? null : await response.json().catch(() => null);
  if (response.status === 401) {
    showNotice("This window is not signed in. Close it and start the app again; it opens a signed-in window.");
  }
  if (!response.ok) throw new ApiError(response.status, data);
  return data;
}

function showNotice(text) {
  const notice = $("#notice");
  notice.textContent = text;
  notice.hidden = false;
}

// ── Navigation ───────────────────────────────────────────────────────────

function show(view) {
  state.view = view;
  $$(".tabs button").forEach((b) => b.setAttribute("aria-selected", String(b.dataset.view === view)));
  $$(".view").forEach((v) => { v.hidden = v.id !== `view-${view}`; });
  if (location.hash !== `#${view}`) history.replaceState(null, "", `#${view}`);
  if (view === "monitors") loadMonitors();
  if (view === "collect") { loadProgress(); loadRuns(); }
  if (view === "settings") loadSettings();
}

// ── Status bar ───────────────────────────────────────────────────────────

async function loadStatus() {
  const s = await api("/api/status");
  const source = s.mode === "demo" ? "Demo data" : "Vendor API";
  const schedule = s.nextRun ? `next collection ${formatTime(s.nextRun)}` : "daily schedule off";
  $("#status").textContent = `${source}, ${schedule}. Version ${s.version}`;
  $("#data-dir").textContent = `Settings and data are kept in ${s.dataDirectory}`;
  return s;
}

// ── Monitors ─────────────────────────────────────────────────────────────

const ZONES = [
  ["normal", "Normal"], ["elevated", "Elevated"], ["caution", "Caution"], ["warning", "Warning"],
];

function zoneOf(reading) {
  const v = reading.value;
  if (v < reading.operational) return 0;
  if (v < reading.caution) return 1;
  if (v < reading.warning) return 2;
  return 3;
}

function severityStrip(reading) {
  const cell = document.createElement("div");
  cell.className = "strip-cell";
  const usable = reading && [reading.value, reading.operational, reading.caution, reading.warning].every((x) => typeof x === "number");
  if (!usable) {
    cell.innerHTML = '<p class="strip-reading">No velocity reading</p>';
    return cell;
  }
  const max = Math.max(reading.warning * 1.35, reading.value * 1.1);
  const bounds = [0, reading.operational, reading.caution, reading.warning, max];
  const strip = document.createElement("div");
  strip.className = "strip";
  strip.setAttribute("aria-hidden", "true");
  ZONES.forEach(([zone], i) => {
    const seg = document.createElement("span");
    seg.className = `seg zone-${zone}`;
    seg.style.width = `${((bounds[i + 1] - bounds[i]) / max) * 100}%`;
    strip.appendChild(seg);
  });
  const marker = document.createElement("span");
  marker.className = "marker";
  marker.style.left = `${(reading.value / max) * 100}%`;
  strip.appendChild(marker);
  const label = document.createElement("p");
  label.className = "strip-reading";
  const zone = ZONES[zoneOf(reading)][1];
  label.innerHTML = `<strong>${zone}</strong>: ${formatNumber(reading.value)} mm/s, warning at ${formatNumber(reading.warning, 1)}`;
  cell.append(strip, label);
  return cell;
}

function monitorRow(m) {
  const row = document.createElement("button");
  row.type = "button";
  row.className = "monitor";
  row.setAttribute("aria-pressed", String(state.selected === m.monitorId));
  row.dataset.monitorId = m.monitorId;

  const name = document.createElement("span");
  name.className = "monitor-name";
  name.textContent = m.monitorName || `Monitor ${m.monitorId}`;
  const id = document.createElement("span");
  id.className = "monitor-id";
  id.textContent = `Monitor ${m.monitorId}`;
  name.appendChild(id);

  const health = document.createElement("span");
  health.className = "health";
  const score = m.status ? m.status.healthScore : null;
  health.textContent = score === null || score === undefined ? "–" : score;
  const caption = document.createElement("small");
  caption.textContent = "health";
  health.appendChild(caption);

  const collected = document.createElement("span");
  collected.className = "collected";
  const until = document.createElement("strong");
  until.textContent = `Until ${formatTime(m.collectedUntil)}`;
  collected.append(until, `${m.computedSamples.toLocaleString()} samples`);

  row.append(name, health, severityStrip(m.status && m.status.velocityX), collected);
  row.addEventListener("click", () => selectMonitor(m.monitorId));
  return row;
}

async function loadMonitors() {
  state.monitors = await api("/api/monitors");
  const machines = $("#machines");
  machines.replaceChildren();
  $("#monitors-empty").hidden = state.monitors.length > 0;
  $("#legend").hidden = state.monitors.length === 0;
  const latest = state.monitors.map((m) => m.collectedUntil).filter(Boolean).sort().pop();
  $("#monitors-lede").textContent = state.monitors.length
    ? `${state.monitors.length} monitors, data collected until ${formatTime(latest)}. Select one to plot its trend.`
    : "";

  const groups = new Map();
  state.monitors.forEach((m) => {
    const key = m.machineName || "Unassigned";
    if (!groups.has(key)) groups.set(key, []);
    groups.get(key).push(m);
  });
  groups.forEach((list, machine) => {
    const section = document.createElement("section");
    section.className = "machine";
    const heading = document.createElement("h3");
    heading.textContent = machine;
    const meta = document.createElement("span");
    meta.className = "machine-meta";
    meta.textContent = [list[0].areaName, list[0].machineGroupName].filter(Boolean).join(", ");
    heading.appendChild(meta);
    section.appendChild(heading);
    list.forEach((m) => section.appendChild(monitorRow(m)));
    machines.appendChild(section);
  });

  if (state.monitors.length && !state.monitors.some((m) => m.monitorId === state.selected)) {
    const worst = [...state.monitors].sort((a, b) => ((a.status || {}).healthScore ?? 101) - ((b.status || {}).healthScore ?? 101))[0];
    selectMonitor(worst.monitorId);
  } else if (state.selected) {
    loadChart();
  }
}

async function selectMonitor(id) {
  state.selected = id;
  $$(".monitor").forEach((r) => r.setAttribute("aria-pressed", String(Number(r.dataset.monitorId) === id)));
  await loadChart();
}

function fillFeaturePickers() {
  if ($("#feature-a").options.length) return;
  const add = (select, value, label) => select.add(new Option(label, value));
  state.features.forEach((f) => add($("#feature-a"), f, FEATURE_NAMES(f)));
  add($("#feature-b"), "", "None");
  state.features.forEach((f) => add($("#feature-b"), f, FEATURE_NAMES(f)));
  $("#feature-a").value = "KURTACCLX";
  $("#feature-b").value = "BPFO";
  $("#feature-a").addEventListener("change", loadChart);
  $("#feature-b").addEventListener("change", loadChart);
}

async function loadChart() {
  const m = state.monitors.find((x) => x.monitorId === state.selected);
  if (!m) return;
  fillFeaturePickers();
  const a = $("#feature-a").value;
  const b = $("#feature-b").value;
  const features = [a, b].filter((f, i, all) => f && all.indexOf(f) === i);
  $("#detail").hidden = false;
  const width = Math.max(300, $("#chart").getBoundingClientRect().width || 800);
  const series = await api(`/api/monitors/${m.monitorId}/series?features=${features.join(",")}&points=${Math.min(1500, Math.round(width))}`);

  $("#detail-title").textContent = `${m.machineName || ""} ${m.monitorName || ""}`.trim();
  $("#chart-note").textContent = series.sourceRows > series.times.length
    ? `${series.sourceRows.toLocaleString()} samples, averaged into ${series.times.length} points for display.`
    : `${series.sourceRows.toLocaleString()} samples.`;

  const colors = ["#0e6a86", "#c2402c"];
  const opts = {
    width,
    height: 320,
    cursor: { drag: { x: true, y: false } },
    scales: { x: { time: true }, a: {}, b: {} },
    series: [{}].concat(series.features.map((f, i) => ({
      label: FEATURE_NAMES(f), scale: i === 0 ? "a" : "b", stroke: colors[i], width: 1.5, spanGaps: false,
      value: (u, v) => formatNumber(v, 3),
    }))),
    axes: [
      { stroke: "#56656c", grid: { stroke: "#dde3e2" } },
      { scale: "a", stroke: colors[0], grid: { stroke: "#dde3e2" } },
    ].concat(series.features.length > 1 ? [{ scale: "b", side: 1, stroke: colors[1], grid: { show: false } }] : []),
  };
  const data = [series.times].concat(series.values);
  if (state.chart) state.chart.destroy();
  state.chart = new uPlot(opts, data, $("#chart"));
}

// ── Collect ──────────────────────────────────────────────────────────────

const STATUS_WORDS = {
  SUCCESS: "Success", PARTIAL: "Partly collected", FAILED: "Failed", INVALID_SETTINGS: "Settings incomplete",
  COLLECTED: "Collected", UP_TO_DATE: "Up to date",
};

async function collectNow() {
  try {
    await api("/api/runs", { method: "POST" });
    state.wasRunning = true;
    if (state.view !== "collect") show("collect"); else loadProgress();
  } catch (e) {
    if (e.status === 409) { show("collect"); return; }
    $("#run-headline").textContent = e.message;
    show("collect");
  }
}

async function loadProgress() {
  const p = await api("/api/progress");
  const button = $("#collect-button");
  button.disabled = p.running;
  button.textContent = p.running ? "Collecting…" : "Collect now";
  const percent = p.chunksTotal ? Math.round((p.chunksDone / p.chunksTotal) * 100) : (p.running ? 0 : 100);
  $("#meter-fill").style.width = `${p.running || p.lastStatus ? percent : 0}%`;
  $("#meter").setAttribute("aria-valuenow", String(percent));
  $("#run-headline").textContent = p.running
    ? `Collecting: ${p.chunksDone} of ${p.chunksTotal || "?"} time ranges fetched`
    : p.lastStatus ? `Last run: ${STATUS_WORDS[p.lastStatus] || p.lastStatus}` : "No run yet in this session";

  const body = $("#run-monitors tbody");
  body.replaceChildren(...p.monitors.map((m) => {
    const tr = document.createElement("tr");
    const stateText = m.state === "done" ? (STATUS_WORDS[m.outcome] || m.outcome) : m.state === "fetching" ? "Fetching" : "Waiting";
    tr.innerHTML = `<td></td><td class="state-${m.outcome || ""}"></td><td class="num"></td><td class="note"></td>`;
    const known = state.monitors.find((x) => x.monitorId === m.monitorId);
    tr.children[0].textContent = known ? `${known.machineName} ${known.monitorName}` : `Monitor ${m.monitorId}`;
    tr.children[1].textContent = stateText;
    tr.children[2].textContent = m.chunksTotal ? `${m.chunksDone} / ${m.chunksTotal}` : "–";
    tr.children[3].textContent = m.error || (m.chunksFailed ? `${m.chunksFailed} failed` : "");
    return tr;
  }));
  $("#run-monitors").hidden = p.monitors.length === 0;
  $("#summary-box").hidden = !p.lastSummary;
  $("#last-summary").textContent = p.lastSummary || "";

  if (state.wasRunning && !p.running) {
    loadRuns();
    loadMonitors();
  }
  state.wasRunning = p.running;
  return p;
}

async function loadRuns() {
  const runs = await api("/api/runs?limit=20");
  const body = $("#runs tbody");
  body.replaceChildren(...runs.map((r) => {
    const tr = document.createElement("tr");
    tr.innerHTML = '<td></td><td></td><td class="num"></td><td class="num"></td><td class="num"></td>';
    tr.children[0].textContent = formatTime(r.startedAt);
    tr.children[1].textContent = STATUS_WORDS[r.status] || r.status;
    tr.children[1].className = `result-${r.status}`;
    tr.children[2].textContent = `${(r.elapsedMs / 1000).toFixed(1)} s`;
    tr.children[3].textContent = r.newRows.toLocaleString();
    tr.children[4].textContent = r.monitorsFailed ? `${r.monitorsFailed} of ${r.monitorsTotal}` : "none";
    if (r.failure) tr.title = r.failure;
    return tr;
  }));
  $("#runs").hidden = runs.length === 0;
}

// ── Settings ─────────────────────────────────────────────────────────────

function formValues() {
  const values = {};
  $$("#settings-form [name]").forEach((input) => {
    if (input.type === "radio") {
      if (input.checked) values[input.name] = input.value;
    } else if (input.type === "checkbox") {
      values[input.name] = String(input.checked);
    } else {
      values[input.name] = input.value;
    }
  });
  return values;
}

function applyMode(mode) {
  $$("#settings-form [data-mode]").forEach((f) => { f.hidden = f.dataset.mode !== mode; });
}

async function loadSettings() {
  const s = await api("/api/settings");
  state.settings = s;
  $$("#settings-form [name]").forEach((input) => {
    const value = s[input.name];
    if (value === undefined) return;
    if (input.type === "radio") input.checked = input.value === value;
    else if (input.type === "checkbox") input.checked = value === "true";
    else input.value = value;
  });
  const password = $('#settings-form [name="api.password"]');
  password.value = "";
  password.placeholder = s["api.password-set"] === "true" ? "Saved. Leave empty to keep it" : "";
  applyMode(s["ui.mode"]);
  showProblems([]);
  $("#settings-result").textContent = "";
}

function showProblems(problems) {
  const box = $("#problems");
  $$("#settings-form .invalid").forEach((el) => el.classList.remove("invalid"));
  box.hidden = problems.length === 0;
  if (!problems.length) return;
  box.innerHTML = "<strong>Fix these before saving:</strong><ul></ul>";
  problems.forEach((p) => {
    const key = p.split(/[ =]/)[0];
    const field = $(`#settings-form [name="${CSS.escape(key)}"]`);
    const li = document.createElement("li");
    li.textContent = readableProblem(p, key, field);
    $("ul", box).appendChild(li);
    if (field) field.classList.add("invalid");
  });
}

/** "api.base-url is required (or set SDC_API_BASE_URL)" becomes "Base URL is required". */
function readableProblem(problem, key, field) {
  let text = problem.replace(/ \(or set SDC_[A-Z_]+\)$/, "");
  const label = field && field.closest("label");
  if (label) {
    const name = label.firstChild.textContent.trim();
    text = text.replace(new RegExp(`^${key.replace(/[.]/g, "\\.")}(='[^']*')?`), name);
  }
  return text.charAt(0).toUpperCase() + text.slice(1);
}

async function saveSettings(event) {
  event.preventDefault();
  const result = $("#settings-result");
  try {
    await api("/api/settings", { method: "PUT", body: formValues() });
    showProblems([]);
    result.textContent = "Settings saved.";
    result.className = "result ok";
    await loadSettings();
    result.textContent = "Settings saved.";
    loadStatus();
  } catch (e) {
    if (e.body && e.body.problems) showProblems(e.body.problems);
    result.textContent = "Not saved.";
    result.className = "result bad";
  }
}

async function testConnection() {
  const result = $("#settings-result");
  result.textContent = "Testing…";
  result.className = "result";
  try {
    const r = await api("/api/settings/test", { method: "POST", body: formValues() });
    result.textContent = r.message;
    result.className = `result ${r.ok ? "ok" : "bad"}`;
  } catch (e) {
    result.textContent = e.message;
    result.className = "result bad";
  }
}

async function quit() {
  await api("/api/shutdown", { method: "POST" }).catch(() => {});
  document.querySelector("main").innerHTML = '<div class="empty"><p>The app has stopped. You can close this tab.</p></div>';
  $("#status").textContent = "Stopped";
}

// ── Start-up ─────────────────────────────────────────────────────────────

function fillZones() {
  if (!Intl.supportedValuesOf) return;
  const list = $("#zones");
  Intl.supportedValuesOf("timeZone").forEach((z) => list.appendChild(new Option(z)));
}

document.addEventListener("DOMContentLoaded", async () => {
  $$(".tabs button").forEach((b) => b.addEventListener("click", () => show(b.dataset.view)));
  $$('[data-action="collect"]').forEach((b) => b.addEventListener("click", collectNow));
  $("#settings-form").addEventListener("submit", saveSettings);
  $("#test-button").addEventListener("click", testConnection);
  $("#quit-button").addEventListener("click", quit);
  $$('#settings-form [name="ui.mode"]').forEach((r) => r.addEventListener("change", () => applyMode(r.value)));
  window.addEventListener("hashchange", () => show(location.hash.slice(1) || "monitors"));
  let resizeTimer;
  window.addEventListener("resize", () => { clearTimeout(resizeTimer); resizeTimer = setTimeout(loadChart, 200); });
  fillZones();

  try {
    state.features = await api("/api/features");
    await loadStatus();
  } catch (e) {
    return;
  }
  const initial = location.hash.slice(1);
  show(["monitors", "collect", "settings"].includes(initial) ? initial : "monitors");

  setInterval(async () => {
    try {
      const status = await api("/api/status");
      if (status.running || state.wasRunning || state.view === "collect") await loadProgress();
    } catch (e) { /* notice already shown */ }
  }, 1000);
  setInterval(() => loadStatus().catch(() => {}), 15000);
});
