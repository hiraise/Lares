# Deployment

Production stack for one family server (ADR-0005): Lares server, PostgreSQL, Caddy (automatic HTTPS) and optional
ntfy (instant notifications, profile `push`).

Installation, backups, updates and troubleshooting for admins:
[English](../docs/user/en/install-server.md) · [Русский](../docs/user/ru/install-server.md).

| File | Purpose |
| --- | --- |
| `docker-compose.yml` | The stack; reads `.env` in this directory |
| `.env.example` | Every setting, documented; copy to `.env` |
| `docker-compose.build.yml` | Override: use a server image built locally from `./gradlew :server:installDist` |
| `caddy/Caddyfile`, `caddy/Caddyfile.push` | Reverse proxy; `.push` also serves ntfy |
| `ntfy/server.yml` | ntfy config: anonymous access only to UnifiedPush topics `up*` |
| `scripts/backup.sh`, `scripts/restore.sh` | Database backup (`backups/`, gitignored, newest 14 kept) and restore |
| `scripts/smoke.sh` | End-to-end test of the whole stack on `https://localhost` (CI job `deploy-smoke`) |
| `scripts/loadtest.sh` | Load test with a simulated family (`tools/loadsim`), memory and cold start; results in `build/loadtest/` ([baseline](../docs/architecture/perf/M0-baseline.md)) |
| `dev/docker-compose.yml` | Development database only |

Local build and smoke test, from the repository root (Docker running, ports 80 and 443 free):

```sh
./gradlew :server:installDist
docker build -f server/Dockerfile -t lares-server:dev .   # image only
deploy/scripts/smoke.sh                                         # full stack, setup, backup, restore, ntfy
./gradlew :tools:loadsim:installDist && deploy/scripts/loadtest.sh --duration 5m   # load test (default 30 min)
```
