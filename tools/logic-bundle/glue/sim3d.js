/**
 * ネイティブ版用のつなぎ：AI撮影環境3Dシミュレーター（Web の routes/_authed/sim3d.index.jsx と components/sim3d/Scene3D.jsx と同じ手順）。
 * setScene で地形・建物データを保持し、描画用の面・線（ローカル座標 m）を返す。
 * compute で立ち位置・日時ごとの太陽・遮蔽・評価と、バス・画角・太陽の描画要素を返す。
 */
import { lightingState, solarPosition } from "@/lib/bus/solar";
import { predictOcclusion } from "@/lib/occlusion/model";
import {
  BUS_SIZE,
  angleDelta,
  buildingVisibility,
  buildingVolumes,
  busPlacement,
  centerElevation,
  elevationAt,
  fieldOfView,
  scoreSimulation,
  toLocal,
} from "@/lib/sim3d/geometry";

let held = null;
const pad = (n) => String(n).padStart(2, "0");
const hhmm = (ms) => {
  if (!ms) return null;
  const d = new Date(ms + 9 * 3600000);
  return `${pad(d.getUTCHours())}:${pad(d.getUTCMinutes())}`;
};
const r2 = (v) => Math.round(v * 100) / 100;

/** 地形・道路・建物・障害物を描画用の面にする。kind: t=地形 r=道路 f=歩道 g=橋 b=建物 u=高さ不明の建物 */
export function setScene(data, lat, lon) {
  held = { data, origin: { lat, lon } };
  const terrain = data?.terrain;
  const osm = data?.osm;
  const origin = held.origin;
  const base = elevationAt(terrain, lat, lon) ?? 0;
  const faces = [];
  const face = (k, pts, id) => faces.push({ k, id: id ?? "", p: pts.flat().map(r2) });
  if (terrain?.ok) {
    const n = terrain.n;
    const grid = [];
    for (let i = 0; i < n; i += 1) {
      grid.push([]);
      for (let j = 0; j < n; j += 1) {
        const la = terrain.center.lat + terrain.dLat * ((i / (n - 1)) * 2 - 1);
        const lo = terrain.center.lon + terrain.dLon * ((j / (n - 1)) * 2 - 1);
        const p = toLocal(la, lo, origin);
        const value = Number.isFinite(terrain.elevations[i][j]) ? terrain.elevations[i][j] : base;
        grid[i].push([p.x, value - base, p.z]);
      }
    }
    for (let i = 0; i < n - 1; i += 1)
      for (let j = 0; j < n - 1; j += 1) face("t", [grid[i][j], grid[i + 1][j], grid[i + 1][j + 1], grid[i][j + 1]]);
  } else {
    face("t", [[-160, 0, -160], [160, 0, -160], [160, 0, 160], [-160, 0, 160]]);
  }
  (osm?.roads || []).forEach((road) => {
    const pts = road.coords.map(([lo, la]) => {
      const p = toLocal(la, lo, origin);
      const g = elevationAt(terrain, la, lo);
      return { x: p.x, z: p.z, y: (Number.isFinite(g) ? g : base) - base + 0.3 };
    });
    if (pts.length < 2) return;
    const foot = road.type === "footway" || road.type === "pedestrian";
    const width = road.width || (foot ? 2.4 : 5.5);
    for (let i = 0; i < pts.length - 1; i += 1) {
      const a = pts[i];
      const b = pts[i + 1];
      const len = Math.hypot(b.x - a.x, b.z - a.z) || 1;
      const nx = (-(b.z - a.z) / len) * (width / 2);
      const nz = ((b.x - a.x) / len) * (width / 2);
      face(foot ? "f" : road.bridge ? "g" : "r", [[a.x + nx, a.y, a.z + nz], [b.x + nx, b.y, b.z + nz], [b.x - nx, b.y, b.z - nz], [a.x - nx, a.y, a.z - nz]]);
    }
  });
  const volumes = buildingVolumes(osm?.buildings || [], terrain, origin, base);
  held.volumes = volumes;
  held.base = base;
  volumes.forEach((v) => {
    const count = v.ring.length;
    if (count < 3) return;
    const gy = v.groundY - base;
    const top = gy + v.height;
    const k = v.known ? "b" : "u";
    for (let i = 0; i < count; i += 1) {
      const a = v.ring[i];
      const b = v.ring[(i + 1) % count];
      face(k, [[a.x, gy, a.z], [b.x, gy, b.z], [b.x, top, b.z], [a.x, top, a.z]], String(v.id));
    }
    face(k, v.ring.map((p) => [p.x, top, p.z]), String(v.id));
  });
  const ground = (la, lo) => {
    const g = elevationAt(terrain, la, lo);
    return (Number.isFinite(g) ? g : base) - base;
  };
  const sprites = [];
  const lines = [];
  (osm?.obstacles || []).forEach((o) => {
    if (o.coords) {
      const pts = o.coords.map(([lo, la]) => {
        const p = toLocal(la, lo, origin);
        return [p.x, ground(la, lo) + (o.height || 1) / 2, p.z].map(r2);
      });
      for (let i = 0; i < pts.length - 1; i += 1) lines.push([...pts[i], ...pts[i + 1]]);
      return;
    }
    if (!Number.isFinite(o.lat)) return;
    const p = toLocal(o.lat, o.lon, origin);
    const h = o.kind === "tree" ? o.height || 7 : o.height || (o.kind === "pole" ? 11 : 6);
    sprites.push({ kind: o.kind, x: r2(p.x), y: r2(ground(o.lat, o.lon)), z: r2(p.z), h, crown: Math.max(1.2, (o.crown || 5) / 2) });
  });
  return { faces, sprites, lines, terrainOk: Boolean(terrain?.ok), base };
}

