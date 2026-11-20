# OAuth proxy

GitHub Device Flow endpoints do not allow browser CORS requests. This Cloudflare Worker only forwards the two Device Flow POST requests for the configured OAuth client and origin. It does not store tokens.

```powershell
cd web/worker
npx wrangler deploy
```

Set `ALLOWED_ORIGIN` in `wrangler.toml` to the production web origin. After deployment, configure the GitHub Actions repository variable `VITE_OAUTH_PROXY_URL` with the Worker URL, such as `https://zmk-helper-oauth.example.workers.dev`.

The GitHub OAuth App must have Device Flow enabled. No OAuth client secret is used by Device Flow.
