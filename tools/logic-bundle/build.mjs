/**
 * Web 版（Base44 アプリ）の src/lib から、画面に依存しない純粋な計算処理だけを集めて
 * 1 本の JavaScript（app/src/main/assets/logic.js）にまとめる。
 * ネイティブ版はこれを QuickJS で実行し、Web と同じ計算結果を得る。
 *
 * 使い方: node tools/logic-bundle/build.mjs <Base44アプリのソースのルート>
 */
import * as esbuild from "esbuild";
import fs from "node:fs";
import path from "node:path";

const appRoot = path.resolve(process.argv[2] || ".");
const outFile = path.resolve(process.argv[3] || "app/src/main/assets/logic.js");
const libDir = path.join(appRoot, "src/lib");

// ブラウザ・サーバー・React に依存するものは対象外
const FORBIDDEN = [/^react/, /^@tanstack\//, /^@base44\//, /^base44:/, /^lucide-react/, /^framer-motion/, /^node:/, /^three/, /^jspdf/, /^html2canvas/];
const SKIP_FILE = (f) => f.endsWith(".server.js") || /fns/.test(path.basename(f)) || /^use[A-Z]/.test(path.basename(f)) || f.includes(`${path.sep}native${path.sep}`);

const walk = (dir) => fs.readdirSync(dir, { withFileTypes: true }).flatMap((e) => {
  const p = path.join(dir, e.name);
  return e.isDirectory() ? walk(p) : p.endsWith(".js") ? [p] : [];
});

const alias = {
  name: "alias",
  setup(build) {
    build.onResolve({ filter: /^@\// }, async (args) => {
      const base = path.join(appRoot, "src", args.path.slice(2));
      for (const cand of [base, `${base}.js`, `${base}.jsx`, path.join(base, "index.js")]) {
        if (fs.existsSync(cand) && fs.statSync(cand).isFile()) return { path: cand };
      }
      return { errors: [{ text: `not found: ${args.path}` }] };
    });
    build.onResolve({ filter: /.*/ }, (args) => {
      if (FORBIDDEN.some((re) => re.test(args.path))) return { path: args.path, external: true };
      return undefined;
    });
  },
};

const candidates = walk(libDir).filter((f) => !SKIP_FILE(f));
const pure = [];
for (const file of candidates) {
  try {
    const r = await esbuild.build({
      entryPoints: [file], bundle: true, write: false, metafile: true, format: "esm", platform: "neutral",
      plugins: [alias], logLevel: "silent", mainFields: ["module", "main"],
      // コメント中の単語（document など）で誤判定しないよう、コメントを除いてから調べる
      minifyWhitespace: true, minifySyntax: true, legalComments: "none",
    });
    const imports = Object.values(r.metafile.outputs).flatMap((o) => o.imports || []);
    const inputs = Object.keys(r.metafile.inputs);
    const usesExternal = imports.some((i) => i.external);
    const usesJsx = inputs.some((i) => i.endsWith(".jsx"));
    const text = r.outputFiles[0].text;
    // localStorage / sessionStorage はアプリ側の保存領域で代用できる（Logic.kt）。それ以外のブラウザ機能は対象外
    const touchesDom = /(?<![.\w$"'`])(window|document|navigator|indexedDB)\b(?!["'`])/.test(text);
    if (!usesExternal && !usesJsx && !touchesDom) pure.push(file);
  } catch {
    /* 単体でまとめられないものは対象外 */
  }
}

// ネイティブ版用のつなぎ（tools/logic-bundle/glue/*.js）も "glue/<名前>" として入れる
const glueDir = new URL("./glue/", import.meta.url).pathname;
const glue = fs.existsSync(glueDir) ? fs.readdirSync(glueDir).filter((f) => f.endsWith(".js")).map((f) => path.join(glueDir, f)) : [];
pure.push(...glue);
const keyOf = (f) => f.startsWith(glueDir) ? "glue/" + path.basename(f, ".js") : path.relative(libDir, f).replace(/\.js$/, "").split(path.sep).join("/");
const entry = [
  ...pure.map((f, i) => `import * as m${i} from ${JSON.stringify(f)};`),
  "const MODULES = {",
  ...pure.map((f, i) => `  ${JSON.stringify(keyOf(f))}: m${i},`),
  "};",
  // QuickJS から呼ぶ入口: callJson("weather/outfit", "buildOutfitAdvice", "[...args]") → JSON 文字列
  "globalThis.__logic = {",
  "  modules: () => JSON.stringify(Object.fromEntries(Object.entries(MODULES).map(([k, m]) => [k, Object.keys(m).filter((n) => typeof m[n] === 'function')]))),",
  "  callJson(mod, name, argsJson) {",
  "    const m = MODULES[mod];",
  "    if (!m || typeof m[name] !== 'function') throw new Error('unknown logic function: ' + mod + '#' + name);",
  "    const out = m[name](...JSON.parse(argsJson || '[]'));",
  "    return JSON.stringify(out === undefined ? null : out, (k, v) => (v instanceof Map ? Object.fromEntries(v) : v instanceof Set ? [...v] : (typeof v === 'number' && !Number.isFinite(v)) ? null : v));",
  "  },",
  "  constJson(mod, name) {",
  "    const m = MODULES[mod];",
  "    return JSON.stringify(m ? m[name] ?? null : null);",
  "  },",
  "};",
].join("\n");

const entryFile = path.join(path.dirname(outFile), ".logic-entry.mjs");
fs.mkdirSync(path.dirname(outFile), { recursive: true });
fs.writeFileSync(entryFile, entry);
await esbuild.build({
  entryPoints: [entryFile], bundle: true, outfile: outFile, format: "iife", platform: "neutral",
  target: "es2020", plugins: [alias], minify: true, legalComments: "none", mainFields: ["module", "main"],
  banner: {
    js: "/* Base44 アプリ src/lib から自動生成（tools/logic-bundle/build.mjs）。手で編集しない。 */\n" +
      fs.readFileSync(new URL("./intl-shim.js", import.meta.url), "utf8"),
  },
});
fs.rmSync(entryFile);
fs.writeFileSync(path.join(path.dirname(outFile), "logic-modules.txt"), pure.map(keyOf).sort().join("\n") + "\n");
console.log(`${pure.length}/${candidates.length} modules -> ${outFile} (${fs.statSync(outFile).size} bytes)`);
