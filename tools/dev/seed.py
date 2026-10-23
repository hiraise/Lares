#!/usr/bin/env python3
"""Fill the local dev server (tools/dev/server.sh) with a test family and talk to its API.

  tools/dev/seed.py family                 # setup + test family (skips setup if already done)
  tools/dev/seed.py token [login]          # access token (default olga)
  tools/dev/seed.py login-code [name]      # one-time login code + QR payload for a seeded member (default: olga herself)
  tools/dev/seed.py invite [group]         # invite into "Взрослые" (default) or "Дети"; prints code + payload
  tools/dev/seed.py call METHOD PATH [JSON] [--as LOGIN]

Test family "Петровы" (setup code DEVSETUP01):
  Ольга Петрова  olga / olga-password  administrator (built-in group)
  Дмитрий Петров dima / dima-password  Взрослые
  Аня, Тимур     no login              Дети
A payload is a deep link; open it on the emulator with: tools/dev/emu.sh link '<payload>'.
State (member ids) is kept in build/dev/seed.json. Env: FAMILYHUB_PORT (default 8080).
"""
import json
import os
import sys
import urllib.error
import urllib.request

ROOT = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
STATE = os.path.join(ROOT, "build", "dev", "seed.json")
BASE = f"http://localhost:{os.environ.get('FAMILYHUB_PORT', '8080')}/api/v1"
DEVICE = {"name": "seed script", "platform": "cli"}


def call(method, path, body=None, token=None):
    data = json.dumps(body).encode() if body is not None else None
    req = urllib.request.Request(BASE + path, method=method, data=data)
    req.add_header("Content-Type", "application/json")
    req.add_header("X-FamilyHub-Protocol", "1")
    if token:
        req.add_header("Authorization", "Bearer " + token)
    try:
        with urllib.request.urlopen(req) as r:
            text = r.read().decode()
            return json.loads(text) if text else None
    except urllib.error.HTTPError as e:
        sys.exit(f"HTTP {e.code} {method} {path}: {e.read().decode()[:500]}")
    except urllib.error.URLError as e:
        sys.exit(f"server not reachable at {BASE} ({e.reason}); run tools/dev/server.sh start")


def login(name="olga", password=None):
    return call("POST", "/auth/login", {"login": name, "password": password or f"{name}-password", "device": DEVICE})[
        "accessToken"
    ]


def load_state():
    try:
        with open(STATE) as f:
            return json.load(f)
    except FileNotFoundError:
        return {"members": {}}


def save_state(state):
    os.makedirs(os.path.dirname(STATE), exist_ok=True)
    with open(STATE, "w") as f:
        json.dump(state, f, ensure_ascii=False, indent=2)


def groups(token):
    changes = call("GET", "/sync/changes?after=0", token=token)["changes"]
    return {c["data"]["name"]: c["id"] for c in changes if c["type"] == "group" and c.get("data")}


def family():
    state = {"members": {}}
    if not call("GET", "/instance")["initialized"]:
        call(
            "POST",
            "/setup",
            {
                "setupCode": "DEVSETUP01",
                "householdName": "Петровы",
                "timeZone": "Europe/Moscow",
                "currency": "RUB",
                "defaultLanguage": "ru",
                "displayName": "Ольга Петрова",
                "login": "olga",
                "password": "olga-password",
                "device": DEVICE,
            },
        )
        print("setup done: household Петровы, admin olga / olga-password")
    else:
        old = load_state()
        if old.get("members"):
            print("already seeded:", ", ".join(old["members"]))
            return
    token = login()
    g = groups(token)
    for name, login_name, group in [
        ("Дмитрий Петров", "dima", "Взрослые"),
        ("Аня", None, "Дети"),
        ("Тимур", None, "Дети"),
    ]:
        body = {"displayName": name, "groupIds": [g[group]]}
        if login_name:
            body.update(login=login_name, password=f"{login_name}-password")
        created = call("POST", "/members", body, token)
        state["members"][name] = created["memberId"]
        print(f"member {name} ({group}{', login ' + login_name if login_name else ''})")
    save_state(state)


def member_id(name):
    members = load_state()["members"]
    for full, mid in members.items():
        if full == name or full.split()[0] == name:
            return mid
    sys.exit(f"unknown member {name!r}; seeded: {', '.join(members) or 'none (run: seed.py family)'}")


def main(argv):
    if not argv or argv[0] in ("-h", "--help"):
        print(__doc__)
        return
    cmd, args = argv[0], argv[1:]
    if cmd == "family":
        family()
    elif cmd == "token":
        print(login(*args[:2]))
    elif cmd == "login-code":
        body = {"memberId": member_id(args[0])} if args and args[0] not in ("olga", "Ольга") else {}
        r = call("POST", "/login-codes", body, login())
        print(f"code {r['code']}\npayload {r['payload']}\nexpires {r['expiresAt']}")
    elif cmd == "invite":
        token = login()
        group = args[0] if args else "Взрослые"
        r = call("POST", "/invites", {"groupIds": [groups(token)[group]], "expiresInHours": 48}, token)
        print(f"code {r['code']}\npayload {r['payload']}\nexpires {r['expiresAt']}")
    elif cmd == "call":
        as_login = "olga"
        if "--as" in args:
            i = args.index("--as")
            as_login = args[i + 1]
            args = args[:i] + args[i + 2 :]
        body = json.loads(args[2]) if len(args) > 2 else None
        print(json.dumps(call(args[0].upper(), args[1], body, login(as_login)), ensure_ascii=False, indent=1))
    else:
        sys.exit(f"unknown command {cmd!r}\n{__doc__}")


if __name__ == "__main__":
    main(sys.argv[1:])
