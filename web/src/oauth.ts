const CLIENT_ID = "Ov23li28WjgBpOYKKb9Y";
const PROXY = (import.meta.env.VITE_OAUTH_PROXY_URL as string | undefined)?.replace(/\/$/, "") || "";

interface DeviceCode {
  device_code: string;
  user_code: string;
  verification_uri: string;
  expires_in: number;
  interval: number;
}

interface TokenResponse {
  access_token?: string;
  error?: string;
  error_description?: string;
  interval?: number;
}

export function oauthAvailable(): boolean {
  return Boolean(PROXY);
}

export async function deviceLogin(onCode: (code: string, url: string) => Promise<void>): Promise<string> {
  if (!PROXY) throw new Error("OAuth proxyが未設定です。設定メニューからGitHub tokenを入力してください");
  const code = await post<DeviceCode>("/device/code", { client_id: CLIENT_ID, scope: "repo" });
  await onCode(code.user_code, code.verification_uri);
  let interval = Math.max(1, code.interval || 5);
  const expiresAt = Date.now() + code.expires_in * 1000;
  while (Date.now() < expiresAt) {
    await new Promise((resolve) => setTimeout(resolve, interval * 1000));
    const result = await post<TokenResponse>("/access_token", {
      client_id: CLIENT_ID,
      device_code: code.device_code,
      grant_type: "urn:ietf:params:oauth:grant-type:device_code",
    });
    if (result.access_token) return result.access_token;
    if (result.error === "authorization_pending") continue;
    if (result.error === "slow_down") {
      interval = result.interval || interval + 5;
      continue;
    }
    throw new Error(result.error_description || result.error || "GitHub loginに失敗しました");
  }
  throw new Error("GitHub login codeの有効期限が切れました");
}

async function post<T>(path: string, body: Record<string, string>): Promise<T> {
  const response = await fetch(`${PROXY}${path}`, {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify(body),
  });
  if (!response.ok) throw new Error(`OAuth proxy ${response.status}: ${await response.text()}`);
  return response.json() as Promise<T>;
}
