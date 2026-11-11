/**
 * ネイティブ Android アプリ用の JSON API。
 *
 * POST /api/native/rpc  { "fn": "board/server-fns#listPosts", "data": {...} }
 *   → 既存のサーバー関数をそのまま実行し、結果を JSON で返す。
 *     認証・入力検証・権限確認は各サーバー関数のミドルウェアがそのまま行う
 *     （呼び出し元は Authorization: Bearer <Base44 アクセストークン> を付ける）。
 *     結果がストリーム（AIチャットなど）の場合は NDJSON で逐次返す。
 * GET  /api/native/rpc  → 呼び出せる関数名の一覧
 *
 * 画面から呼べるサーバー関数はもともと誰でも呼べる公開エンドポイントなので、
 * この API が新たに公開する処理はない（同じ関数を JSON で呼べるようにするだけ）。
 */
import { createFileRoute } from "@tanstack/react-router";
import { SERVER_FNS } from "@/lib/native/registry";

const NO_STORE = { "cache-control": "no-store" };

const errorResponse = (error) => {
  const status = Number(error?.status || error?.statusCode) || 500;
  const safeStatus = status >= 400 && status < 600 ? status : 500;
  const issues = Array.isArray(error?.issues) ? error.issues : undefined;
  return Response.json(
    {
      error: String(error?.message || "サーバーエラー").slice(0, 2000),
      ...(issues ? { issues } : {}),
    },
    { status: issues ? 400 : safeStatus, headers: NO_STORE },
  );
};

const isAsyncIterable = (v) => v != null && typeof v[Symbol.asyncIterator] === "function";

const streamResponse = (iterable) => {
  const encoder = new TextEncoder();
  const body = new ReadableStream({
    async start(controller) {
      try {
        for await (const chunk of iterable) {
          controller.enqueue(encoder.encode(`${JSON.stringify(chunk)}\n`));
        }
      } catch (error) {
        controller.enqueue(encoder.encode(`${JSON.stringify({ type: "error", error: String(error?.message || error) })}\n`));
      } finally {
        controller.close();
      }
    },
  });
  return new Response(body, { headers: { ...NO_STORE, "content-type": "application/x-ndjson; charset=utf-8" } });
};

export const Route = createFileRoute("/api/native/rpc")({
  server: {
    handlers: {
      GET: async () =>
        Response.json({ version: 1, functions: [...SERVER_FNS.keys()].sort() }, { headers: NO_STORE }),

      POST: async ({ request }) => {
        let body;
        try {
          body = await request.json();
        } catch {
          return Response.json({ error: "JSON を送ってください" }, { status: 400, headers: NO_STORE });
        }
        const name = typeof body?.fn === "string" ? body.fn : "";
        const fn = SERVER_FNS.get(name);
        if (!fn) return Response.json({ error: `不明な関数: ${name.slice(0, 120)}` }, { status: 404, headers: NO_STORE });

        try {
          const { result, error } = await fn.__executeServer({ data: body.data, method: fn.method, context: {} });
          if (error) return errorResponse(error);
          if (result instanceof Response) return result;
          if (result instanceof ReadableStream) {
            return new Response(result, { headers: { ...NO_STORE, "content-type": "application/x-ndjson; charset=utf-8" } });
          }
          if (isAsyncIterable(result)) return streamResponse(result);
          return Response.json({ result: result === undefined ? null : result }, { headers: NO_STORE });
        } catch (error) {
          return errorResponse(error);
        }
      },
    },
  },
});
