/**
 * ネイティブ版用：統合乗換案内（奈良交通バス＋鉄道・他社交通機関）。
 *
 * サーバーの既存の検索（ハイブリッド検索・奈良交通の区間検索・停留所検索）を組み合わせ、
 *   1) サーバーのハイブリッド検索の結果（バス直行・鉄道・サーバーが組めた乗継）
 *   2) バス→バスの乗換（主要停留所・のりば名に出てくる停留所で乗り継ぐ）
 *   3) バス⇄鉄道の乗継（鉄道の経路の前後を、駅前の停留所からの奈良交通バスでつなぐ）
 * を 1 つの候補一覧にまとめる。時刻・運賃・停留所はすべて取得した実データだけを使い、推測で補わない。
 *
 * 通信はアプリ側が行う。step(state) は「次に必要な問い合わせ」を返し、結果が state.res に入ったら
 * もう一度呼ぶ……を繰り返して、done になったら候補を返す（同期関数のまま端末・PC の両方で動かすため）。
 */
import { decorateOptions } from "./transit";
import { haversineMeters, walkSeconds } from "@/lib/transit/geo";

const MAX_WALK_TO_STATION = 900;
const MAX_HUBS = 4;
const MAX_BASES = 2;

const text = (v) => {
  const s = String(v ?? "").trim();
  return !s || s === "null" || s === "undefined" ? "" : s;
};
const fin = (v) => typeof v === "number" && Number.isFinite(v);
/** 全角英数を半角にし、空白を除く（「ＪＲ奈良駅」と「JR奈良駅」を同じ名前として扱う）。 */
export const normName = (v) =>
  text(v)
    .replace(/[Ａ-Ｚａ-ｚ０-９]/g, (c) => String.fromCharCode(c.charCodeAt(0) - 0xfee0))
    .replace(/[\s　]+/g, "");
const secsOf = (hhmm) => {
  const m = /^(\d{1,2}):(\d{2})$/.exec(text(hhmm));
  return m ? Number(m[1]) * 3600 + Number(m[2]) * 60 : null;
};
const clockOf = (secs) => {
  const t = ((Math.round(secs) % 86400) + 86400) % 86400;
  return `${String(Math.floor(t / 3600)).padStart(2, "0")}:${String(Math.floor((t % 3600) / 60)).padStart(2, "0")}`;
};
const failed = (r) => !r || typeof r !== "object" || "__error" in r;
const shortPlatform = (raw) => {
  const m = /のりば\s*[0-9０-９]+/.exec(text(raw));
  return m ? m[0].replace(/\s+/g, "") : "";
};

/** 主要な乗換停留所（のりば名から候補が取れないときの手がかり。実際に便があるかは必ず API で確認する）。 */
const MAJOR_HUBS = [
  ["近鉄奈良駅", 34.6843, 135.8279], ["JR奈良駅", 34.6805, 135.8189], ["大和西大寺駅", 34.6937, 135.7836], ["学園前駅", 34.6989, 135.7523],
  ["高の原駅", 34.7279, 135.7918], ["富雄駅", 34.6945, 135.7341], ["生駒駅", 34.6934, 135.6985], ["学研奈良登美ヶ丘駅", 34.7268, 135.7493],
  ["近鉄郡山駅", 34.6482, 135.7807], ["天理駅", 34.6012, 135.8297], ["王寺駅", 34.5978, 135.7044], ["法隆寺駅", 34.6016, 135.7389],
  ["大和八木駅", 34.5129, 135.7928], ["桜井駅", 34.5131, 135.8463], ["榛原駅", 34.5298, 135.9533], ["橿原神宮前駅", 34.4849, 135.7941],
  ["五位堂駅", 34.5389, 135.7272], ["近鉄高田駅", 34.5153, 135.7379], ["五条バスセンター", 34.3517, 135.6946], ["大淀バスセンター", 34.3907, 135.7906],
  ["下市口駅", 34.3839, 135.7921], ["木津駅", 34.7354, 135.8243], ["祝園駅", 34.7646, 135.7889],
];

/* ── 奈良交通の区間（公式 API の実データ）を共通の区間形式にする ── */
function busLegs(journey, codes = {}) {
  return (journey?.legs || [])
    .map((l, i, all) => {
      const dep = secsOf(l.departureTime);
      let arr = secsOf(l.arrivalTime);
      if (dep != null && arr != null && arr < dep) arr += 86400;
      if (!l.fromStop || !l.toStop) return null;
      return {
        kind: "transit",
        mode: "bus",
        routeName: l.indicator ? `${l.indicator}系統` : text(l.routeCode),
        trainType: "",
        headsign: text(l.destination),
        from: { id: `bus:${i === 0 && codes.from ? codes.from : l.fromStop}`, name: l.fromStop, platformCode: shortPlatform(l.platform), lat: i === 0 ? codes.fromLat ?? null : null, lon: i === 0 ? codes.fromLon ?? null : null },
        to: { id: `bus:${i === all.length - 1 && codes.to ? codes.to : l.toStop}`, name: l.toStop, platformCode: "", lat: i === all.length - 1 ? codes.toLat ?? null : null, lon: i === all.length - 1 ? codes.toLon ?? null : null },
        departureSecs: dep,
        arrivalSecs: arr,
        feedId: "narakotsu-navi",
        feedName: "奈良交通（奈良バスなびweb）",
        operator: { name: "奈良交通", nameShort: "奈良交通" },
        fare: fin(l.fare) ? l.fare : null,
        headwayBased: false,
        nara: true,
        platform: text(l.platform),
        via: text(l.via),
      };
    })
    .filter(Boolean);
}

