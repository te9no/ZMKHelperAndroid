interface Env {
  ALLOWED_ORIGIN: string;
  GITHUB_CLIENT_ID: string;
}

const ROUTES: Record<string, string> = {
  "/device/code": "https://github.com/login/device/code",
  "/access_token": "https://github.com/login/oauth/access_token",
};

export default {
  async fetch(request: Request, env: Env): Promise<Response> {
    const origin = request.headers.get("Origin") || "";
    const allowed = origin === env.ALLOWED_ORIGIN || origin.startsWith("http://localhost:");
    const cors = {
      "Access-Control-Allow-Origin": allowed ? origin : env.ALLOWED_ORIGIN,
      "Access-Control-Allow-Headers": "Content-Type",
      "Access-Control-Allow-Methods": "POST, OPTIONS",
      Vary: "Origin",
    };
    if (request.method === "OPTIONS") return new Response(null, { status: allowed ? 204 : 403, headers: cors });
    if (!allowed) return new Response("Origin is not allowed", { status: 403, headers: cors });
    const target = ROUTES[new URL(request.url).pathname];
    if (!target || request.method !== "POST") return new Response("Not found", { status: 404, headers: cors });

    let body: Record<string, string>;
    try {
      body = (await request.json()) as Record<string, string>;
    } catch {
      return new Response("Invalid JSON", { status: 400, headers: cors });
    }
    if (body.client_id !== env.GITHUB_CLIENT_ID) return new Response("Invalid client_id", { status: 400, headers: cors });
    const permitted = new URLSearchParams({ client_id: env.GITHUB_CLIENT_ID });
    if (target.endsWith("device/code")) {
      permitted.set("scope", "repo");
    } else {
      permitted.set("device_code", body.device_code || "");
      permitted.set("grant_type", "urn:ietf:params:oauth:grant-type:device_code");
    }
    const github = await fetch(target, {
      method: "POST",
      headers: { Accept: "application/json", "Content-Type": "application/x-www-form-urlencoded", "User-Agent": "ZMK-Helper-Web" },
      body: permitted,
    });
    return new Response(github.body, {
      status: github.status,
      headers: { ...cors, "Content-Type": "application/json", "Cache-Control": "no-store" },
    });
  },
};
