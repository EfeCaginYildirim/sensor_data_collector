// Sensor Data Collector — front end. Plain JavaScript, no build step, no framework.
"use strict";

const $ = (selector, root = document) => root.querySelector(selector);
const $$ = (selector, root = document) => Array.from(root.querySelectorAll(selector));

const { t } = I18N;

/** "KURTACCLX" becomes "Kurtosis X", "BPFO" "BPFO amplitude (outer race)", in the chosen language. */
const FEATURE_NAMES = (name) => {
  const single = `feature.${name}`;
  if (t(single) !== single) return t(single);
  const family = `feature.${name.slice(0, -1)}`;
  const axis = name.slice(-1);
  return t(family) !== family && ["X", "Y", "Z"].includes(axis) ? `${t(family)} ${axis}` : name;
};

const formatTime = (iso) => (iso ? I18N.dateTime(new Date(iso)) : t("time.never"));
const formatNumber = I18N.number;
const formatCount = I18N.integer;

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
    showNotice(t("notice.notRunning"));
    throw e;
  }
  const data = response.status === 204 ? null : await response.json().catch(() => null);
  if (response.status === 401) {
    showNotice(t("notice.signedOut"));
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
  if (view === "data") loadData();
  if (view === "settings") loadSettings();
}

// ── Status bar ───────────────────────────────────────────────────────────

async function loadStatus() {
  const s = await api("/api/status");
  const source = t(s.mode === "demo" ? "status.demo" : "status.api");
  const schedule = s.nextRun ? t("status.next", { time: formatTime(s.nextRun) }) : t("status.off");
  $("#status").textContent = t("status.line", { source, schedule, version: s.version });
  $("#data-dir").textContent = t("status.dataDir", { dir: s.dataDirectory });
  return s;
}

// ── Monitors ─────────────────────────────────────────────────────────────