/** 区間の時刻を基準時刻と同じ日にそろえる（深夜をまたぐ便）。 */
function align(legs, ref) {
  if (!fin(ref)) return legs;
  const shift = (s) => {
    if (!fin(s)) return s;
    let v = s;
    while (v < ref - 6 * 3600) v += 86400;
    while (v > ref + 18 * 3600) v -= 86400;
    return v;
  };
  return legs.map((l) => ({ ...l, departureSecs: shift(l.departureSecs), arrivalSecs: shift(l.arrivalSecs) }));
}

function walkLeg(from, to, departureSecs, seconds, meters) {
  return {
    kind: "walk", mode: "walk", routeName: "", trainType: "", headsign: "", from, to,
    departureSecs: fin(departureSecs) ? departureSecs : null,
    arrivalSecs: fin(departureSecs) ? departureSecs + seconds : null,
    feedId: "", feedName: "", operator: null, walkMeters: fin(meters) ? Math.round(meters) : null, headwayBased: false,
  };
}

function summarize(legs) {
  const transit = legs.filter((l) => l.kind === "transit");
  const first = legs[0] || null;
  const last = legs[legs.length - 1] || null;
  const walkSecs = legs.filter((l) => l.kind === "walk").reduce((s, l) => s + Math.max(0, (l.arrivalSecs ?? 0) - (l.departureSecs ?? 0)), 0);
  const transfers = [];
  for (let i = 1; i < transit.length; i += 1) {
    const a = transit[i - 1];
    const b = transit[i];
    transfers.push({ name: a.to?.name || b.from?.name || "", nextRoute: b.routeName, waitSecs: fin(a.arrivalSecs) && fin(b.departureSecs) ? b.departureSecs - a.arrivalSecs : null });
  }
  return {
    departureSecs: first?.departureSecs ?? null,
    arrivalSecs: last?.arrivalSecs ?? null,
    durationSecs: fin(first?.departureSecs) && fin(last?.arrivalSecs) ? last.arrivalSecs - first.departureSecs : null,
    walkSecs,
    transferCount: Math.max(0, transit.length - 1),
    transfers,
  };
}

const compositionOf = (legs) => {
  if (!legs.some((l) => l.kind === "transit")) return "walk";
  const bus = legs.some((l) => l.nara);
  const other = legs.some((l) => l.kind === "transit" && !l.nara);
  return bus && other ? "hybrid" : bus ? "bus" : "transit";
};

/** 奈良交通区間の運賃合計（全区間が分かるときだけ）。 */
function busFareOf(legs) {
  const fares = legs.filter((l) => l.nara).map((l) => l.fare);
  if (!fares.length || fares.some((f) => !fin(f))) return null;
  return fares.reduce((s, f) => s + f, 0);
}
/** 合計運賃（バス・鉄道の両方が分かるときだけ合計する。片方でも不明なら出さない）。 */
function fareOf(legs, baseTicket) {
  const bus = busFareOf(legs);
  const hasBus = legs.some((l) => l.nara);
  const hasOther = legs.some((l) => l.kind === "transit" && !l.nara);
  if (hasBus && bus == null) return null;
  if (hasOther && !fin(baseTicket)) return null;
  return { currency: "JPY", ticket: (fin(baseTicket) ? baseTicket : 0) + (bus ?? 0), ic: null };
}

function chronological(legs) {
  for (let i = 1; i < legs.length; i += 1) {
    const a = legs[i - 1];
    const b = legs[i];
    if (fin(a.arrivalSecs) && fin(b.departureSecs) && b.departureSecs < a.arrivalSecs - 30) return false;
  }
  return true;
}

function makeOption(id, legs, extra = {}) {
  const s = summarize(legs);
  if (!fin(s.departureSecs) || !fin(s.arrivalSecs) || s.arrivalSecs <= s.departureSecs || !chronological(legs)) return null;
  const points = [];
  const push = (p, role) => {
    if (!p || !fin(p.lat) || !fin(p.lon) || points.some((x) => x.id === p.id)) return;
    points.push({ id: p.id, name: p.name, lat: p.lat, lon: p.lon, role });
  };
  (extra.basePoints || []).forEach((p) => push(p, p.role || "stop"));
  legs.forEach((l, i) => {
    push(l.from, i === 0 ? "origin" : "stop");
    push(l.to, i === legs.length - 1 ? "destination" : "stop");
  });
  const busPairs = legs.filter((l) => l.nara && fin(l.from?.lat) && fin(l.to?.lat)).map((l) => ({ from: { lat: l.from.lat, lon: l.from.lon }, to: { lat: l.to.lat, lon: l.to.lon } }));
  return {
    id,
    rank: 0,
    composition: compositionOf(legs),
    recommended: false,
    confidence: extra.confidence || "",
    strategy: extra.strategy || "",
    ...s,
    extraWalkSecs: extra.extraWalkSecs || 0,
    fare: fareOf(legs, extra.baseTicket),
    busFare: busFareOf(legs),
    legs,
    operators: legs.filter((l) => l.kind === "transit").map((l) => ({ name: l.operator?.name || l.feedName || "", nameShort: l.operator?.nameShort || l.operator?.name || l.feedName || "", routeName: l.routeName }))
      .filter((o, i, all) => o.nameShort && all.findIndex((x) => x.nameShort === o.nameShort) === i),
    map: points.length ? { bounds: null, points, segments: (extra.baseSegments || []).filter((seg) => seg.kind !== "walk"), busPairs } : null,
    hybridNote: extra.note || "",
    planner: extra.planner || "",
  };
}

