# Bot for Order

## Observability (S21)
- Prometheus scraping is exposed via `/metrics` when metrics are enabled. Protect access with optional Basic-auth (`METRICS_BASIC_AUTH=user:pass`) and an IP/CIDR allowlist (`METRICS_IP_ALLOWLIST=127.0.0.1,10.0.0.0/8,::1`).
- Basic-auth parsing is hardened: credentials are size-limited and sanitized from control characters to prevent header smuggling and resource overuse.
- Optional compatibility: set `SECURITY_BASIC_AUTH_LATIN1_FALLBACK=true` to allow ISO-8859-1 decoding when UTF-8 credentials contain replacement characters.
- Customize the Basic-auth realm via `METRICS_BASIC_REALM` (default: `metrics`).
- The metrics endpoint serves the Prometheus text exposition format with content type `text/plain; version=0.0.4; charset=utf-8`.
- Build metadata is embedded into `build-info.json` during the build and exposed through the `/build` endpoint and the `build_info` gauge (tags: `version`, `commit`, `branch`).

### Trusted proxies & client IP resolution
- Configure trusted proxies with `METRICS_TRUSTED_PROXY_ALLOWLIST` (comma-separated IPs/CIDRs). When empty, the remote socket address is always used and `X-Forwarded-For` is ignored.
- If the remote address is not on the trusted proxy allowlist, `X-Forwarded-For` is ignored. If it *is* trusted, the header is walked from right-to-left, skipping any entries that match trusted proxies; the first non-proxy is treated as the client IP. Missing/malformed headers fall back to the remote address.
- CDN real-IP headers (`X-Real-IP`, `CF-Connecting-IP`, `True-Client-IP`) are used as a fallback only when the remote address is trusted; otherwise they are ignored.
- `/metrics` includes `Cache-Control: no-store` and `Vary` on `Authorization`, `X-Forwarded-For`, `Forwarded`, `X-Real-IP`, `CF-Connecting-IP`, `True-Client-IP`. The endpoint also includes `X-Content-Type-Options: nosniff`, `X-Frame-Options: DENY`, `Referrer-Policy: no-referrer`, `Content-Security-Policy: default-src 'none'; frame-ancestors 'none'`, `Cross-Origin-Resource-Policy: same-origin`, `X-Permitted-Cross-Domain-Policies: none`, `Expires: 0`, plus `Pragma: no-cache`.
- Fallback precedence (when traversing trusted proxies and `X-Forwarded-For`/`Forwarded` are absent): `True-Client-IP` → `CF-Connecting-IP` → `X-Real-IP`.
- Example: with `METRICS_TRUSTED_PROXY_ALLOWLIST=127.0.0.1,10.0.0.0/8`, a request from `127.0.0.1` with `X-Forwarded-For: 203.0.113.5, 127.0.0.1` resolves to `203.0.113.5`; the same header from `198.51.100.10` resolves to `198.51.100.10` because the proxy is untrusted.

### HSTS for observability endpoints
- Configure HSTS via `SECURITY_HSTS_ENABLED`, `SECURITY_HSTS_MAX_AGE`, `SECURITY_HSTS_INCLUDE_SUBDOMAINS`, `SECURITY_HSTS_PRELOAD`. The `Strict-Transport-Security` header is appended only for HTTPS requests (direct TLS or when `X-Forwarded-Proto`/`Forwarded: proto=https` is present).

### Health & build endpoints
- `/health` and `/build` responses include `Cache-Control: no-store`, `X-Content-Type-Options: nosniff`, `X-Frame-Options: DENY`, `Referrer-Policy: no-referrer`, `Content-Security-Policy: default-src 'none'; frame-ancestors 'none'`, `Cross-Origin-Resource-Policy: same-origin`, `X-Permitted-Cross-Domain-Policies: none`, `Expires: 0`, plus `Pragma: no-cache` to prevent caching and content-type sniffing.

## Telegram webhooks
- Webhooks are configured outside the app. When calling `setWebhook` for admin/shop bots, pass `secret_token` from `ADMIN_WEBHOOK_SECRET` / `SHOP_WEBHOOK_SECRET` so the server can validate `X-Telegram-Bot-Api-Secret-Token`.

### JS lock enforcement flags
- Skip lock check: `-PskipJsLockCheck=true`.
- Force lock check even outside packaging/publish: `-PforceJsLockCheck=true`.
- Restrict lock enforcement to specific projects (comma-separated): `-PjsLockStrictProjects=:app,:miniapp`.
- By default, the lock check is enforced for `:app` and `:miniapp`. Override with `-PjsLockStrictProjects=:app,:miniapp`.
- Control log verbosity: `-PjsLockVerbose=false` to reduce kotlinStoreYarnLock logs (defaults to `true`).
- Skip miniapp copy during packaging: `-PskipMiniappCopy=true` (useful for CI jobs that don't bundle the web UI).