const ZONES = ["normal", "elevated", "caution", "warning"];

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
    const none = document.createElement("p");
    none.className = "strip-reading";
    none.textContent = t("strip.none");
    cell.appendChild(none);
    return cell;
  }
  const max = Math.max(reading.warning * 1.35, reading.value * 1.1);
  const bounds = [0, reading.operational, reading.caution, reading.warning, max];
  const strip = document.createElement("div");
  strip.className = "strip";
  strip.setAttribute("aria-hidden", "true");
  ZONES.forEach((zone, i) => {
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
  const zone = document.createElement("strong");
  zone.textContent = t(`zone.${ZONES[zoneOf(reading)]}`);
  label.append(zone, t("strip.reading", { value: formatNumber(reading.value), warning: formatNumber(reading.warning, 1) }));
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
  name.textContent = m.monitorName || t("monitor.name", { id: m.monitorId });
  const id = document.createElement("span");
  id.className = "monitor-id";
  id.textContent = t("monitor.name", { id: m.monitorId });
  name.appendChild(id);

  const health = document.createElement("span");
  health.className = "health";
  const score = m.status ? m.status.healthScore : null;
  health.textContent = score === null || score === undefined ? "–" : score;
  const caption = document.createElement("small");
  caption.textContent = t("monitor.health");
  health.appendChild(caption);

  const collected = document.createElement("span");
  collected.className = "collected";
  const until = document.createElement("strong");
  until.textContent = m.collectedUntil ? t("monitor.until", { time: formatTime(m.collectedUntil) }) : t("monitor.notYet");
  collected.append(until, t("monitor.samples", { count: formatCount(m.computedSamples) }));

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
    ? t("monitors.lede", { count: state.monitors.length, time: formatTime(latest) })
    : "";

  const groups = new Map();
  state.monitors.forEach((m) => {
    const key = m.machineName || t("monitors.unassigned");
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
  add($("#feature-b"), "", t("picker.none"));
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
    ? t("chart.averaged", { rows: formatCount(series.sourceRows), points: formatCount(series.times.length) })
    : t("chart.samples", { rows: formatCount(series.sourceRows) });

  const colors = ["#0e6a86", "#c2402c"];
  const opts = {
    width,
    height: 320,
    cursor: { drag: { x: true, y: false } },
    scales: { x: { time: true }, a: {}, b: {} },
    series: [{ label: t("chart.time"), value: I18N.timeValue }].concat(series.features.map((f, i) => ({
      label: FEATURE_NAMES(f), scale: i === 0 ? "a" : "b", stroke: colors[i], width: 1.5, spanGaps: false,
      value: (u, v) => formatNumber(v, 3),
    }))),
    axes: [
      { stroke: "#56656c", grid: { stroke: "#dde3e2" }, values: I18N.timeTicks },
      { scale: "a", stroke: colors[0], grid: { stroke: "#dde3e2" }, values: I18N.numberTicks },
    ].concat(series.features.length > 1 ? [{ scale: "b", side: 1, stroke: colors[1], grid: { show: false }, values: I18N.numberTicks }] : []),
  };
  const data = [series.times].concat(series.values);
  if (state.chart) state.chart.destroy();
  state.chart = new uPlot(opts, data, $("#chart"));
}

// ── Collect ──────────────────────────────────────────────────────────────

const statusWord = (status) => (t(`result.${status}`) === `result.${status}` ? status : t(`result.${status}`));

async function collectNow() {
  try {
    await api("/api/runs", { method: "POST" });
    state.wasRunning = true;
    if (state.view !== "collect") show("collect"); else loadProgress();
  } catch (e) {
    if (e.status === 409) { show("collect"); return; }
    $("#run-headline").textContent = I18N.server(e.message);
    show("collect");
  }
}

async function loadProgress() {
  const p = await api("/api/progress");
  const button = $("#collect-button");
  button.disabled = p.running;
  button.textContent = t(p.running ? "collect.busy" : "action.collect");
  const percent = p.chunksTotal ? Math.round((p.chunksDone / p.chunksTotal) * 100) : (p.running ? 0 : 100);
  $("#meter-fill").style.width = `${p.running || p.lastStatus ? percent : 0}%`;
  $("#meter").setAttribute("aria-valuenow", String(percent));
  $("#run-headline").textContent = p.running
    ? t("collect.progress", { done: formatCount(p.chunksDone), total: p.chunksTotal ? formatCount(p.chunksTotal) : "?" })
    : p.lastStatus ? t("collect.last", { status: statusWord(p.lastStatus) }) : t("collect.noneYet");

  const body = $("#run-monitors tbody");
  body.replaceChildren(...p.monitors.map((m) => {
    const tr = document.createElement("tr");
    const stateText = m.state === "done" ? statusWord(m.outcome) : t(m.state === "fetching" ? "collect.fetching" : "collect.waiting");
    tr.innerHTML = `<td></td><td class="state-${m.outcome || ""}"></td><td class="num"></td><td class="note"></td>`;
    const known = state.monitors.find((x) => x.monitorId === m.monitorId);
    tr.children[0].textContent = known ? `${known.machineName} ${known.monitorName}` : t("monitor.name", { id: m.monitorId });
    tr.children[1].textContent = stateText;
    tr.children[2].textContent = m.chunksTotal ? `${formatCount(m.chunksDone)} / ${formatCount(m.chunksTotal)}` : "–";
    tr.children[3].textContent = m.error ? I18N.server(m.error) : m.chunksFailed ? t("collect.chunksFailed", { count: m.chunksFailed }) : "";
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
    tr.children[1].textContent = statusWord(r.status);
    tr.children[1].className = `result-${r.status}`;
    tr.children[2].textContent = t("runs.seconds", { seconds: formatNumber(r.elapsedMs / 1000, 1) });
    tr.children[3].textContent = formatCount(r.newRows);
    tr.children[4].textContent = r.monitorsFailed ? t("runs.failed", { failed: r.monitorsFailed, total: r.monitorsTotal }) : t("runs.none");
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
  password.placeholder = s["api.password-set"] === "true" ? t("settings.passwordSaved") : "";
  applyMode(s["ui.mode"]);
  showProblems([]);
  $("#settings-result").textContent = "";
}

function showProblems(problems) {
  const box = $("#problems");
  $$("#settings-form .invalid").forEach((el) => el.classList.remove("invalid"));
  box.hidden = problems.length === 0;
  if (!problems.length) return;
  box.innerHTML = "<strong></strong><ul></ul>";
  $("strong", box).textContent = t("settings.fix");
  problems.forEach((p) => {
    const key = p.split(/[ =]/)[0];
    const field = $(`#settings-form [name="${CSS.escape(key)}"]`);
    const li = document.createElement("li");
    li.textContent = readableProblem(p, key, field);
    $("ul", box).appendChild(li);
    if (field) field.classList.add("invalid");
  });
}

/** "api.base-url is required (or set SDC_API_BASE_URL)" becomes "Base URL is required" ("URL de base est obligatoire"). */
function readableProblem(problem, key, field) {
  let text = problem.replace(/ \(or set SDC_[A-Z_]+\)$/, "");
  const label = field && field.closest("label");
  if (label) {
    const name = (label.querySelector("span") || label.firstChild).textContent.trim();
    text = text.replace(new RegExp(`^${key.replace(/[.]/g, "\\.")}(='[^']*')?`), name);
  }
  text = I18N.server(text);
  return text.charAt(0).toUpperCase() + text.slice(1);
}

async function saveSettings(event) {
  event.preventDefault();
  const result = $("#settings-result");
  try {
    await api("/api/settings", { method: "PUT", body: formValues() });
    showProblems([]);
    result.textContent = t("settings.saved");
    result.className = "result ok";
    await loadSettings();
    result.textContent = t("settings.saved");
    loadStatus();
  } catch (e) {
    if (e.body && e.body.problems) showProblems(e.body.problems);
    result.textContent = t("settings.notSaved");
    result.className = "result bad";
  }
}

async function testConnection() {
  const result = $("#settings-result");
  result.textContent = t("settings.testing");
  result.className = "result";
  try {
    const r = await api("/api/settings/test", { method: "POST", body: formValues() });
    result.textContent = I18N.server(r.message);
    result.className = `result ${r.ok ? "ok" : "bad"}`;
  } catch (e) {
    result.textContent = I18N.server(e.message);
    result.className = "result bad";
  }
}

async function quit() {
  await api("/api/shutdown", { method: "POST" }).catch(() => {});
  document.querySelector("main").innerHTML = '<div class="empty"><p></p></div>';
  $("main .empty p").textContent = t("quit.done");
  $("#status").textContent = t("status.stopped");
}

// ── Data ─────────────────────────────────────────────────────────────────

const AXIS_FAMILIES = ["VRMS", "GRMSACCL", "PPACCL", "CRESTACCL", "KURTACCL", "PPSW", "SWRMS"];
const DEFAULT_COLUMNS = ["VRMSX", "VRMSY", "VRMSZ", "KURTACCLX", "BPFO", "RPM", "LOAD"];
const EXPORT_FORMATS = {
  comma: { separator: "comma", decimal: "." },
  semicolon: { separator: "semicolon", decimal: "," },
  tab: { separator: "tab", decimal: "." },
};
const formatUtc = (date) => t("time.utc", { time: I18N.dateTime(date, true) });
const sentence = (text) => text.charAt(0).toUpperCase() + text.slice(1) + (/[.!?]$/.test(text) ? "" : ".");

state.data = { built: false, token: 0, exclusions: [], chart: null, pending: null, refreshTimer: null };

function familyName(family) {
  return t(`feature.${family}`);
}

function checkbox(name, value, label, checked = false) {
  const wrap = document.createElement("label");
  wrap.className = "check";
  const input = document.createElement("input");
  input.type = "checkbox";
  input.name = name;
  input.value = value;
  input.checked = checked;
  wrap.append(input, document.createTextNode(` ${label}`));
  return wrap;
}

function buildFilters() {
  if (state.data.built) return;
  state.data.built = true;
  const body = $("#matrix tbody");
  AXIS_FAMILIES.forEach((family) => {
    const tr = document.createElement("tr");
    const th = document.createElement("th");
    th.scope = "row";
    const toggle = document.createElement("button");
    toggle.type = "button";
    toggle.className = "family-toggle";
    toggle.dataset.family = family;
    toggle.textContent = familyName(family);
    th.appendChild(toggle);
    tr.appendChild(th);
    ["X", "Y", "Z"].forEach((axis) => {
      const td = document.createElement("td");
      const input = document.createElement("input");
      input.type = "checkbox";
      input.name = "column";
      input.value = family + axis;
      input.title = FEATURE_NAMES(family + axis);
      input.setAttribute("aria-label", FEATURE_NAMES(family + axis));
      td.appendChild(input);
      tr.appendChild(td);
    });
    body.appendChild(tr);
  });
  state.features
    .filter((f) => !AXIS_FAMILIES.includes(f.slice(0, -1)))
    .forEach((f) => $("#single-columns").appendChild(checkbox("column", f, FEATURE_NAMES(f))));
  $$('#filters [name="column"]').forEach((c) => { c.checked = DEFAULT_COLUMNS.includes(c.value); });

  const columns = () => $$('#filters [name="column"]');
  const flip = (inputs) => {
    const on = !inputs.every((i) => i.checked);
    inputs.forEach((i) => { i.checked = on; });
    scheduleRefresh();
  };
  $$(".family-toggle").forEach((b) => b.addEventListener("click", () =>
    flip(columns().filter((c) => c.value.slice(0, -1) === b.dataset.family))));
  $$(".axis-toggle").forEach((b) => b.addEventListener("click", () =>
    flip(columns().filter((c) => AXIS_FAMILIES.includes(c.value.slice(0, -1)) && c.value.endsWith(b.dataset.axis)))));
  $("#columns-all").addEventListener("click", () => { columns().forEach((c) => { c.checked = true; }); scheduleRefresh(); });
  $("#columns-none").addEventListener("click", () => { columns().forEach((c) => { c.checked = false; }); scheduleRefresh(); });
  $("#add-rule").addEventListener("click", () => { addRule(); });
  $("#filters").addEventListener("input", scheduleRefresh);
  $("#filters").addEventListener("change", scheduleRefresh);
  $("#filters").addEventListener("submit", (e) => e.preventDefault());
  $("#chart-monitor").addEventListener("change", () => loadDataChart());
  $("#chart-feature").addEventListener("change", () => loadDataChart());
  $("#exclude-form").addEventListener("submit", saveExclusion);
  $("#exclude-cancel").addEventListener("click", cancelExclusion);
  $("#export-form").addEventListener("submit", exportCsv);
  $("#preset-form").addEventListener("submit", savePreset);
  $("#preset-load").addEventListener("click", loadPreset);
  $("#preset-delete").addEventListener("click", deletePreset);
}

function addRule(rule = { feature: "IDLE", op: ">", value: "" }) {
  const row = document.createElement("div");
  row.className = "rule";
  const lead = document.createElement("span");
  lead.textContent = t("rule.lead");
  const feature = document.createElement("select");
  feature.name = "rule-feature";
  feature.setAttribute("aria-label", t("rule.column"));
  state.features.forEach((f) => feature.add(new Option(FEATURE_NAMES(f), f)));
  feature.value = rule.feature;
  const op = document.createElement("select");
  op.name = "rule-op";
  op.setAttribute("aria-label", t("rule.comparison"));
  [[">", "rule.gt"], [">=", "rule.gte"], ["<", "rule.lt"], ["<=", "rule.lte"]]
    .forEach(([v, label]) => op.add(new Option(t(label), v)));
  op.value = rule.op;
  const value = document.createElement("input");
  value.type = "number";
  value.step = "any";
  value.name = "rule-value";
  value.setAttribute("aria-label", t("rule.value"));
  value.value = rule.value;
  const remove = document.createElement("button");
  remove.type = "button";
  remove.className = "link";
  remove.textContent = t("rule.remove");
  remove.addEventListener("click", () => { row.remove(); scheduleRefresh(); });
  row.append(lead, feature, op, value, remove);
  $("#rules").appendChild(row);
  if (rule.value === "") value.focus();
  return row;
}

function currentSpec() {
  const form = $("#filters").elements;
  const spec = {
    monitorIds: $$('#filter-monitors input:checked').map((i) => Number(i.value)),
    from: form.namedItem("from").value || null,
    to: form.namedItem("to").value || null,
    features: state.features.filter((f) => $$('#filters [name="column"]:checked').some((c) => c.value === f)),
    rules: $$("#rules .rule")
      .map((r) => ({ feature: $('[name="rule-feature"]', r).value, op: $('[name="rule-op"]', r).value, value: $('[name="rule-value"]', r).value }))
      .filter((r) => r.value !== "" && Number.isFinite(Number(r.value)))
      .map((r) => ({ ...r, value: Number(r.value) })),
    dropIncomplete: form.namedItem("dropIncomplete").checked,
    sampling: form.namedItem("sampling").value,
    applyManualExclusions: form.namedItem("applyManualExclusions").checked,
  };
  const threshold = form.namedItem("threshold").value;
  if (form.namedItem("changeOnly").checked && threshold !== "") {
    spec.changeFilter = { mode: form.namedItem("mode").value, threshold: Number(threshold) };
  }
  return spec;
}

function applySpec(spec) {
  const form = $("#filters").elements;
  $$('#filter-monitors input').forEach((i) => { i.checked = spec.monitorIds.includes(Number(i.value)); });
  form.namedItem("from").value = spec.from || "";
  form.namedItem("to").value = spec.to || "";
  $$('#filters [name="column"]').forEach((c) => { c.checked = spec.features.includes(c.value); });
  $("#rules").replaceChildren();
  (spec.rules || []).forEach((r) => addRule({ ...r, value: String(r.value) }));
  form.namedItem("dropIncomplete").checked = spec.dropIncomplete;
  form.namedItem("sampling").value = spec.sampling;
  form.namedItem("applyManualExclusions").checked = spec.applyManualExclusions;
  form.namedItem("changeOnly").checked = Boolean(spec.changeFilter);
  if (spec.changeFilter) {
    form.namedItem("threshold").value = spec.changeFilter.threshold;
    form.namedItem("mode").value = spec.changeFilter.mode;
  }
  refreshData();
}

async function loadData() {
  buildFilters();
  const monitors = await api("/api/monitors");
  state.monitors = monitors;
  const empty = monitors.length === 0;
  $("#data-empty").hidden = !empty;
  $("#data-layout").hidden = empty;
  $("#preset-form").hidden = empty;
  if (empty) return;
  const box = $("#filter-monitors");
  const known = new Set($$("input", box).map((i) => Number(i.value)));
  const fresh = known.size === 0;
  monitors.filter((m) => !known.has(m.monitorId)).forEach((m) => {
    const name = `${m.machineName || ""} ${m.monitorName || ""}`.trim() || t("monitor.name", { id: m.monitorId });
    box.appendChild(checkbox("monitor", m.monitorId, `${name} (${m.monitorId})`, fresh));
  });
  await Promise.all([loadPresets(), refreshData({ force: true })]);
}

function scheduleRefresh() {
  $("#change-options").hidden = !$("#filters").elements.namedItem("changeOnly").checked;
  clearTimeout(state.data.refreshTimer);
  state.data.refreshTimer = setTimeout(refreshData, 300);
}

function showDataProblem(text) {
  const box = $("#data-problem");
  box.textContent = text ? sentence(I18N.server(text)) : "";
  box.hidden = !text;
}

async function refreshData({ force = false } = {}) {
  $("#change-options").hidden = !$("#filters").elements.namedItem("changeOnly").checked;
  const spec = currentSpec();
  const key = JSON.stringify(spec);
  // a blur after typing fires "change" again; redrawing then would wipe a chart selection in progress
  if (!force && key === state.data.lastSpec) return;
  state.data.lastSpec = key;
  const token = ++state.data.token;
  syncChartPickers(spec);
  try {
    const [preview, exclusions] = await Promise.all([
      api("/api/dataset/preview", { method: "POST", body: { spec, limit: 100 } }),
      api(`/api/exclusions?monitors=${spec.monitorIds.join(",")}`),
    ]);
    if (token !== state.data.token) return;
    showDataProblem(null);
    state.data.exclusions = exclusions;
    renderFunnel(preview.stats, spec);
    renderPreview(preview);
    renderExclusions();
    $("#export-button").disabled = preview.stats.written === 0;
    await loadDataChart(spec);
  } catch (e) {
    if (token !== state.data.token) return;
    state.data.lastSpec = null;
    if (e instanceof ApiError && e.status === 400) {
      showDataProblem(e.message);
      $("#export-button").disabled = true;
    } else {
      throw e;
    }
  }
}

function renderFunnel(s, spec) {
  const steps = [{ label: t("funnel.read"), value: s.read, kind: "total" }];
  if (spec.applyManualExclusions) steps.push({ label: t("funnel.manual"), value: s.excludedManually, kind: "removed" });
  if (spec.rules.length) steps.push({ label: t(spec.rules.length === 1 ? "funnel.rule" : "funnel.rules"), value: s.excludedByRules, kind: "removed" });
  if (spec.sampling !== "raw") steps.push({ label: t(`funnel.${spec.sampling}`), value: s.afterSampling, kind: "total" });
  if (spec.dropIncomplete) steps.push({ label: t("funnel.incomplete"), value: s.droppedIncomplete, kind: "removed" });
  if (spec.changeFilter) steps.push({ label: t("funnel.unchanged"), value: s.droppedUnchanged, kind: "removed" });
  steps.push({ label: t("funnel.result", { count: spec.features.length + 2 }), value: s.written, kind: "result" });

  const scale = Math.max(1, s.read);
  $("#funnel").replaceChildren(...steps.map((step) => {
    const li = document.createElement("li");
    li.className = `step ${step.kind}`;
    const label = document.createElement("span");
    label.className = "step-label";
    label.textContent = step.label;
    const bar = document.createElement("span");
    bar.className = "step-bar";
    const fill = document.createElement("span");
    fill.style.width = `${Math.min(100, (100 * step.value) / scale)}%`;
    bar.appendChild(fill);
    const value = document.createElement("span");
    value.className = "step-value";
    value.textContent = (step.kind === "removed" ? "− " : "") + formatCount(step.value);
    li.append(label, bar, value);
    return li;
  }));
}

function renderPreview(preview) {
  const head = document.createElement("tr");
  preview.columns.forEach((c, i) => {
    const th = document.createElement("th");
    th.scope = "col";
    th.textContent = i === 0 ? t("preview.time") : i === 1 ? t("preview.monitor") : c;
    if (i > 1) { th.title = FEATURE_NAMES(c); th.className = "num"; }
    head.appendChild(th);
  });
  $("#preview thead").replaceChildren(head);
  $("#preview tbody").replaceChildren(...preview.rows.map((row) => {
    const tr = document.createElement("tr");
    row.forEach((v, i) => {
      const td = document.createElement("td");
      td.textContent = i === 0 ? v.slice(0, 16).replace("T", " ") : i === 1 ? v : formatNumber(v, 3);
      if (i > 1) td.className = "num";
      tr.appendChild(td);
    });
    return tr;
  }));
  const total = preview.stats.written;
  $("#preview-caption").textContent = total === 0 ? t("preview.empty")
    : preview.truncated ? t("preview.first", { shown: formatCount(preview.rows.length), total: formatCount(total) })
      : t("preview.all", { total: formatCount(total) });
}

// ── Data chart and manual exclusions ──────────────────────────────────────

function monitorLabel(id) {
  const m = state.monitors.find((x) => x.monitorId === id);
  return m ? `${m.machineName || ""} ${m.monitorName || ""}`.trim() || String(id) : String(id);
}

function syncChartPickers(spec) {
  const fill = (select, values, label) => {
    const previous = select.value;
    select.replaceChildren(...values.map((v) => new Option(label(v), v)));
    if (values.map(String).includes(previous)) select.value = previous;
  };
  fill($("#chart-monitor"), spec.monitorIds, (id) => `${monitorLabel(id)} (${id})`);
  fill($("#chart-feature"), spec.features, FEATURE_NAMES);
}

function hatchPattern(ctx) {
  const tile = document.createElement("canvas");
  tile.width = tile.height = 8;
  const g = tile.getContext("2d");
  g.strokeStyle = "rgba(194, 64, 44, 0.55)";
  g.lineWidth = 1.2;
  g.beginPath();
  g.moveTo(-1, 9); g.lineTo(9, -1);
  g.stroke();
  return ctx.createPattern(tile, "repeat");
}

async function loadDataChart(spec = currentSpec()) {
  const monitorId = Number($("#chart-monitor").value);
  const feature = $("#chart-feature").value;
  cancelExclusion();
  if (!monitorId || !feature) {
    if (state.data.chart) { state.data.chart.destroy(); state.data.chart = null; }
    $("#data-chart-note").textContent = t("dataChart.pick");
    return;
  }
  const width = Math.max(300, $("#data-chart").getBoundingClientRect().width || 700);
  const series = await api("/api/dataset/series", {
    method: "POST", body: { spec, monitorId, plot: [feature], points: Math.min(1500, Math.round(width)) },
  });
  const ranges = state.data.exclusions.filter((e) => e.monitorId === monitorId)
    .map((e) => [Date.parse(e.from) / 1000, Date.parse(e.to) / 1000]);
  $("#data-chart-note").textContent = (series.sourceRows === 0 ? t("dataChart.noRows")
    : series.sourceRows > series.times.length
      ? t("dataChart.averaged", { rows: formatCount(series.sourceRows), points: formatCount(series.times.length) })
      : t("dataChart.rows", { rows: formatCount(series.sourceRows) }))
    + t(ranges.length ? "dataChart.dragHatched" : "dataChart.drag");

  let pattern;
  const opts = {
    width,
    height: 260,
    cursor: { drag: { x: true, y: false, setScale: false } },
    select: { show: true },
    scales: { x: { time: true } },
    legend: { show: true },
    series: [{ label: t("chart.time"), value: I18N.timeValue }, { label: FEATURE_NAMES(feature), stroke: "#0e6a86", width: 1.5, value: (u, v) => formatNumber(v, 3) }],
    axes: [
      { stroke: "#56656c", grid: { stroke: "#dde3e2" }, values: I18N.timeTicks },
      { stroke: "#56656c", grid: { stroke: "#dde3e2" }, values: I18N.numberTicks },
    ],
    hooks: {
      drawClear: [(u) => {
        if (!ranges.length) return;
        pattern = pattern || hatchPattern(u.ctx);
        const { left, top, width: w, height } = u.bbox;
        u.ctx.save();
        u.ctx.fillStyle = pattern;
        ranges.forEach(([from, to]) => {
          const x0 = Math.max(left, u.valToPos(from, "x", true));
          const x1 = Math.min(left + w, u.valToPos(to, "x", true));
          if (x1 > x0) u.ctx.fillRect(x0, top, Math.max(2, x1 - x0), height);
        });
        u.ctx.restore();
      }],
      setSelect: [(u) => {
        if (u.select.width < 4) return;
        const from = u.posToVal(u.select.left, "x");
        const to = u.posToVal(u.select.left + u.select.width, "x");
        offerExclusion(monitorId, new Date(Math.floor(from / 60) * 60000), new Date(Math.ceil(to / 60) * 60000));
      }],
    },
  };
  if (state.data.chart) state.data.chart.destroy();
  // break the line inside excluded ranges instead of drawing straight across the missing rows
  const times = series.times.slice();
  const values = series.values[0].slice();
  if (spec.applyManualExclusions) {
    ranges.forEach(([from, to]) => {
      const mid = (from + to) / 2;
      const at = times.findIndex((t) => t > mid);
      if (at > 0) { times.splice(at, 0, mid); values.splice(at, 0, null); }
    });
  }
  state.data.chart = new uPlot(opts, [times, values], $("#data-chart"));
}

function offerExclusion(monitorId, from, to) {
  state.data.pending = { monitorId, from, to };
  $("#exclude-range").textContent = t("exclude.range", { monitor: `${monitorLabel(monitorId)} (${monitorId})`, from: formatUtc(from), to: formatUtc(to) });
  $("#exclude-form").hidden = false;
  $("#exclude-reason").focus();
}

function cancelExclusion() {
  state.data.pending = null;
  $("#exclude-form").hidden = true;
  $("#exclude-reason").value = "";
  if (state.data.chart) state.data.chart.setSelect({ left: 0, top: 0, width: 0, height: 0 }, false);
}

async function saveExclusion(event) {
  event.preventDefault();
  const pending = state.data.pending;
  const reason = $("#exclude-reason").value.trim();
  if (!pending) return;
  if (!reason) { $("#exclude-reason").classList.add("invalid"); $("#exclude-reason").focus(); return; }
  $("#exclude-reason").classList.remove("invalid");
  try {
    await api("/api/exclusions", {
      method: "POST",
      body: { monitorId: pending.monitorId, from: pending.from.toISOString(), to: pending.to.toISOString(), reason },
    });
    cancelExclusion();
    await refreshData({ force: true });
  } catch (e) {
    if (e instanceof ApiError) showDataProblem(e.message); else throw e;
  }
}

function renderExclusions() {
  const rows = state.data.exclusions.map((e) => {
    const tr = document.createElement("tr");
    const cells = [`${monitorLabel(e.monitorId)} (${e.monitorId})`, formatUtc(new Date(e.from)), formatUtc(new Date(e.to)), e.reason];
    cells.forEach((text, i) => {
      const td = document.createElement("td");
      td.textContent = text;
      if (i === 3) td.className = "note";
      tr.appendChild(td);
    });
    const td = document.createElement("td");
    const remove = document.createElement("button");
    remove.type = "button";
    remove.className = "link";
    remove.textContent = t("exclusions.include");
    remove.addEventListener("click", async () => {
      await api(`/api/exclusions/${e.id}`, { method: "DELETE" });
      await refreshData({ force: true });
    });
    td.appendChild(remove);
    tr.appendChild(td);
    return tr;
  });
  if (!rows.length) {
    const tr = document.createElement("tr");
    const td = document.createElement("td");
    td.colSpan = 5;
    td.className = "note";
    td.textContent = t("exclusions.empty");
    tr.appendChild(td);
    rows.push(tr);
  }
  $("#exclusions tbody").replaceChildren(...rows);
}

// ── Export and presets ────────────────────────────────────────────────────

async function exportCsv(event) {
  event.preventDefault();
  const result = $("#export-result");
  const button = $("#export-button");
  button.disabled = true;
  result.className = "result";
  result.textContent = t("export.preparing");
  try {
    const response = await fetch("/api/dataset/export", {
      method: "POST",
      credentials: "same-origin",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({ spec: currentSpec(), format: EXPORT_FORMATS[$("#export-format").value] }),
    });
    if (!response.ok) {
      const body = await response.json().catch(() => null);
      throw new ApiError(response.status, body);
    }
    const name = (/filename="([^"]+)"/.exec(response.headers.get("Content-Disposition") || "") || [])[1] || "dataset.csv";
    const blob = await response.blob();
    const link = document.createElement("a");
    link.href = URL.createObjectURL(blob);
    link.download = name;
    document.body.appendChild(link);
    link.click();
    link.remove();
    setTimeout(() => URL.revokeObjectURL(link.href), 10000);
    result.className = "result ok";
    result.textContent = t("export.done", { name, size: formatCount(blob.size / 1024) });
  } catch (e) {
    result.className = "result bad";
    result.textContent = e instanceof ApiError ? sentence(I18N.server(e.message)) : t("export.failed");
  } finally {
    button.disabled = false;
  }
}

async function loadPresets(selected) {
  state.data.presets = await api("/api/presets");
  const select = $("#preset-select");
  const options = state.data.presets.map((p) => new Option(p.name, p.name));
  select.replaceChildren(...(options.length ? options : [new Option(t("presets.none"), "")]));
  if (selected) select.value = selected;
  $("#preset-load").disabled = $("#preset-delete").disabled = options.length === 0;
}

async function savePreset(event) {
  event.preventDefault();
  const name = $("#preset-name").value.trim();
  if (!name) { $("#preset-name").classList.add("invalid"); $("#preset-name").focus(); return; }
  $("#preset-name").classList.remove("invalid");
  try {
    await api("/api/presets", { method: "POST", body: { name, spec: currentSpec() } });
    $("#preset-name").value = "";
    await loadPresets(name);
  } catch (e) {
    if (e instanceof ApiError) showDataProblem(e.message); else throw e;
  }
}

function loadPreset() {
  const preset = (state.data.presets || []).find((p) => p.name === $("#preset-select").value);
  if (preset) applySpec(preset.spec);
}

async function deletePreset() {
  const name = $("#preset-select").value;
  if (!name) return;
  await api(`/api/presets?name=${encodeURIComponent(name)}`, { method: "DELETE" });
  await loadPresets();
}

// ── Language ─────────────────────────────────────────────────────────────

/** Saves the language and reloads, so every label, chart and number is redrawn in it. */
async function changeLanguage(event) {
  const select = event.target;
  try {
    await api("/api/language", { method: "PUT", body: { language: select.value } });
    location.reload();
  } catch (e) {
    select.value = I18N.language;
    showNotice(t("lang.failed"));
  }
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
  window.addEventListener("resize", () => { clearTimeout(resizeTimer); resizeTimer = setTimeout(() => { if (state.view === "data") loadDataChart(); else loadChart(); }, 200); });
  fillZones();

  try {
    const status = await api("/api/status");
    I18N.setLanguage(status.language);
    $("#language").value = I18N.language;
    $("#language").addEventListener("change", changeLanguage);
    state.features = await api("/api/features");
    await loadStatus();
  } catch (e) {
    return;
  } finally {
    document.documentElement.removeAttribute("data-i18n-pending");
  }
  const initial = location.hash.slice(1);
  show(["monitors", "collect", "data", "settings"].includes(initial) ? initial : "monitors");

  setInterval(async () => {
    try {
      const status = await api("/api/status");
      if (status.running || state.wasRunning || state.view === "collect") await loadProgress();
    } catch (e) { /* notice already shown */ }
  }, 1000);
  setInterval(() => loadStatus().catch(() => {}), 15000);
});
