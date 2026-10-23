---
id: M0-10
status: done
depends_on: [M0-05]
requirements: [FND-070, FND-071, FND-072]
---
# M0-10: Deploy and operations

## Goal

The admin can run the full stack on a VPS with one Docker Compose file and automatic HTTPS, back it up and restore it.
CI builds multi-arch server images and the APK on tags. A user manual (en/ru) explains installation.

## Read first

- [ADR-0005](../../adr/0005-postgresql-docker-vps.md), [ADR-0013](../../adr/0013-server-resource-budget.md),
  [ADR-0006](../../adr/0006-no-proprietary-dependencies.md) (ntfy), [ADR-0007](../../adr/0007-distribution.md)
- [Auth design § setup and public URL](../../architecture/auth.md)
- [versions.md](versions.md): image tags, GitHub Actions versions
- Server config from M0-02 (environment variables)

## Scope

- `server/Dockerfile`, `.dockerignore`.
- `deploy/docker-compose.yml`, `deploy/Caddyfile`, `deploy/ntfy/server.yml`, `deploy/.env.example`.
- `deploy/scripts/backup.sh`, `restore.sh`, `smoke.sh`.
- `.github/workflows/release.yml` (image + APK on tags) and a `deploy-smoke` job in `ci.yml`.
- User manuals: `docs/user/en/install-server.md`, `docs/user/ru/install-server.md`.

Out of scope: load testing (M0-11), store publishing (M6), automatic updates.

## Decisions (do not revisit)

- **Image**: runtime base `eclipse-temurin:21-jre-noble` (multi-arch; pin the distro suffix — plain `21-jre` moved to a newer Ubuntu). The image copies the output of
  `./gradlew :server:installDist` (built before `docker build`, in CI and locally) — no Gradle inside Docker.
  Runs as a non-root user `lares` (uid 10001). `EXPOSE 8080`. Installs `curl` for the healthcheck.
  Default `JAVA_OPTS="-XX:+UseSerialGC -Xmx160m -Xss512k -XX:MaxMetaspaceSize=128m -XX:+ExitOnOutOfMemoryError"`
  (M0-11 may tune). `HEALTHCHECK` → `curl -fsS http://localhost:8080/health/ready`.
- **Pinned image tags**: `postgres:18-alpine`, `caddy:2.11.7-alpine`, `binwiederhier/ntfy:v2.32.0`,
  base `eclipse-temurin:21-jre-noble` (see [versions.md](versions.md)).
- **Image name**: `ghcr.io/<github-owner>/lares-server` — owner from `${{ github.repository_owner }}` in CI;
  compose uses `${LARES_IMAGE:-ghcr.io/hiraise/lares-server}:${LARES_VERSION:-latest}` with a build fallback
  documented for local use (`docker compose build`).
- **Compose services**: `postgres` (`postgres:18-alpine`, healthcheck `pg_isready`, volume `pgdata` mounted at
  **`/var/lib/postgresql`** — the PostgreSQL 18 image data path), `server` (depends on healthy
  postgres; env from `.env`; `mem_limit: 384m`), `caddy` (ports 80/443, volumes `caddy_data`, `caddy_config`,
  `mem_limit: 64m`), `ntfy` (profile `push`, `mem_limit: 64m`). Restart `unless-stopped`. Only Caddy publishes ports.
- **Caddy config**: two files in `deploy/caddy/`: `Caddyfile` (site `{$LARES_DOMAIN}` → `reverse_proxy server:8080`) and
  `Caddyfile.push` (the same plus site `{$LARES_NTFY_DOMAIN}` → `reverse_proxy ntfy:80`). The caddy service runs
  `caddy run --config /etc/caddy/${LARES_CADDYFILE:-Caddyfile} --adapter caddyfile`; enabling push = start with
  `--profile push` and set `LARES_CADDYFILE=Caddyfile.push` and `LARES_NTFY_DOMAIN`. If `LARES_DOMAIN=localhost`,
  Caddy uses its internal CA (local smoke tests).
- **ntfy** (`binwiederhier/ntfy:v2.32.0`, `command: serve`): config `base-url: https://ntfy.<domain>`,
  `listen-http: ":80"`, `behind-proxy: true`, `cache-file`, `auth-file`, `auth-default-access: "deny-all"`; an init step
  (documented one-time command in the manual and done automatically by `smoke.sh`) grants anonymous access only to
  UnifiedPush topics: `docker compose exec ntfy ntfy access '*' 'up*' read-write`. Result: family phones need no ntfy
  account, and the server cannot be used for arbitrary topics. `upstream-base-url` is **not** needed (iOS only).
  Set `LARES_PUSH_ALLOWED_HOSTS=ntfy.<domain>` only if the server resolves the ntfy host to a private IP.
