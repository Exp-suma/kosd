"""Generate src/lib/native/registry.js for the Base44 app (run from the app root)."""
import pathlib
import subprocess
import sys

root = pathlib.Path(sys.argv[1])
files = sorted(
    p for p in subprocess.run(
        ["grep", "-rlE", "createServerFn", "src/lib"], cwd=root, capture_output=True, text=True
    ).stdout.split()
    if not p.startswith("src/lib/native/")
)

lines = [
    "/**",
    " * ネイティブ Android アプリ用：サーバー関数の一覧（自動生成）。",
    " * キーは「src/lib からのモジュールパス#エクスポート名」（例: board/server-fns#listPosts）。",
    " * サーバー関数のファイルを追加したら、ここにも import を追加する。",
    " */",
]
for i, f in enumerate(files):
    lines.append(f'import * as m{i} from "@/{f[len("src/"):-3]}";')
lines.append("")
lines.append("const MODULES = {")
for i, f in enumerate(files):
    lines.append(f'  "{f[len("src/lib/"):-3]}": m{i},')
lines.append("};")
lines.append("""
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
""")
out = root / "src/lib/native/registry.js"
out.parent.mkdir(parents=True, exist_ok=True)
out.write_text("\n".join(lines), encoding="utf-8")
print(f"{len(files)} modules -> {out}")
