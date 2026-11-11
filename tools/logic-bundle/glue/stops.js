/**
 * ネイティブ版用のつなぎ（Web の hooks の中で行っている計算をまとめたもの）。
 * Map などを返す関数を JSON で受け渡せる形にして呼ぶ。
 */
import { buildCoordIndex, matchCoord } from "@/lib/bus/stopMatch";
import { readCachedCoords } from "@/lib/bus/osm";

/** useStopCoordLookup の 1)〜2) と同じ照合。list: [[seq, name, code]] → { seq: {lat, lon} } */
export function matchStopCoords(list, sharedRows) {
  const out = {};
  const names = list.map(([, name]) => name).filter(Boolean);
  const local = readCachedCoords(names);
  list.forEach(([seq, name]) => {
    if (local.coords[name]) out[seq] = local.coords[name];
  });
  const index = buildCoordIndex(sharedRows || []);
  list.forEach(([seq, name, code]) => {
    if (out[seq]) return;
    const hit = matchCoord({ seq, name, code }, index);
    if (hit.coord) out[seq] = hit.coord;
  });
  return out;
}