- **.env.example** documents every variable: `LARES_DOMAIN`, `LARES_PUBLIC_URL` (derived default
  `https://${LARES_DOMAIN}`), `POSTGRES_PASSWORD`, `LARES_DB_PASSWORD` (same value), `LARES_SETUP_CODE` (optional),
  `LARES_VERSION`, `LARES_LOG_FORMAT=json`, `LARES_IMAGE`, `LARES_CADDYFILE`, `LARES_NTFY_DOMAIN`,
  `LARES_PUSH_ALLOWED_HOSTS`. Compose sets for the server `LARES_DB_URL=jdbc:postgresql://postgres:5432/lares`,
  `LARES_DB_USER=lares`, and for postgres `POSTGRES_USER=lares`, `POSTGRES_DB=lares`.
- **Backups**: `backup.sh` runs `pg_dump -Fc` inside the postgres container to `deploy/backups/lares-YYYYMMDD-HHMMSS.dump`,
  keeps the last 14 files. `restore.sh <file>` stops `server`, recreates the database, `pg_restore`, then runs
  `UPDATE household_sync_state SET sync_epoch = gen_random_uuid();` (forces every client to resync — sync.md reset rule 4),
  starts `server`. Backups go to `deploy/backups/` (gitignored — family data must never be committed).
  Scripts are POSIX `sh`, `set -eu`, work from any directory (resolve paths relative to the script).
- **APK in release**: debug-signed APK is acceptable until M6 **only if** no release keystore is configured; the release
  workflow uses a release keystore when the secrets `ANDROID_KEYSTORE_BASE64`, `ANDROID_KEYSTORE_PASSWORD`,
  `ANDROID_KEY_ALIAS`, `ANDROID_KEY_PASSWORD` exist. Configure `signingConfigs.release` in `client/android` reading them
  from environment variables; never commit keystores.

## Steps

1. Dockerfile + `.dockerignore`; build locally: `./gradlew :server:installDist && docker build -f server/Dockerfile -t lares-server:dev .`
2. Compose file, Caddyfile, ntfy config, `.env.example`; `deploy/README.md` (short, links to the manual).
3. `smoke.sh`: copies `.env.example` to a temp env with `LARES_DOMAIN=localhost`, a generated DB password and
   `LARES_SETUP_CODE=SMK7E5T1AB` (valid Crockford base32), runs `docker compose up -d --build` (with a compose override that builds the image
   from the local `installDist`), waits for health, checks `https://localhost/health/ready` (with `-k`) and
   `/api/v1/instance`, performs setup with the known code, runs `backup.sh`, creates a tag via the sync API,
   runs `restore.sh` with the backup, verifies the tag is gone and the household exists, then `docker compose down -v`.
4. Release signing config in `client/android/build.gradle.kts` (env-based, optional).
5. `release.yml` (`permissions: contents: write, packages: write`): on tags `v*`: build and test; `installDist`; QEMU + Buildx; build and push `linux/amd64,linux/arm64`
   images tagged with the version and `latest`; build the APK; create a GitHub Release with the APK attached.
   `ci.yml`: add job `deploy-smoke` (ubuntu, Docker available): checkout, setup-java 21, setup-gradle,
   `./gradlew :server:installDist`, then `deploy/scripts/smoke.sh`.
   Validate workflow syntax with `actionlint` if available (install via Homebrew if missing; otherwise note it).
6. User manual (en and ru, same content): requirements (VPS 1 vCPU / 2 GB / 20 GB, domain, Docker), DNS records
   (`family.example.com`, optional `ntfy.family.example.com`), installation steps, finding the setup code
   (`docker compose logs server | grep "Setup code"`), installing the app and connecting, backups (cron example:
   daily `backup.sh`, copy backups off the server), restore, updating (`docker compose pull && docker compose up -d`),
   enabling instant notifications (`--profile push`), troubleshooting (ports 80/443 closed, DNS not propagated).
   Russian text must be natural, not a word-for-word translation.

## Acceptance

- `deploy/scripts/smoke.sh` passes locally (Docker running), including backup/restore verification.
- `docker stats` during the smoke test shows the server container under its `mem_limit`.
- Workflows pass `actionlint` (or the check is reported as unavailable).

## Docs to update

- AGENTS.md "Commands": build image, smoke test, backup/restore.
- [docs/user/README.md](../../user/README.md): link the new manuals.
- Foundation spec: FND-070, FND-071, FND-072 → `done`.
- [architecture overview](../../architecture/overview.md) if the deployment differs from the diagram.
- STATUS.md; plan status.

## Stop and report if

- Caddy cannot issue local certificates for `localhost` in the smoke test environment — report and propose an
  HTTP-only smoke variant instead of disabling HTTPS in production config.

## Handoff

Russian summary: how the family server is installed on a VPS and backed up; what was verified automatically; what the
customer will need to provide for a real installation (domain, VPS); next plan M0-11.
