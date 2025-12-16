# Bot for Order

## Observability (S21)
- Prometheus scraping is exposed via `/metrics` when metrics are enabled. Protect access with optional Basic-auth (`METRICS_BASIC_AUTH=user:pass`) and an IP/CIDR allowlist (`METRICS_IP_ALLOWLIST=127.0.0.1,10.0.0.0/8,::1`).
- The metrics endpoint serves the Prometheus text exposition format with content type `text/plain; version=0.0.4; charset=utf-8`.
- Build metadata is embedded into `build-info.json` during the build and exposed through the `/build` endpoint and the `build_info` gauge (tags: `version`, `commit`, `branch`).
