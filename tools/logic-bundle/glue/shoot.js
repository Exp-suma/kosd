/**
 * ネイティブ版用のつなぎ：AI撮影支援の状態計算（Web の components/shoot/ShootProvider.jsx と同じ手順）。
 * 経路形状・停留所・日時・気象を受け取り、太陽位置・光線判定・撮影候補地点・時間帯別の評価をまとめて返す。
 * 計算そのものは Web と同じ関数（lib/shoot・lib/bus）を使う。
 */
import { distanceMeters } from "@/lib/bus/geo";
import { compass, solarPosition, sunTimes } from "@/lib/bus/solar";
import { environmentAdvice } from "@/lib/shoot/advice";
import { CHANCE_NOTES, buildChances } from "@/lib/shoot/chances";
import { CONDITION_PRESETS, suggestSpots } from "@/lib/shoot/conditions";
import { evaluateSpot } from "@/lib/shoot/evaluate";
import { EMPTY_FILTERS, SORT_OPTIONS, applyFilters } from "@/lib/shoot/filter";
import { bearingOf, destinationPoint } from "@/lib/shoot/geometry";
import { LIGHT_ORDER, LIGHT_STATES, cameraSideLabel, judgeLight, lightState } from "@/lib/shoot/light";
import { LIGHT_GROUPS, buildRouteAnalysis } from "@/lib/shoot/routeanalysis";
import { bestWindow, buildSchedule } from "@/lib/shoot/schedule";
import { PURPOSES, purposeLabel } from "@/lib/shoot/score";
import { DEFAULT_SHAPE_THRESHOLDS, SHAPE_ICONS, SHAPE_LABELS } from "@/lib/shoot/shape";
import { buildPhotoSpots } from "@/lib/shoot/spotsearch";
import { forecastKind, hourAt, weatherAgeLabel, weatherKey, weatherLabel } from "@/lib/shoot/weather";

const fin = (v) => typeof v === "number" && Number.isFinite(v);
const jstDay = (ts) => new Date(ts + 9 * 3600000).toISOString().slice(0, 10);
const minutesOf = (ts) => {
  const d = new Date(ts + 9 * 3600000);
  return d.getUTCHours() * 60 + d.getUTCMinutes();
};
const hhmm = (minutes) => {
  const c = Math.max(0, Math.min(1439, Math.round(minutes)));
  return `${String(Math.floor(c / 60)).padStart(2, "0")}:${String(c % 60).padStart(2, "0")}`;
};
const comp = (deg) => (fin(deg) ? compass(deg) : "");

/** 画面の選択肢・凡例（定数）。 */
export function meta() {
  return {
    purposes: PURPOSES.map((p) => ({ key: p.key, label: p.label })),
    lightGroups: Object.values(LIGHT_GROUPS),
    lightOrder: LIGHT_ORDER.map((key) => ({ key, label: lightState(key).label, hex: lightState(key).hex })),
    lightStates: LIGHT_STATES,
    shapeLabels: SHAPE_LABELS,
    shapeIcons: SHAPE_ICONS,
    defaultThresholds: DEFAULT_SHAPE_THRESHOLDS,
    conditions: CONDITION_PRESETS.map((p) => ({ key: p.key, label: p.label })),
    sortOptions: SORT_OPTIONS,
    emptyFilters: EMPTY_FILTERS,
    chanceNotes: CHANCE_NOTES,
  };
}

const lightOf = (light) => {
  if (!light) return null;
  const st = lightState(light.shooting?.key);
  return {
    ...light,
    _key: light.shooting?.key || "",
    _label: light.shooting?.label || st.label,
    _hex: st.hex,
    _rank: light.shooting?.rank ?? 0,
    surfaces: (light.surfaces || []).map((s) => ({ ...s, _hex: lightState(s.state?.key).hex })),
  };
};

const weatherOf = (w) => (w ? { ...w, _label: weatherLabel(w.code), _windDir: comp(w.windDir) } : null);

/**
 * @param input { lines, stops, stopNames, route, now, mode, date, time, purpose, stepMin, sunMode, manualSun,
 *                thresholds, weatherDays, vehicles, focusId, filters, condition, chance }
 */
