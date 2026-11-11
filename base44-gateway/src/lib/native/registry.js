/**
 * ネイティブ Android アプリ用：サーバー関数の一覧（自動生成）。
 * キーは「src/lib からのモジュールパス#エクスポート名」（例: board/server-fns#listPosts）。
 * サーバー関数のファイルを追加したら、ここにも import を追加する。
 */
import * as m0 from "@/lib/ai/server-fns";
import * as m1 from "@/lib/analytics/server-fns";
import * as m2 from "@/lib/analytics/transfers-fns";
import * as m3 from "@/lib/automation/server-fns";
import * as m4 from "@/lib/board/server-fns";
import * as m5 from "@/lib/camera/fns";
import * as m6 from "@/lib/catalog/fns";
import * as m7 from "@/lib/chat/server-fns";
import * as m8 from "@/lib/collect/generated-fns";
import * as m9 from "@/lib/collect/server-fns";
import * as m10 from "@/lib/discover/server-fns";
import * as m11 from "@/lib/expedition/server-fns";
import * as m12 from "@/lib/expert/server-fns";
import * as m13 from "@/lib/fleet/fns";
import * as m14 from "@/lib/forecast/plan-fns";
import * as m15 from "@/lib/forecast/plan-fns.server";
import * as m16 from "@/lib/forecast/server-fns";
import * as m17 from "@/lib/monitor/server-fns";
import * as m18 from "@/lib/nara/server-fns";
import * as m19 from "@/lib/notify/fns";
import * as m20 from "@/lib/notify/vehicle-fns";
import * as m21 from "@/lib/occlusion/server-fns";
import * as m22 from "@/lib/offline/note-fns";
import * as m23 from "@/lib/offline/server-fns";
import * as m24 from "@/lib/outing/server-fns";
import * as m25 from "@/lib/personal/fns";
import * as m26 from "@/lib/photo/server-fns";
import * as m27 from "@/lib/predict/fns";
import * as m28 from "@/lib/push/server-fns";
import * as m29 from "@/lib/radar/server-fns";
import * as m30 from "@/lib/radar/snow-fns";
import * as m31 from "@/lib/rain/fns";
import * as m32 from "@/lib/research/fns";
import * as m33 from "@/lib/rides/server-fns";
import * as m34 from "@/lib/server-fns";
import * as m35 from "@/lib/services/server-fns";
import * as m36 from "@/lib/shoot/chances-fns";
import * as m37 from "@/lib/shoot/server-fns";
import * as m38 from "@/lib/sim3d/server-fns";
import * as m39 from "@/lib/terrain/server-fns";
import * as m40 from "@/lib/timetable/fns";
import * as m41 from "@/lib/transit/hybrid-fns";
import * as m42 from "@/lib/transit/server-fns";
import * as m43 from "@/lib/weather/advanced-fns";
import * as m44 from "@/lib/weather/outfit-fns";
import * as m45 from "@/lib/weather/server-fns";

const MODULES = {
  "ai/server-fns": m0,
  "analytics/server-fns": m1,
  "analytics/transfers-fns": m2,
  "automation/server-fns": m3,
  "board/server-fns": m4,
  "camera/fns": m5,
  "catalog/fns": m6,
  "chat/server-fns": m7,
  "collect/generated-fns": m8,
  "collect/server-fns": m9,
  "discover/server-fns": m10,
  "expedition/server-fns": m11,
  "expert/server-fns": m12,
  "fleet/fns": m13,
  "forecast/plan-fns": m14,
  "forecast/plan-fns.server": m15,
  "forecast/server-fns": m16,
  "monitor/server-fns": m17,
  "nara/server-fns": m18,
  "notify/fns": m19,
  "notify/vehicle-fns": m20,
  "occlusion/server-fns": m21,
  "offline/note-fns": m22,
  "offline/server-fns": m23,
  "outing/server-fns": m24,
  "personal/fns": m25,
  "photo/server-fns": m26,
  "predict/fns": m27,
  "push/server-fns": m28,
  "radar/server-fns": m29,
  "radar/snow-fns": m30,
  "rain/fns": m31,
  "research/fns": m32,
  "rides/server-fns": m33,
  "server-fns": m34,
  "services/server-fns": m35,
  "shoot/chances-fns": m36,
  "shoot/server-fns": m37,
  "sim3d/server-fns": m38,
  "terrain/server-fns": m39,
  "timetable/fns": m40,
  "transit/hybrid-fns": m41,
  "transit/server-fns": m42,
  "weather/advanced-fns": m43,
  "weather/outfit-fns": m44,
  "weather/server-fns": m45,
};

const isServerFn = (v) => typeof v === "function" && typeof v.__executeServer === "function";

/** 名前 → サーバー関数 */
export const SERVER_FNS = (() => {
  const out = new Map();
  for (const [mod, ns] of Object.entries(MODULES)) {
    for (const [name, value] of Object.entries(ns)) {
      if (isServerFn(value)) out.set(`${mod}#${name}`, value);
    }
  }
  return out;
})();
