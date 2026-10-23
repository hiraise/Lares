# Installing the family server

Lares keeps your family's data on your own server. This guide sets it up on a rented virtual server (VPS) with
Docker Compose. You will need about an hour and basic comfort with a Linux terminal (copying commands over SSH).

The server runs four parts: the Lares server, its PostgreSQL database, **Caddy** (gets and renews the HTTPS
certificate automatically) and, optionally, **ntfy** for instant notifications.

## What you need

- **A VPS**: 1 vCPU, 2 GB RAM, 20 GB disk, Ubuntu 24.04 or Debian 12, x86_64 or ARM64. 1 GB RAM works for one family,
  but leaves no headroom. Choose a data center close to where your family lives: the app feels faster.
- **A domain name** you control, e.g. `example.com`. The family server gets a subdomain such as `family.example.com`.
- **Open ports 80 and 443** on the VPS (check the provider's firewall settings too).
- **Docker** with the Compose plugin (installed below).

## 1. DNS records

At your domain registrar or DNS provider, create:

| Name | Type | Value | Needed for |
| --- | --- | --- | --- |
| `family.example.com` | A (and AAAA if the VPS has IPv6) | IP address of the VPS | always |
| `ntfy.family.example.com` | A (and AAAA) | the same IP address | only for instant notifications |

DNS changes can take from a few minutes to a few hours. Check with `ping family.example.com`: it must show the VPS address.

## 2. Install Docker

Log in to the VPS over SSH and run:

```sh
curl -fsSL https://get.docker.com | sudo sh
docker compose version
```

The second command must print a version. The commands below are run as root or with `sudo`.

## 3. Get the configuration

Put the project's `deploy` folder on the server, for example in `/opt/lares`:

```sh
sudo git clone <project repository URL> /opt/lares
cd /opt/lares/deploy
```

## 4. Configure

```sh
cp .env.example .env
chmod 600 .env
openssl rand -hex 24      # prints a random password
nano .env
```

In `.env` set:

- `LARES_DOMAIN` — your server's domain, e.g. `family.example.com`.
- `POSTGRES_PASSWORD` and `LARES_DB_PASSWORD` — the random password, **the same value in both**.

Everything else is optional and explained in the file. Keep `.env` private: it contains the database password.

## 5. Start

The server image `ghcr.io/hiraise/lares-server` is published with each project release. If `docker compose up` cannot
pull it (`denied` or `not found`), the image is not public: log in once with a GitHub token that has the
`read:packages` permission, `docker login ghcr.io -u <github-user>`, and repeat.

```sh
docker compose up -d
docker compose ps
```

After a minute all services show `running` (the server `healthy`). Open `https://family.example.com/health/ready` in a
browser: it should answer `{"status":"ok"}` over HTTPS.

## 6. Find the setup code

A new server is protected by a one-time setup code, so nobody else can claim it first:

```sh
docker compose logs server | grep "Setup code"
```

The code is 10 letters and digits. You can also choose it yourself in advance with `LARES_SETUP_CODE` in `.env`.

## 7. Install the app and create your family

1. Install the Lares APK on your Android phone (download it from the project's releases page; allow installing
   apps from this source when Android asks).
2. Open the app, enter the **Server address** `https://family.example.com`.
3. The app sees a new server and shows **New family**: enter the setup code, family name, time zone, currency,
   family language, then your name, login and password as the administrator. Tap **Create family**.
4. Invite the rest of the family from the app: **More** → **Family**. Each person scans the invitation QR code or enters
   the invitation code; children can get accounts without a password.

## Backups

The family's data lives in the database volume. Back it up daily and keep copies **off** the server: if the VPS is
lost, a backup on the same VPS is lost with it.

Make a backup now:

```sh
/opt/lares/deploy/scripts/backup.sh
```

It writes `deploy/backups/lares-YYYYMMDD-HHMMSS.dump` and keeps the newest 14 files.

Daily at 03:00, with `sudo crontab -e`:

```
0 3 * * * /opt/lares/deploy/scripts/backup.sh >> /var/log/lares-backup.log 2>&1
```

Copy the backups elsewhere regularly, e.g. from your home computer:

```sh
rsync -av root@family.example.com:/opt/lares/deploy/backups/ ~/lares-backups/
```

Backups contain all family data, including locations and finances: store them as carefully as the server itself.

## Restore

```sh
/opt/lares/deploy/scripts/restore.sh /opt/lares/deploy/backups/lares-20261023-030000.dump
```

The script asks for confirmation, replaces **all** current data with the backup and restarts the server. Phones notice
the restore on their next sync and reload all data from the server automatically. Changes saved on the server after the
backup are lost; changes that were still waiting on a phone (made offline) are sent again.

To move to a new VPS: install as above (steps 1–5, same `.env`), copy the latest backup over and restore it.

## Updating

Make a backup first, then:

```sh
cd /opt/lares/deploy
git pull
docker compose pull
docker compose up -d
```

The server updates its database automatically on start. To control when updates happen, pin a version in `.env`
(`LARES_VERSION=0.1.0`) and change it deliberately.

## Instant notifications (optional)

Without this step notifications still arrive, but with a delay of up to about 15 minutes. With your own ntfy server
they arrive within seconds, and still without Google services.

1. Create the DNS record `ntfy.family.example.com` (step 1).
2. In `.env` uncomment `LARES_CADDYFILE=Caddyfile.push` and `COMPOSE_PROFILES=push`. If the ntfy domain is not `ntfy.` + your domain, also set
   `LARES_NTFY_DOMAIN`.
3. Start with the push profile and allow anonymous access to push topics only (one time):

   ```sh
   docker compose --profile push up -d
   docker compose exec ntfy ntfy access '*' 'up*' read-write
   ```

4. With `COMPOSE_PROFILES=push` in `.env` every later `docker compose` command includes ntfy; without it add
   `--profile push` each time.
5. Each family member sets up the ntfy app on their phone:
   [Instant notifications with ntfy](instant-notifications.md).

The ntfy server only accepts the app's own push topics; it cannot be used by strangers for anything else.

## Already running a web server?

If nginx or Traefik already uses ports 80 and 443 on this machine, remove the `caddy` service from your setup and proxy
`family.example.com` to the `server` container on port 8080 (and `ntfy.…` to `ntfy` on port 80) with your web server.
Pass the `X-Forwarded-For` header.

## Troubleshooting

- **The browser cannot open the address, or the certificate is invalid.** Caddy gets the certificate only when
  `family.example.com` points at this VPS and ports 80 and 443 are reachable from the internet. Check
  `ping family.example.com`, the VPS provider's firewall and `ufw status`, then `docker compose logs caddy`.
- **DNS has not updated yet.** Wait and try again; Caddy retries on its own. `docker compose restart caddy` speeds it up.
- **The app says the setup code is wrong.** Copy it again from `docker compose logs server | grep "Setup code"`; it
  changes on every restart unless `LARES_SETUP_CODE` is set.
- **The server keeps restarting.** Look at `docker compose logs server`. The most common cause is different values in
  `POSTGRES_PASSWORD` and `LARES_DB_PASSWORD`. Note: changing `POSTGRES_PASSWORD` later does not change the password
  of an existing database.
- **Instant notifications do not arrive.** Check `https://ntfy.family.example.com` opens in a browser, that the
  `ntfy access` command was run, and the phone-side steps in [the ntfy guide](instant-notifications.md).
