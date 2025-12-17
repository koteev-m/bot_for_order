# Bot for Order

## Observability (S21)
- Prometheus scraping is exposed via `/metrics` when metrics are enabled. Protect access with optional Basic-auth (`METRICS_BASIC_AUTH=user:pass`) and an IP/CIDR allowlist (`METRICS_IP_ALLOWLIST=127.0.0.1,10.0.0.0/8,::1`).
- The metrics endpoint serves the Prometheus text exposition format with content type `text/plain; version=0.0.4; charset=utf-8`.
- Build metadata is embedded into `build-info.json` during the build and exposed through the `/build` endpoint and the `build_info` gauge (tags: `version`, `commit`, `branch`).

### Trusted proxies & client IP resolution
- Configure trusted proxies with `METRICS_TRUSTED_PROXY_ALLOWLIST` (comma-separated IPs/CIDRs). When empty, the remote socket address is always used and `X-Forwarded-For` is ignored.
- If the remote address is not on the trusted proxy allowlist, `X-Forwarded-For` is ignored. If it *is* trusted, the header is walked from right-to-left, skipping any entries that match trusted proxies; the first non-proxy is treated as the client IP. Missing/malformed headers fall back to the remote address.
- CDN real-IP headers (`X-Real-IP`, `CF-Connecting-IP`, `True-Client-IP`) are used as a fallback only when the remote address is trusted; otherwise they are ignored.
- `/metrics` includes `Cache-Control: no-store` and `Vary` on `Authorization`, `X-Forwarded-For`, `Forwarded`, `X-Real-IP`, `CF-Connecting-IP`, `True-Client-IP`.
- Example: with `METRICS_TRUSTED_PROXY_ALLOWLIST=127.0.0.1,10.0.0.0/8`, a request from `127.0.0.1` with `X-Forwarded-For: 203.0.113.5, 127.0.0.1` resolves to `203.0.113.5`; the same header from `198.51.100.10` resolves to `198.51.100.10` because the proxy is untrusted.
