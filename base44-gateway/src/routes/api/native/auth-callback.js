/**
 * ネイティブ Android アプリの Google ログイン用の戻り先。
 *
 * アプリはブラウザで /api/apps/auth/login?app_id=…&from_url=<このURL> を開く。
 * Base44 はログイン後にこのURLへ ?access_token=… を付けて戻すので、
 * ここからアプリのスキーム（narabuspulse://auth）へトークンを渡して戻る。
 */
import { createFileRoute } from "@tanstack/react-router";

const APP_SCHEME_URL = "narabuspulse://auth";

const page = (target) => `<!doctype html><html lang="ja"><head><meta charset="utf-8">
<meta name="viewport" content="width=device-width,initial-scale=1">
<meta http-equiv="refresh" content="0;url=${target}">
<title>アプリに戻ります</title></head>
<body style="font-family:system-ui,sans-serif;padding:24px;color:#333">
<p>アプリに戻ります…</p>
<p><a href="${target}" style="color:#E66116">戻らない場合はこちらをタップ</a></p>
</body></html>`;

export const Route = createFileRoute("/api/native/auth-callback")({
  server: {
    handlers: {
      GET: async ({ request }) => {
        const url = new URL(request.url);
        const token = url.searchParams.get("access_token") || "";
        const safeToken = /^[A-Za-z0-9._~-]{10,4096}$/.test(token) ? token : "";
        const target = safeToken
          ? `${APP_SCHEME_URL}#access_token=${encodeURIComponent(safeToken)}`
          : `${APP_SCHEME_URL}#error=login_failed`;
        return new Response(page(target), {
          headers: {
            "content-type": "text/html; charset=utf-8",
            "cache-control": "no-store",
            "referrer-policy": "no-referrer",
          },
        });
      },
    },
  },
});
