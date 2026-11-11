/**
 * ネイティブ版用のつなぎ：総合経路検索の候補に表示用の文字列を付ける（Web の RouteResultCard / LegList と同じ整形）。
 */
import {
  modeLabel, timeLabel, durationLabel, fareLabel, fareNote, clockOfEpoch,
  CONFIDENCE_LABEL, COMPOSITION_LABEL, itineraryRowsOf,
} from "@/lib/transit/format";

export function decorateOptions(options) {
  return (options || []).map((o) => ({
    ...o,
    _dep: timeLabel(o.departureSecs),
    _arr: timeLabel(o.arrivalSecs),
    _dur: durationLabel(o.durationSecs),
    _walk: o.walkSecs > 0 ? durationLabel(o.walkSecs) : "",
    _fare: fareLabel(o.fare),
    _fareNote: fareNote(o.fare),
    _comp: o.composition ? COMPOSITION_LABEL[o.composition] || o.composition : "",
    _conf: o.confidence ? CONFIDENCE_LABEL[o.confidence] || o.confidence : "",
    _rows: itineraryRowsOf(o),
    legs: (o.legs || []).map((l) => ({
      ...l,
      _dep: timeLabel(l.departureSecs),
      _arr: timeLabel(l.arrivalSecs),
      _mode: l.kind === "walk" ? durationLabel((l.arrivalSecs ?? 0) - (l.departureSecs ?? 0)) : modeLabel(l),
      _liveAt: l.live?.observedAt ? clockOfEpoch(l.live.observedAt) : "",
    })),
  }));
}