function sunTimesOf(date, lat, lon) {
  const start = new Date(`${date}T00:00:00+09:00`).getTime();
  let sunrise = null;
  let sunset = null;
  let noon = null;
  let maxAlt = -90;
  let prev = null;
  for (let m = 0; m <= 1440; m += 5) {
    const t = start + m * 60000;
    const alt = solarPosition(t, lat, lon).altitude;
    if (prev !== null) {
      if (prev < 0 && alt >= 0 && sunrise === null) sunrise = t;
      if (prev >= 0 && alt < 0 && sunset === null) sunset = t;
    }
    if (alt > maxAlt) {
      maxAlt = alt;
      noon = t;
    }
    prev = alt;
  }
  return { sunrise: hhmm(sunrise), sunset: hhmm(sunset), noon: hhmm(noon) };
}

/** 立ち位置・日時ごとの計算。input: { setup, date, hour, weather, survey } */
export function compute(input) {
  const { setup, date, hour, weather, survey } = input;
  const data = held?.data || null;
  const origin = held?.origin || { lat: setup.lat, lon: setup.lon };
  const at = new Date(`${date}T${pad(hour)}:00:00+09:00`).getTime();
  const sun = solarPosition(at, setup.lat, setup.lon);
  const light = lightingState({ sunAzimuth: sun.azimuth, sunAltitude: sun.altitude, busHeading: setup.road_bearing });
  const weatherHour = (weather?.hours || []).find((h) => h.hour === hour) || null;
  const base = centerElevation(data?.terrain) ?? 0;
  const drawBase = held?.base ?? 0;
  const fov = fieldOfView({ focal: setup.focal_length, orientation: setup.orientation });
  const bus = busPlacement({ setup, baseElevation: base, terrain: data?.terrain, origin });
  if (held && data && !held.vis) held.vis = buildingVolumes(data?.osm?.buildings || [], data?.terrain, origin, base);
  const volumes = held?.vis || [];
  const ground = data ? elevationAt(data.terrain, setup.lat, setup.lon) : null;
  const eyeAbs = (Number.isFinite(ground) ? ground : base) + (Number(setup.camera_height) || 1.5);
  const visibility = data
    ? buildingVisibility({ eye: { x: 0, y: eyeAbs, z: 0 }, bus: { center: bus.center, heading: bus.heading }, volumes, terrain: data.terrain, origin })
    : null;
  const obstaclesInView = (data?.osm?.obstacles || []).filter((o) => {
    if (!Number.isFinite(o.lat)) return false;
    const p = toLocal(o.lat, o.lon, origin);
    const d = Math.hypot(p.x, p.z);
    if (d < 1.5 || d > 40) return false;
    const bearing = ((Math.atan2(p.x, p.z) * 180) / Math.PI + 360) % 360;
    return angleDelta(bearing, setup.azimuth) <= fov.hFov / 2;
  }).length;
  let occlusion = null;
  if (survey) {
    try {
      occlusion = predictOcclusion({ setup, location: survey, observations: {}, hour });
    } catch {
      occlusion = null;
    }
  }
  const score = scoreSimulation({ visibility, light, occlusion, weather: weatherHour ? { hour: weatherHour } : null, setup, obstaclesInView });

  // 描画要素（Scene3D と同じ寸法）
  const busY = bus.center.y - drawBase;
  const rad = (bus.heading * Math.PI) / 180;
  const dir = [Math.sin(rad), Math.cos(rad)];
  const side = [Math.cos(rad), -Math.sin(rad)];
  const box = (len, wid, y0, y1) => {
    const c = [[1, 1], [1, -1], [-1, -1], [-1, 1]].map(([a, b]) => [bus.center.x + dir[0] * a * len / 2 + side[0] * b * wid / 2, bus.center.z + dir[1] * a * len / 2 + side[1] * b * wid / 2]);
    const out = [];
    for (let i = 0; i < 4; i += 1) {
      const a = c[i];
      const b = c[(i + 1) % 4];
      out.push([a[0], y0, a[1], b[0], y0, b[1], b[0], y1, b[1], a[0], y1, a[1]].map(r2));
    }
    out.push(c.flatMap((p) => [p[0], y1, p[1]]).map(r2));
    return out;
  };
  const eyeY = (Number.isFinite(ground) ? ground : drawBase) - drawBase + (Number(setup.camera_height) || 1.5);
  const az = ((Number(setup.azimuth) || 0) * Math.PI) / 180;
  const pitch = ((Number(setup.camera_pitch) || 0) * Math.PI) / 180;
  const f = [Math.sin(az) * Math.cos(pitch), Math.sin(pitch), Math.cos(az) * Math.cos(pitch)];
  const cross = (a, b) => [a[1] * b[2] - a[2] * b[1], a[2] * b[0] - a[0] * b[2], a[0] * b[1] - a[1] * b[0]];
  const norm = (v) => {
    const l = Math.hypot(...v) || 1;
    return v.map((x) => x / l);
  };
  const right = norm(cross([0, 1, 0], f));
  const camUp = norm(cross(f, right));
  const length = Math.max(24, Math.min(220, (Number(setup.bus_distance) || 30) * 1.6));
  const hw = Math.tan(((fov.hFov * Math.PI) / 180) / 2) * length;
  const hh = Math.tan(((fov.vFov * Math.PI) / 180) / 2) * length;
  const eye = [0, eyeY, 0];
  const centre = eye.map((v, i) => v + f[i] * length);
  const corners = [[1, 1], [-1, 1], [-1, -1], [1, -1]].map(([a, b]) => centre.map((v, i) => r2(v + right[i] * hw * a + camUp[i] * hh * b)));
  const alt = (sun.altitude * Math.PI) / 180;
  const sunAz = (sun.azimuth * Math.PI) / 180;
  const R = 190;
  return {
    sun: { azimuth: sun.azimuth, altitude: sun.altitude },
    sunTimes: sunTimesOf(date, setup.lat, setup.lon),
    light: light ? { label: light.label, detail: light.detail, key: light.key } : null,
    weatherHour,
    base,
    fov,
    visibility,
    obstaclesInView,
    occlusion: occlusion ? { areaRate: occlusion.areaRate, occurrenceRate: occlusion.occurrenceRate, grade: occlusion.grade, distance: occlusion.distance } : null,
    score,
    draw: {
      busBody: box(BUS_SIZE.length, BUS_SIZE.width, busY, busY + BUS_SIZE.height),
      busStripe: box(BUS_SIZE.length * 0.92, BUS_SIZE.width + 0.06, busY + BUS_SIZE.height / 2 + 0.5, busY + BUS_SIZE.height / 2 + 1.0),
      busPoint: [bus.center.x, busY + BUS_SIZE.height / 2, bus.center.z].map(r2),
      eye: eye.map(r2),
      forward: f,
      frustum: corners,
      sunPoint: [Math.sin(sunAz) * Math.cos(alt) * R, Math.sin(alt) * R, Math.cos(sunAz) * Math.cos(alt) * R].map(r2),
      occluders: (visibility?.occluders || []).map((o) => String(o.id)),
    },
  };
}