export function compute(input) {
  const q = input || {};
  const now = fin(q.now) ? q.now : Date.now();
  const date = q.date || jstDay(now);
  const purpose = q.purpose || "normal";
  const stepMin = fin(q.stepMin) ? q.stepMin : 30;
  const stops = (q.stops || []).filter((s) => fin(s.lat) && fin(s.lon));
  const center = stops.length ? { lat: stops.reduce((s, p) => s + p.lat, 0) / stops.length, lon: stops.reduce((s, p) => s + p.lon, 0) / stops.length } : null;
  const at = q.mode === "now" ? now : Date.parse(`${date}T${q.time || "12:00"}:00+09:00`) || now;

  const actualSun = center ? solarPosition(at, center.lat, center.lon) : { azimuth: null, altitude: null };
  const times = center ? sunTimes(at, center.lat, center.lon) : { sunrise: null, sunset: null, noon: null };
  const manual = q.sunMode === "manual";
  const sun = manual ? { azimuth: q.manualSun?.azimuth ?? 180, altitude: q.manualSun?.altitude ?? 30, manual: true } : { ...actualSun, manual: false };
  const thresholds = { ...DEFAULT_SHAPE_THRESHOLDS, ...(q.thresholds || {}) };

  const analysis = buildRouteAnalysis({ lines: q.lines || [], sun, thresholds });
  const baseSpots = buildPhotoSpots({ analysis, stops, route: q.route, stepMeters: 110, limit: 16 });
  const days = q.weatherDays || {};
  const weatherPoints = baseSpots.map((s) => ({ key: weatherKey(date, s.lat, s.lon), lat: s.lat, lon: s.lon }));

  let spots = baseSpots.map((spot) => {
    const day = days[weatherKey(date, spot.lat, spot.lon)] || null;
    const hour = hourAt(day, at);
    const roles = spot.roles.map((role) => {
      const light = judgeLight({ sun, heading: spot.heading, cameraHeading: role.cameraHeading, cloudCover: hour?.cloud });
      const result = evaluateSpot({ light, shapeKey: spot.shapeKey, roleKey: role.key, distance: role.distance, sightline: spot.sightline, heading: spot.heading, cameraHeading: role.cameraHeading, shapeNote: spot.shapeNote, purpose });
      return { ...role, light, scoreResult: result, score: result.score };
    });
    const best = roles.reduce((a, b) => (b.score > (a?.score ?? -1) ? b : a), roles[0]);
    const schedule = buildSchedule({ times, lat: spot.lat, lon: spot.lon, heading: spot.heading, cameraHeading: best.cameraHeading, shapeKey: spot.shapeKey, roleKey: best.key, sightline: spot.sightline, shapeNote: spot.shapeNote, distance: best.distance, purpose, weatherDay: day, stepMin: 30 });
    return { ...spot, roles, role: best, camera: best.camera, cameraHeading: best.cameraHeading, light: best.light, day, weather: hour, scoreResult: best.scoreResult, score: best.score, schedule, window: bestWindow(schedule) };
  });
  spots.sort((a, b) => (b.score ?? 0) - (a.score ?? 0));

  const focus = spots.find((s) => s.id === q.focusId) || spots[0] || null;

  // この系統を走行中の車両と、地点ごとの通過の目安（停留所間の距離÷平均15km/h）
  const stopNames = q.stopNames || [];
  const stopIndex = new Map(stopNames.map((name, i) => [name, i]));
  const coordOf = new Map(stops.map((s) => [s.name, s]));
  const routeVehicles = (q.vehicles || []).map((v) => ({ ...v, lat: coordOf.get(v.stop)?.lat ?? null, lon: coordOf.get(v.stop)?.lon ?? null }));
  const passingFor = (spot) => {
    if (!spot) return [];
    const target = spot.stopIndex;
    return routeVehicles
      .map((v) => {
        const from = stopIndex.get(v.stop);
        if (from === undefined) return { vehicle: v.name, status: "unknown", label: "いまの停留所が経路上で確認できません" };
        if (from >= target) return { vehicle: v.name, status: "passed", label: "すでにこの地点より先に進んでいます" };
        let meters = 0;
        for (let i = from + 1; i <= target && i < stops.length; i += 1) meters += distanceMeters(stops[i - 1], stops[i]);
        const minutes = Math.max(1, Math.round((meters / 15000) * 60));
        return { vehicle: v.name, status: "coming", meters, minutes, at: now + minutes * 60000, _at: hhmm(minutesOf(now + minutes * 60000)), label: `約${minutes}分後に通過（停留所間の距離÷平均15km/hの目安）` };
      })
      .sort((a, b) => (a.minutes ?? 9999) - (b.minutes ?? 9999));
  };

  // 絞り込み・条件検索・撮影チャンス（指定されたときだけ）
  const filteredIds = q.filters ? applyFilters(spots, { ...EMPTY_FILTERS, ...q.filters }).map((s) => s.id) : null;
  const suggestions = q.condition ? suggestSpots(spots, q.condition, { sun, limit: 3 }).map(({ spot, role, reasons }) => ({ id: spot.id, name: spot.name, score: spot.score, roleKey: role.key, roleLabel: role.label, reasons })) : null;
  const chances = q.chance ? buildChances(spots, { minScore: q.chance.minScore ?? 60, fromMin: q.chance.fromMin ?? null, toMin: q.chance.toMin ?? null }) : null;

  const focusSchedule = focus
    ? buildSchedule({ times, lat: focus.lat, lon: focus.lon, heading: focus.heading, cameraHeading: focus.cameraHeading, shapeKey: focus.shapeKey, roleKey: focus.role?.key, sightline: focus.sightline, shapeNote: focus.shapeNote, distance: focus.role?.distance, purpose, weatherDay: focus.day, stepMin })
    : [];
  const pointWeather = focus ? hourAt(focus.day, at) : null;

  const start = times.sunrise ? minutesOf(times.sunrise) : null;
  const end = times.sunset ? minutesOf(times.sunset) : null;
  const from = start === null ? 330 : Math.max(0, start - 60);
  const to = end === null ? 1140 : Math.min(1439, end + 30);

  const sunStart = center && fin(sun.azimuth) ? destinationPoint(center, sun.azimuth, 500) : null;
  const nearestStops = (p) => stops.map((s) => ({ name: s.name, lat: s.lat, lon: s.lon, distance: distanceMeters(p, s) })).sort((a, b) => a.distance - b.distance).slice(0, 12);

  const outSpot = (s) => ({
    id: s.id, name: s.name, lat: s.lat, lon: s.lon, route: s.route, heading: s.heading, cameraHeading: s.cameraHeading, camera: s.camera,
    shapeKey: s.shapeKey, shapeLabel: s.shapeLabel, shapeTurn: s.shapeTurn, shapeNote: s.shapeNote, nearestStop: s.nearestStop, stopDistance: s.stopDistance, stopIndex: s.stopIndex,
    role: s.role ? { key: s.role.key, label: s.role.label, distance: s.role.distance } : null,
    roles: (s.roles || []).map((r) => ({ key: r.key, label: r.label, score: r.score, _lightLabel: r.light?.shooting?.label || "" })),
    light: lightOf(s.light), weather: weatherOf(s.weather), day: s.day ? { source: s.day.source, fetchedAt: s.day.fetchedAt, _age: weatherAgeLabel(s.day.fetchedAt) } : null,
    scoreResult: s.scoreResult, score: s.score, window: s.window,
    scheduleTop: (s.schedule || []).slice().sort((a, b) => b.score - a.score).slice(0, 3).map((r) => ({ at: r.at, time: r.time, label: r.label, key: r.key, score: r.score })),
    _heading: comp(s.heading), _camera: comp(s.cameraHeading), _side: cameraSideLabel(s.cameraHeading, s.heading),
    _busEnd: destinationPoint(s, s.heading, 45), _cameraEnd: s.camera && fin(s.camera.lat) ? destinationPoint(s.camera, s.cameraHeading, 45) : null,
    _comingMin: passingFor(s).find((p) => p.status === "coming")?.minutes ?? null,
  });

  return {
    at, date, _at: new Date(at + 9 * 3600000).toISOString().slice(0, 16).replace("T", " "), minutes: minutesOf(at), _minutes: hhmm(minutesOf(at)),
    dayRange: { start: from, end: Math.max(from + 60, to), _start: hhmm(from), _end: hhmm(Math.max(from + 60, to)) },
    center, sun: { ...sun, _compass: comp(sun.azimuth) }, actualSun,
    times: { ...times, _sunrise: times.sunrise ? hhmm(minutesOf(times.sunrise)) : "—", _noon: times.noon ? hhmm(minutesOf(times.noon)) : "—", _sunset: times.sunset ? hhmm(minutesOf(times.sunset)) : "—", _hours: times.sunrise && times.sunset ? ((times.sunset - times.sunrise) / 3600000).toFixed(1) : "—" },
    sunStart,
    analysis: { segments: (analysis.segments || []).map((s) => ({ coords: s.coords, group: s.group })), shapes: (analysis.shapes || []).filter((m) => fin(m.lat) && fin(m.lon)).map((m) => ({ lat: m.lat, lon: m.lon, key: m.key, label: m.label, turn: m.turn, icon: SHAPE_ICONS[m.key] || "?" })), occurrences: analysis.occurrences || {} },
    spots: spots.map(outSpot),
    focusId: focus?.id || "",
    focusPassing: passingFor(focus),
    focusSchedule: focusSchedule.map((r) => ({ at: r.at, time: r.time, key: r.key, label: r.label, altitude: r.altitude, score: r.score, partial: r.partial, _hex: lightState(r.key).hex, _day: jstDay(r.at) })),
    focusWindow: bestWindow(focusSchedule),
    focusNearStops: focus ? nearestStops(focus) : [],
    pointWeather: weatherOf(pointWeather),
    advice: environmentAdvice({ weather: pointWeather, lightKey: focus?.light?.shooting?.key }),
    forecastKind: forecastKind(date, jstDay(Date.now())),
    purposeLabel: purposeLabel(purpose),
    weatherPoints, routeVehicles, filteredIds, suggestions, chances,
  };
}

/** 地図上で太陽を動かしたときの方位（中心から見た向き）。 */
export function sunBearing(center, point) {
  return bearingOf(center, point);
}
