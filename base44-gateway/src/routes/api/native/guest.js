/**
 * ネイティブ Android アプリを「ログインせずに使う」ための窓口。
 *
 * POST /api/native/guest → { access_token }
 *   共有のゲストアカウント（管理者ではない一般ユーザー）でログインし、そのアクセストークンを返す。
 *   ゲストのメールアドレスとパスワードは Base44 の Secrets（GUEST_EMAIL / GUEST_PASSWORD）に置き、
 *   アプリやリポジトリには含めない。未設定のときは 404 を返し、アプリは通常のログイン画面を出す。
 *
 * 注意：このトークンで呼べる機能は、ゲストアカウントの権限の範囲（管理者機能は使えない）。
 *       アプリを持つ人は誰でもゲストとしてデータを見たり書き込んだりでき、AI 機能の利用料もかかる。
 */
import { createFileRoute } from "@tanstack/react-router";

const NO_STORE = { "cache-control": "no-store" };
const env = (name) => (typeof process !== "undefined" && process.env ? process.env[name] : undefined) || "";

export const Route = createFileRoute("/api/native/guest")({
  server: {
    handlers: {
      POST: async ({ request }) => {
        const email = env("GUEST_EMAIL");
        const password = env("GUEST_PASSWORD");
        const appId = env("VITE_BASE44_APP_ID") || import.meta.env.VITE_BASE44_APP_ID;
        if (!email || !password || !appId) {
          return Response.json({ error: "ゲスト利用は設定されていません" }, { status: 404, headers: NO_STORE });
        }
        const origin = new URL(request.url).origin;
        const res = await fetch(`${origin}/api/apps/${appId}/auth/login`, {
          method: "POST",
          headers: { "content-type": "application/json" },
          body: JSON.stringify({ email, password }),
        }).catch(() => null);
        const data = res ? await res.json().catch(() => ({})) : {};
        if (!res?.ok || !data?.access_token) {
          return Response.json({ error: "ゲストでログインできませんでした" }, { status: 502, headers: NO_STORE });
        }
        return Response.json({ access_token: data.access_token }, { headers: NO_STORE });
      },
    },
  },
});