/* ── 候補地点 ── */
function partsOfPoleName(name) {
  const out = [];
  (text(name).match(/[（(][^）)]+[）)]/g) || []).forEach((g) => {
    g.replace(/[（()）]/g, "").split(/[・、,／/]/).forEach((part) => {
      const n = part.replace(/方面$/, "").replace(/^(東|西|南|北)向き$/, "").trim();
      if (n && n.length <= 20) out.push(n);
    });
  });
  return out;
}

/** バス→バス乗換の候補停留所名（のりば名に出る行先 → 近い主要停留所の順）。 */
function hubNameCandidates(F, T) {
  const names = [];
  const add = (n) => {
    const k = normName(n);
    if (!k || k === normName(F.label) || k === normName(T.label) || names.some((x) => normName(x) === k)) return;
    names.push(n);
  };
  const fromPoles = (F.poles || []).flatMap((p) => partsOfPoleName(p.name));
  const toPoles = (T.poles || []).flatMap((p) => partsOfPoleName(p.name));
  // 両方ののりば名に出る停留所を最優先、次に駅・バスセンター
  fromPoles.filter((n) => toPoles.some((m) => normName(m) === normName(n))).forEach(add);
  [...fromPoles, ...toPoles].filter((n) => /駅$|バスセンター|営業所/.test(n)).forEach(add);
  if (fin(F.lat) && fin(T.lat)) {
    MAJOR_HUBS.map(([name, lat, lon]) => ({ name, d: haversineMeters(F, { lat, lon }) + haversineMeters(T, { lat, lon }) }))
      .sort((a, b) => a.d - b.d).slice(0, 3).forEach((h) => add(h.name));
  } else {
    ["近鉄奈良駅", "JR奈良駅", "大和八木駅"].forEach(add);
  }
  return names.slice(0, 6);
}

/** 鉄道の駅名から、駅前にある奈良交通の停留所名の候補を作る（事業者名つきを優先）。 */
function stationStopNames(stationName, leg) {
  const base = normName(stationName).replace(/駅$/, "");
  const hint = `${text(leg?.feedName)} ${text(leg?.routeName)} ${text(leg?.operator?.name)}`;
  const names = [];
  if (/JR|ＪＲ|西日本/.test(hint) && !/^JR/.test(base)) names.push(`JR${base}駅`);
  if (/近鉄|近畿日本/.test(hint) && !/^近鉄/.test(base)) names.push(`近鉄${base}駅`);
  names.push(`${base}駅`, `JR${base}駅`, `近鉄${base}駅`, `${base}駅前`, base);
  return [...new Set(names)];
}

/** 同名の駅が全国にあるため、奈良交通の営業エリア（奈良県周辺）に最も近いものを選ぶ。 */
const NARA_CENTER = { lat: 34.6, lon: 135.8 };
function pickStation(places, baseName) {
  const hits = (places || []).filter((p) => p.kind === "station" && fin(p.lat) && fin(p.lon) && normName(p.name).replace(/駅$/, "") === baseName)
    .map((p) => ({ p, d: haversineMeters(p, NARA_CENTER) })).filter((x) => fin(x.d) && x.d <= 150000).sort((a, b) => a.d - b.d);
  return hits[0]?.p || null;
}

