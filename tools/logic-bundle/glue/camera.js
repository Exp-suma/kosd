/**
 * ネイティブ版用のつなぎ：カメラ統合コンソール（Web の lib/camera/* と同じ判定・解釈を使う）。
 * USB(PTP) の送受信そのものは端末側（Kotlin）で行い、ここでは受け取ったバイト列（16進文字列）の解釈だけを行う。
 */
import { capabilitiesFromBridge, capabilitiesFromPtp, mergeStatuses, withMakerHint } from "@/lib/camera/capabilities";
import { FEATURES, FEATURE_GROUPS, STATUS, STATUS_IDS, featureIdForSetting, statusEntry, summarize } from "@/lib/camera/features";
import { CAMERA_PROPS, buildPropRow } from "@/lib/camera/props";
import { encodeTypedValue, parseDeviceInfo, parseObjectInfo, parsePropDesc, responseText } from "@/lib/camera/ptp";
import { isManualFocusText, wifiDefByKey, wifiPropRows } from "@/lib/camera/wifiSettings";
import { cameraIpProblem, detectCandidates, normalizeNetinfo } from "@/lib/wifi/netinfo";

const bytesOf = (hex) => {
  const text = String(hex || "");
  const out = new Uint8Array(text.length >> 1);
  for (let i = 0; i < out.length; i += 1) out[i] = parseInt(text.substr(i * 2, 2), 16);
  return out;
};
const hexOf = (bytes) => Array.from(bytes, (b) => b.toString(16).padStart(2, "0")).join("");

export const meta = () => ({ features: FEATURES, groups: FEATURE_GROUPS, status: STATUS, statusIds: STATUS_IDS, props: CAMERA_PROPS });
export const deviceInfo = (hex) => parseDeviceInfo(bytesOf(hex));
export const objectInfo = (hex) => parseObjectInfo(bytesOf(hex));
export const propDesc = (hex) => parsePropDesc(bytesOf(hex));
export const encodeValue = (dataType, value) => hexOf(encodeTypedValue(dataType, value));
export const rcText = (code) => responseText(code);

/** USB：各設定の GetDevicePropDesc 結果（{ code, hex } または { code, error }、未対応は含めない）から表示行を作る。 */
export function usbPropRows(descs) {
  const byCode = new Map((descs || []).map((row) => [row.code, row]));
  return CAMERA_PROPS.map((def) => {
    const hit = byCode.get(def.code);
    if (!hit) return buildPropRow(def, null);
    if (hit.error) return buildPropRow(def, null, hit.error);
    try {
      return buildPropRow(def, parsePropDesc(bytesOf(hit.hex)));
    } catch (error) {
      return buildPropRow(def, null, error?.message || "読み取れませんでした");
    }
  });
}
export const wifiRows = (settings) => wifiPropRows(settings);
export const wifiSettingName = (key) => wifiDefByKey(key)?.name || "";

/** 機能の対応状況：保存済み → 実行時の判定 → 今回の操作結果 の順に重ねる。 */
export function capabilities({ transport, ptpInfo, report, device, persisted, overrides }) {
  const runtime = transport === "usb" ? capabilitiesFromPtp(ptpInfo || null) : withMakerHint(capabilitiesFromBridge(report || null), device || null);
  const map = mergeStatuses(mergeStatuses(persisted || {}, runtime), overrides || {});
  const entries = {};
  FEATURES.forEach((feature) => {
    entries[feature.id] = statusEntry(map, feature.id);
  });
  return { map, entries, summary: summarize(map) };
}
export const featureForSetting = (key) => featureIdForSetting(key) || "";
export const manualFocusText = (value) => isManualFocusText(value);
export const netinfo = (raw) => normalizeNetinfo(raw);
export const ipProblem = (ip, phoneIp) => cameraIpProblem(ip, phoneIp);
export const candidates = (input) => detectCandidates(input);