/* ── 本体 ── */
function compute(q, get) {
  const buffer = fin(q.transferBuffer) ? q.transferBuffer : 4;
  const walkSpeed = fin(q.walkSpeed) ? q.walkSpeed : 80;
  const maxTransfers = fin(q.maxTransfers) ? q.maxTransfers : 3;
  const timeBased = q.type === "departure" || q.type === "arrival";
  const baseTime = timeBased && secsOf(q.time) != null ? text(q.time) : q.nowClock || "09:00";
  const t0 = secsOf(baseTime);
  const arrivalBased = q.type === "arrival";
  const notices = [];
  let incomplete = false;
  const need = (id, fn, data) => {
    const r = get(id, fn, data);
    if (r === undefined) incomplete = true;
    return r;
  };
  const stopInfo = (code) => need(`stop:${code}`, "nara/server-fns#getNaraStop", { code: String(code) });

  /* 1. 出発地・目的地（奈良交通の停留所は座標とのりば名を取得する） */
  const endpoint = (ep, role) => {
    if (!ep) return null;
    const isBus = ep.kind === "bus" || text(ep.endpoint).startsWith("bus:");
    if (!isBus) return { kind: "place", code: "", label: text(ep.label), endpoint: text(ep.endpoint), lat: fin(ep.lat) ? ep.lat : null, lon: fin(ep.lon) ? ep.lon : null, poles: [] };
    const code = text(ep.code) || text(ep.endpoint).replace(/^bus:/, "");
    const info = stopInfo(code);
    let lat = fin(ep.lat) ? ep.lat : null;
    let lon = fin(ep.lon) ? ep.lon : null;
    let poles = [];
    if (info && !failed(info)) {
      if (fin(info.stop?.lat) && fin(info.stop?.lon)) { lat = info.stop.lat; lon = info.stop.lon; }
      poles = info.poles || [];
    }
    // 「○○駅」という停留所でのりば座標が無い場合は、同名の駅の実座標を使う
    if (info !== undefined && lat == null && /駅$/.test(normName(ep.label))) {
      const base = normName(ep.label).replace(/^(JR|近鉄)/, "").replace(/駅$/, "");
      const st = need(`stn:${base}`, "transit/server-fns#suggestTransitPlaces", { q: base, limit: 6 });
      const hit = !failed(st) ? pickStation(st.places, base) : null;
      if (hit) { lat = hit.lat; lon = hit.lon; }
    }
    return { kind: "bus", code, label: text(ep.label) || `停留所 ${code}`, endpoint: `bus:${code}`, lat, lon, poles, role };
  };
  const F = endpoint(q.from, "from");
  const T = endpoint(q.to, "to");
  if (!F || !T) return { error: "出発地と目的地を指定してください", options: [], notices };
  if (incomplete) return { incomplete: true, progress: "停留所の位置を確認しています" };

  /* 2. サーバーのハイブリッド検索 */
  const payload = (E) => ({ kind: E.kind, code: E.code, endpoint: E.endpoint, label: E.label, ...(fin(E.lat) ? { lat: E.lat, lon: E.lon } : {}) });
  const hybridData = { from: payload(F), to: payload(T), date: q.date, ...(timeBased ? { time: baseTime } : {}), type: q.type || "departure", walkSpeed, transferBuffer: buffer, maxTransfers, numItineraries: 4 };
  let H = need("hybrid", "transit/hybrid-fns#searchJourneyRoutes", hybridData);
  // サーバー内の一時的な不整合（キャッシュの形式違い）で失敗したときは、時刻を 1 分ずらして取り直す
  if (H !== undefined && timeBased && (failed(H) || /Cannot read|undefined \(reading/.test(text(H.error)))) {
    H = need("hybrid2", "transit/hybrid-fns#searchJourneyRoutes", { ...hybridData, time: clockOf(secsOf(baseTime) + 60) });
  }
  const hybridOptions = H && !failed(H) ? (H.options || []).map((o) => ({ ...o, planner: "server" })) : [];
  if (H !== undefined) (H?.notices || []).filter((n) => !/undefined/.test(text(n.message))).forEach((n) => notices.push(n));

  /* 3. バス→バスの乗換（両端が奈良交通の停留所のとき） */
  const transferOptions = [];
  if (F.kind === "bus" && T.kind === "bus" && F.code !== T.code && maxTransfers >= 1 && q.type !== "last") {
    const hubs = [];
    hubNameCandidates(F, T).forEach((name) => {
      const r = need(`hubq:${normName(name)}`, "nara/server-fns#searchNaraStops", { query: name, limit: 8 });
      if (failed(r)) return;
      const hit = (r.items || []).find((it) => normName(it.name) === normName(name));
      if (hit && hit.code !== F.code && hit.code !== T.code && !hubs.some((h) => h.code === hit.code)) hubs.push({ code: String(hit.code), name: hit.name });
    });
    const searchTime = q.type === "arrival" ? clockOf(Math.max(4 * 3600, t0 - 150 * 60)) : q.type === "first" ? "04:30" : clockOf(t0 + 60);
    hubs.slice(0, MAX_HUBS).forEach((hub) => {
      const r = need(`tr:${hub.code}:${searchTime}`, "nara/server-fns#searchNaraJourneys", { fromCode: F.code, toCode: T.code, date: q.date, time: searchTime, type: "1", mode: "transfer", transferCode: hub.code, transferMinutes: Math.max(1, buffer) });
      if (failed(r)) return;
      let journeys = r.journeys || [];
      if (q.type === "arrival") journeys = journeys.filter((j) => (secsOf(j.arrivalTime) ?? 1e9) <= t0).slice(-2);
      journeys.forEach((j, i) => {
        const legs = align(busLegs(j, { from: F.code, fromLat: F.lat, fromLon: F.lon, to: T.code, toLat: T.lat, toLon: T.lon }), t0);
        const opt = makeOption(`bt-${hub.code}-${i}-${j.departureTime}`, legs, { strategy: "bus-transfer", planner: "bus-transfer", confidence: "high", note: `${hub.name}で奈良交通バスを乗り継ぎ（乗換 ${j.transfer?.waitMinutes ?? "—"}分）` });
        if (opt) transferOptions.push(opt);
      });
    });
  }

  /* 4. バス⇄鉄道の乗継（鉄道の経路の前後を、駅前停留所からの奈良交通バスでつなぐ） */
  const relayOptions = [];
  const connectSecs = (hub) => (fin(hub.distance) ? walkSeconds(hub.distance, walkSpeed) : 180);
  const pointOf = (base, station) => (base.map?.points || []).find((p) => p.id === station?.id) || (base.map?.points || []).find((p) => p.name === station?.name) || null;

  /** 駅の徒歩圏にある奈良交通の停留所（名前が一致し、座標が取れる場合は距離も確認する）。 */
  const stationStop = (station, leg, point, avoidCode) => {
    if (!station?.name) return null;
    const wanted = stationStopNames(station.name, leg);
    const baseName = normName(station.name).replace(/駅$/, "");
    const r = need(`sq:${baseName}`, "nara/server-fns#searchNaraStops", { query: `${baseName}駅`, limit: 20 });
    if (failed(r)) return null;
    const items = r.items || [];
    for (const name of wanted) {
      const hit = items.find((it) => normName(it.name) === normName(name));
      if (!hit) continue;
      if (String(hit.code) === String(avoidCode)) return { same: true };
      let lat = null;
      let lon = null;
      const info = stopInfo(hit.code);
      if (info === undefined) return null;
      if (!failed(info) && fin(info.stop?.lat)) { lat = info.stop.lat; lon = info.stop.lon; }
      let distance = null;
      if (fin(lat) && point && fin(point.lat)) {
        distance = haversineMeters({ lat, lon }, point);
        if (!fin(distance) || distance > MAX_WALK_TO_STATION) continue; // 同名でも離れた停留所は使わない
      }
      return { code: String(hit.code), name: hit.name, lat, lon, distance };
    }
    return null;
  };
  /**
   * 奈良交通の区間検索（直行便）。サーバー内でハイブリッド検索と同じキャッシュを別の形式で共有しているため、
   * 同じ時刻で問い合わせると壊れた応答になることがある。問い合わせ時刻をずらし、失敗したら別の時刻で取り直す。
   */
  const naraDirect = (tag, from, to, secs, type, offsets) => {
    for (const off of offsets) {
      const time = clockOf(secs + off);
      const r = need(`${tag}:${from.code}:${to.code}:${time}`, "nara/server-fns#searchNaraJourneys", { fromCode: from.code, toCode: to.code, date: q.date, time, type, mode: "direct" });
      if (r === undefined) return undefined;
      if (!failed(r) && Array.isArray(r.journeys)) return r.journeys;
    }
    return [];
  };
  const legsOf = (j, from, to, ref) => align(busLegs(j, { from: from.code, fromLat: from.lat, fromLon: from.lon, to: to.code, toLat: to.lat, toLon: to.lon }), ref);
  /** limit までに着く最後のバス（公式APIの到着時刻指定）。 */
  const busBefore = (from, to, limit) => {
    if (!fin(limit)) return null;
    const journeys = naraDirect("fm", from, to, limit, "2", [120, 300]);
    if (!journeys) return null;
    const picks = journeys.map((j) => legsOf(j, from, to, limit))
      .filter((legs) => legs.length && fin(legs[legs.length - 1].arrivalSecs) && legs[legs.length - 1].arrivalSecs <= limit && legs[legs.length - 1].arrivalSecs >= limit - 2 * 3600)
      .sort((x, y) => y[y.length - 1].arrivalSecs - x[x.length - 1].arrivalSecs);
    return picks[0] || null;
  };
  /** start 以降に出る最初のバス（公式APIの出発時刻指定）。 */
  const busAfter = (from, to, start) => {
    if (!fin(start)) return null;
    const journeys = naraDirect("lm", from, to, start, "1", [-120, -300]);
    if (!journeys) return null;
    const picks = journeys.map((j) => legsOf(j, from, to, start))
      .filter((legs) => legs.length && fin(legs[0].departureSecs) && legs[0].departureSecs >= start - 30 && legs[0].departureSecs <= start + 3 * 3600)
      .sort((x, y) => x[0].departureSecs - y[0].departureSecs);
    return picks[0] || null;
  };
  /** 鉄道などの経路（Transit API の実データ）。 */
  const railSearch = (id, from, to, fromLabel, toLabel, time, type) => {
    const r = need(id, "transit/server-fns#searchTransitJourneys", { from, to, fromLabel, toLabel, date: q.date, time, type, numItineraries: 3 });
    if (failed(r) || r.ok === false) return [];
    return (r.options || []).filter((o) => (o.legs || []).some((l) => l.kind === "transit")).slice(0, MAX_BASES);
  };
  const railEndpoint = (E) => (E.kind === "bus" ? (fin(E.lat) ? `geo:${E.lat},${E.lon}` : null) : E.endpoint);

  /** 鉄道の経路 base の前後にバス区間を付けて 1 つの候補にする。 */
  const compose = (base, head, tail, tag) => {
    if (!head && !tail) return;
    const firstIdx = base.legs.findIndex((l) => l.kind === "transit");
    let lastIdx = -1;
    base.legs.forEach((l, i) => { if (l.kind === "transit") lastIdx = i; });
    const firstTrain = base.legs[firstIdx];
    const lastTrain = base.legs[lastIdx];
    const legs = [];
    const notes = [];
    let extraWalk = 0;
    if (head) {
      legs.push(...head.legs);
      legs.push(walkLeg({ id: `bus:${head.hub.code}`, name: head.hub.name, platformCode: "" }, { id: firstTrain.from?.id, name: firstTrain.from?.name, platformCode: "" }, head.legs[head.legs.length - 1].arrivalSecs, connectSecs(head.hub), head.hub.distance));
      notes.push(`${head.hub.name}停留所で降り、${normName(firstTrain.from?.name).replace(/駅$/, "")}駅から列車に乗り継ぎ`);
      legs.push(...base.legs.slice(firstIdx, tail ? lastIdx + 1 : undefined));
    } else {
      legs.push(...base.legs.slice(0, tail ? lastIdx + 1 : undefined));
      const p = pointOf(base, firstTrain.from);
      if (F.kind === "bus" && fin(F.lat) && p && fin(p.lat) && firstIdx === 0) extraWalk += walkSeconds(haversineMeters(F, p), walkSpeed);
    }
    if (tail) {
      legs.push(walkLeg({ id: lastTrain.to?.id, name: lastTrain.to?.name, platformCode: "" }, { id: `bus:${tail.hub.code}`, name: tail.hub.name, platformCode: "" }, lastTrain.arrivalSecs, connectSecs(tail.hub), tail.hub.distance));
      legs.push(...tail.legs);
      notes.push(`${normName(lastTrain.to?.name).replace(/駅$/, "")}駅で降り、${tail.hub.name}停留所からバスに乗り継ぎ`);
    } else {
      const p = pointOf(base, lastTrain.to);
      if (T.kind === "bus" && fin(T.lat) && p && fin(p.lat) && lastIdx === base.legs.length - 1) extraWalk += walkSeconds(haversineMeters(p, T), walkSpeed);
    }
    const opt = makeOption(`relay-${tag}-${head ? head.hub.code : "x"}-${tail ? tail.hub.code : "x"}-${base.departureSecs}`, legs, {
      strategy: "hybrid-relay", planner: "relay", confidence: (F.kind !== "bus" || head) && (T.kind !== "bus" || tail) ? "high" : "medium",
      baseTicket: fin(base.fare?.ticket) ? base.fare.ticket : null, basePoints: base.map?.points || [], baseSegments: base.map?.segments || [],
      note: notes.join("／"), extraWalkSecs: Math.round(extraWalk),
    });
    if (opt) relayOptions.push(opt);
  };
  const trainsOf = (base) => {
    const tl = base.legs.filter((l) => l.kind === "transit");
    return { first: tl[0], last: tl[tl.length - 1] };
  };

  if (H !== undefined && (F.kind === "bus" || T.kind === "bus") && maxTransfers >= 1) {
    const railBases = hybridOptions.filter((o) => (o.legs || []).some((l) => l.kind === "transit") && !(o.legs || []).some((l) => l.nara))
      .filter((o) => !timeBased || (arrivalBased ? (o.arrivalSecs ?? 0) <= t0 + 60 : (o.departureSecs ?? 0) >= t0 - 60));

    /* 4a. サーバーが返した鉄道の経路の前後をバスでつなぐ */
    railBases.slice(0, MAX_BASES).forEach((base, bi) => {
      const { first, last } = trainsOf(base);
      const hubF = F.kind === "bus" ? stationStop(first.from, first, pointOf(base, first.from), F.code) : null;
      const hubT = T.kind === "bus" ? stationStop(last.to, last, pointOf(base, last.to), T.code) : null;
      let head = null;
      if (hubF && !hubF.same && fin(first.departureSecs)) {
        const legs = busBefore(F, hubF, first.departureSecs - connectSecs(hubF) - buffer * 60);
        if (legs) head = { legs, hub: hubF };
      }
      let tail = null;
      if (hubT && !hubT.same && fin(last.arrivalSecs)) {
        const legs = busAfter(hubT, T, last.arrivalSecs + connectSecs(hubT) + buffer * 60);
        if (legs) tail = { legs, hub: hubT };
      }
      compose(base, head, tail, `a${bi}`);
    });

    /* 4b. 鉄道が停留所の近くまで通っていないとき：のりば名に出る駅・近い主要駅でバスと鉄道をつなぐ */
    const needFallback = !incomplete && relayOptions.length === 0 && !hybridOptions.some((o) => o.composition === "hybrid") && (F.kind === "bus") !== (T.kind === "bus");
    if (needFallback) {
      const B = T.kind === "bus" ? T : F; // バス側の端
      const P = T.kind === "bus" ? F : T; // 駅・施設側の端
      const side = T.kind === "bus" ? "to" : "from";
      const names = [];
      const addName = (n) => { if (/駅$/.test(n) && normName(n) !== normName(B.label) && !names.some((x) => normName(x) === normName(n))) names.push(n); };
      (B.poles || []).flatMap((p) => partsOfPoleName(p.name)).forEach(addName);
      if (fin(B.lat)) MAJOR_HUBS.map(([name, lat, lon]) => ({ name, d: haversineMeters(B, { lat, lon }) })).sort((x, y) => x.d - y.d).slice(0, 2).forEach((h) => addName(h.name));
      const pEndpoint = railEndpoint(P);

      names.slice(0, 3).forEach((name, hi) => {
        if (!pEndpoint) return;
        const baseName = normName(name).replace(/^(JR|近鉄)/, "").replace(/駅$/, "");
        const stops = need(`hubq:${normName(name)}`, "nara/server-fns#searchNaraStops", { query: name, limit: 8 });
        const st = need(`stn:${baseName}`, "transit/server-fns#suggestTransitPlaces", { q: baseName, limit: 6 });
        if (failed(stops) || failed(st)) return;
        const stopHit = (stops.items || []).find((it) => normName(it.name) === normName(name));
        const station = pickStation(st.places, baseName);
        if (!stopHit || !station || String(stopHit.code) === String(B.code)) return;
        const hub = { code: String(stopHit.code), name: stopHit.name, lat: fin(stopHit.lat) ? stopHit.lat : null, lon: fin(stopHit.lon) ? stopHit.lon : null, distance: null };
        if (fin(hub.lat)) {
          hub.distance = haversineMeters(hub, station);
          if (!fin(hub.distance) || hub.distance > MAX_WALK_TO_STATION) return;
        }
        const stationEp = `geo:${station.lat},${station.lon}`;
        const gap = connectSecs(hub) + buffer * 60;

        if (side === "to") {
          if (arrivalBased) {
            const bus = busBefore(hub, B, t0);
            if (!bus) return;
            railSearch(`rail:to:${baseName}:${clockOf(bus[0].departureSecs - gap)}`, pEndpoint, stationEp, P.label, name, clockOf(bus[0].departureSecs - gap), "arrival")
              .forEach((base, bi) => { if ((trainsOf(base).last.arrivalSecs ?? 1e9) <= bus[0].departureSecs - gap + 60) compose(base, null, { legs: bus, hub }, `b${hi}${bi}`); });
          } else {
            railSearch(`rail:to:${baseName}:${baseTime}`, pEndpoint, stationEp, P.label, name, timeBased ? baseTime : undefined, q.type || "departure")
              .forEach((base, bi) => {
                const bus = busAfter(hub, B, (trainsOf(base).last.arrivalSecs ?? NaN) + gap);
                if (bus) compose(base, null, { legs: bus, hub }, `b${hi}${bi}`);
              });
          }
        } else if (arrivalBased) {
          railSearch(`rail:from:${baseName}:${baseTime}`, stationEp, pEndpoint, name, P.label, baseTime, "arrival")
            .forEach((base, bi) => {
              const bus = busBefore(B, hub, (trainsOf(base).first.departureSecs ?? NaN) - gap);
              if (bus) compose(base, { legs: bus, hub }, null, `b${hi}${bi}`);
            });
        } else {
          const bus = busAfter(B, hub, q.type === "first" ? 4.5 * 3600 : t0);
          if (!bus) return;
          const at = bus[bus.length - 1].arrivalSecs + gap;
          railSearch(`rail:from:${baseName}:${clockOf(at)}`, stationEp, pEndpoint, name, P.label, clockOf(at), "departure")
            .forEach((base, bi) => { if ((trainsOf(base).first.departureSecs ?? 0) >= at - 60) compose(base, { legs: bus, hub }, null, `b${hi}${bi}`); });
        }
      });
    }
  }

  if (incomplete) return { incomplete: true, progress: H === undefined ? "鉄道・バスの経路を検索しています" : "乗り継ぎを確認しています" };

  /* 5. まとめる（重複を除き、同じ時間帯でより良い候補がある徒歩だけの鉄道候補は後ろへ） */
  let options = [...hybridOptions, ...transferOptions, ...relayOptions].filter((o) => (o.transferCount ?? 0) <= maxTransfers)
    .filter((o) => !timeBased || (arrivalBased ? (o.arrivalSecs ?? 0) <= t0 + 60 : (o.departureSecs ?? 0) >= t0 - 60));
  const sig = (o) => (o.legs || []).filter((l) => l.kind === "transit").map((l) => `${l.routeName}:${normName(l.from?.name)}:${normName(l.to?.name)}:${l.departureSecs}`).join("|");
  const seenSig = new Set();
  options = options.filter((o) => { const k = sig(o); if (seenSig.has(k)) return false; seenSig.add(k); return true; });
  // 徒歩の時間を含めた実質の到着時刻（鉄道のみの候補は駅までの徒歩が区間として入っていない）
  const implicitWalk = (o) => {
    if (fin(o.extraWalkSecs) && o.planner === "relay") return o.extraWalkSecs;
    if (o.planner === "server") return Math.max(0, (o.walkSecs || 0) - (o.legs || []).filter((l) => l.kind === "walk").reduce((s, l) => s + Math.max(0, (l.arrivalSecs ?? 0) - (l.departureSecs ?? 0)), 0));
    return 0;
  };
  options = options.map((o) => ({ ...o, effectiveArrivalSecs: fin(o.arrivalSecs) ? o.arrivalSecs + implicitWalk(o) : null, implicitWalkSecs: implicitWalk(o) }));
  // 組み立てた候補のうち、同じ便に乗り継ぐなら遅く出られる方だけを残し、乗換が少なく早く着く候補に負けるものは出さない
  const lastKey = (o) => { const tl = (o.legs || []).filter((l) => l.kind === "transit"); const l = tl[tl.length - 1]; return l ? `${l.routeName}:${normName(l.to?.name)}:${l.arrivalSecs}` : ""; };
  const built = (o) => o.planner === "bus-transfer" || o.planner === "relay";
  options = options.filter((a, ai) => !built(a) || !options.some((b, bi) => {
    if (ai === bi || !fin(b.effectiveArrivalSecs) || !fin(a.effectiveArrivalSecs)) return false;
    const sameLast = built(b) && lastKey(a) === lastKey(b) && (b.transferCount ?? 0) <= (a.transferCount ?? 0);
    if (sameLast && ((b.departureSecs ?? 0) > (a.departureSecs ?? 0) || ((b.departureSecs ?? 0) === (a.departureSecs ?? 0) && bi < ai))) return true;
    const better = (b.departureSecs ?? 0) >= (a.departureSecs ?? 0) && b.effectiveArrivalSecs <= a.effectiveArrivalSecs && (b.transferCount ?? 0) < (a.transferCount ?? 0);
    return better;
  }));
  options.sort((a, b) => (a.departureSecs ?? 0) - (b.departureSecs ?? 0));
  let best = null;
  options.forEach((o) => { if (fin(o.effectiveArrivalSecs) && (!best || o.effectiveArrivalSecs < best.effectiveArrivalSecs || (o.effectiveArrivalSecs === best.effectiveArrivalSecs && (o.transferCount ?? 0) < (best.transferCount ?? 0)))) best = o; });
  options = options.map((o, i) => ({ ...o, rank: i + 1, recommended: best ? o.id === best.id : false }));

  const counts = {
    bus: options.filter((o) => o.composition === "bus").length,
    transit: options.filter((o) => o.composition === "transit").length,
    hybrid: options.filter((o) => o.composition === "hybrid").length,
    busTransfer: options.filter((o) => o.composition === "bus" && (o.transferCount ?? 0) > 0).length,
  };
  if (options.some((o) => o.planner === "relay")) notices.unshift({ severity: "info", code: "relay", message: "鉄道の経路の前後を、駅前の停留所から出る奈良交通バスでつないだ候補を表示しています（時刻は公式APIの実データ）。" });
  if (counts.busTransfer) notices.unshift({ severity: "info", code: "busTransfer", message: "奈良交通バス同士を乗り継ぐ候補を表示しています（乗換停留所で接続する便だけを表示）。" });
  const finalNotices = options.length ? notices.filter((n) => !["noRoute", "busNoRoute", "walkOnly", "walkTooLong", "transitDown"].includes(n.code) || (n.code === "transitDown" && !counts.transit && !counts.hybrid)) : notices;

  const hybridError = H && failed(H) ? text(H.__error) : text(H?.error);
  return {
    ok: options.length > 0,
    error: options.length ? "" : hybridError || "この条件で取得できる経路がありませんでした。",
    date: q.date,
    type: q.type || "departure",
    time: timeBased ? baseTime : "",
    from: { label: F.label, kind: F.kind, code: F.code, lat: F.lat, lon: F.lon },
    to: { label: T.label, kind: T.kind, code: T.code, lat: T.lat, lon: T.lon },
    options: decorateOptions(options).map((o) => ({ ...o, _effArr: fin(o.effectiveArrivalSecs) && o.implicitWalkSecs > 60 ? clockOf(o.effectiveArrivalSecs) : "", _implicitWalkMin: Math.round((o.implicitWalkSecs || 0) / 60) })),
    counts,
    notices: [...new Map(finalNotices.map((n) => [n.message, n])).values()],
    sources: H && !failed(H) ? H.sources || null : { transit: { ok: false, error: hybridError }, narakotsu: { ok: transferOptions.length > 0, error: "" } },
    coverage: H && !failed(H) ? H.coverage || null : null,
    osm: H && !failed(H) ? H.osm || null : null,
    durationMs: H && !failed(H) ? H.durationMs || 0 : 0,
  };
}

/**
 * 1 段進める。state = { query, res: { id: 結果 | { __error } } }
 * 戻り値: { done:false, requests:[{id, fn, data}], progress } または { done:true, result }
 */
export function step(state) {
  const res = state?.res || {};
  const pending = [];
  const get = (id, fn, data) => {
    if (Object.prototype.hasOwnProperty.call(res, id)) return res[id] ?? { __error: "empty" };
    if (!pending.some((p) => p.id === id)) pending.push({ id, fn, data });
    return undefined;
  };
  const out = compute(state?.query || {}, get);
  if (pending.length) return { done: false, requests: pending, progress: out?.progress || "検索しています" };
  return { done: true, result: out };
}
