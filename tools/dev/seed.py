#!/usr/bin/env python3
"""Fill the local dev server (tools/dev/server.sh) with a test family and talk to its API.

  tools/dev/seed.py family                 # setup + test family (skips setup if already done)
  tools/dev/seed.py token [login]          # access token (default olga)
  tools/dev/seed.py login-code [name]      # one-time login code + QR payload for a seeded member (default: olga herself)
  tools/dev/seed.py invite [group]         # invite into "Взрослые" (default) or "Дети"; prints code + payload
  tools/dev/seed.py tasks                  # sample tasks and routines (after family; skips if already seeded)
  tools/dev/seed.py shopping               # sample shopping lists (after family; skips if already seeded)
  tools/dev/seed.py tick NAME [--as LOGIN] # mark the first item named NAME bought by that member (default olga)
  tools/dev/seed.py call METHOD PATH [JSON] [--as LOGIN]

Test family "Петровы" (setup code DEVSETUP01):
  Ольга Петрова  olga / olga-password  administrator (built-in group)
  Дмитрий Петров dima / dima-password  Взрослые
  Аня, Тимур     no login              Дети
A payload is a deep link; open it on the emulator with: tools/dev/emu.sh link '<payload>'.
State (member ids) is kept in build/dev/seed.json. Env: LARES_PORT (default 8080).
"""
import datetime
import json
import os
import secrets
import sys
import time
import urllib.error
import urllib.request

ROOT = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
STATE = os.path.join(ROOT, "build", "dev", "seed.json")
BASE = f"http://localhost:{os.environ.get('LARES_PORT', '8080')}/api/v1"
DEVICE = {"name": "seed script", "platform": "cli"}


def call(method, path, body=None, token=None):
    data = json.dumps(body).encode() if body is not None else None
    req = urllib.request.Request(BASE + path, method=method, data=data)
    req.add_header("Content-Type", "application/json")
    req.add_header("X-Lares-Protocol", "1")
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


def uuid7():
    """A UUIDv7 like the apps create (time-ordered, so tasks sort in creation order)."""
    ms = int(time.time() * 1000)
    raw = bytearray(ms.to_bytes(6, "big") + secrets.token_bytes(10))
    raw[6] = (raw[6] & 0x0F) | 0x70
    raw[8] = (raw[8] & 0x3F) | 0x80
    h = raw.hex()
    return f"{h[:8]}-{h[8:12]}-{h[12:16]}-{h[16:20]}-{h[20:]}"


def push(token, mutations):
    now = datetime.datetime.now(datetime.timezone.utc).isoformat().replace("+00:00", "Z")
    body = {
        "mutations": [
            {"mutationId": uuid7(), "type": t, "id": i, "op": "upsert", "fields": f, "createdAt": now}
            for t, i, f in mutations
        ]
    }
    for r in call("POST", "/sync/mutations", body, token)["results"]:
        if r["status"] != "applied":
            sys.exit(f"mutation rejected: {json.dumps(r, ensure_ascii=False)}")


def tasks():
    state = load_state()
    if not state.get("members"):
        sys.exit("no test family; run: seed.py family")
    if state.get("tasks"):
        print("tasks already seeded:", ", ".join(state["tasks"]))
        return
    token = login()
    me = call("GET", "/me", token=token)["memberId"]
    dima, anya, timur = member_id("Дмитрий"), member_id("Аня"), member_id("Тимур")
    changes = call("GET", "/sync/changes?after=0", token=token)["changes"]
    tag = next((c["id"] for c in changes if c["type"] == "tag" and c.get("data", {}).get("name") == "Машина"), None)
    mutations = []
    if tag is None:
        tag = uuid7()
        mutations.append(("tag", tag, {"name": "Машина", "color": "blue", "archived": False}))
    # Values must be canonical (the server rejects fields that change in a decode round trip, e.g. a missing
    # "weekdays": []). Dates in the household zone (Europe/Moscow, UTC+3 all year).
    today = (datetime.datetime.now(datetime.timezone.utc) + datetime.timedelta(hours=3)).date()
    day = datetime.timedelta(days=1)
    week_saturday = today + datetime.timedelta(days=(5 - today.weekday()) % 7)
    samples = [
        ("Забрать посылку", {"dueDate": str(today + day), "dueTime": "18:30", "assigneeIds": [dima], "reminderMinutesBefore": 60}),
        (
            "Вынести мусор",
            {
                "dueDate": str(today),
                "dueTime": "20:00",
                "assigneeIds": [anya, timur],
                "recurrence": {"mode": "fixed", "unit": "week", "interval": 1, "weekdays": ["MONDAY", "THURSDAY"]},
                "reminderMinutesBefore": 15,
                "notifyCreatorOnDone": True,
            },
        ),
        (
            "Уборка в комнате",
            {
                "dueDate": str(week_saturday),
                "recurrence": {"mode": "fixed", "unit": "week", "interval": 1, "weekdays": ["SATURDAY"]},
            },
        ),
        (
            "Поменять фильтр для воды",
            {"dueDate": str(today), "recurrence": {"mode": "after_completion", "unit": "month", "interval": 3, "weekdays": []}},
        ),
        ("Оплатить интернет", {"dueDate": str(today - day), "assigneeIds": [me], "notes": "Счёт пришёл на почту"}),
        ("Поменять резину", {"tagIds": [tag], "assigneeIds": [dima]}),
    ]
    created = {}
    for title, fields in samples:
        tid = uuid7()
        created[title] = tid
        mutations.append(("task", tid, {"title": title, "createdBy": me, **fields}))
    for pos, text in enumerate(["Пропылесосить", "Протереть пыль", "Разобрать стол"]):
        mutations.append(("checklist_item", uuid7(), {"taskId": created["Уборка в комнате"], "text": text, "position": pos}))
    push(token, mutations)
    state["tasks"] = created
    save_state(state)
    print("tasks:", ", ".join(created))


def all_changes(token):
    """Every live change visible to the token's member (a full pull from 0)."""
    changes, after = [], 0
    while True:
        page = call("GET", f"/sync/changes?after={after}", token=token)
        changes += [c for c in page["changes"] if not c.get("deleted")]
        if not page.get("hasMore"):
            return changes
        after = page["cursor"]


def instant(moment):
    """Canonical instant in whole seconds (the server rejects values that change in a decode round trip)."""
    return moment.astimezone(datetime.timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ")


def shopping():
    state = load_state()
    if not state.get("members"):
        sys.exit("no test family; run: seed.py family")
    if state.get("shopping"):
        print("shopping already seeded:", ", ".join(state["shopping"]))
        return
    token = login()
    me = call("GET", "/me", token=token)["memberId"]
    tag = next(
        (c["id"] for c in all_changes(token) if c["type"] == "tag" and c.get("data", {}).get("name") == "Дача"), None
    )
    mutations = []
    if tag is None:
        tag = uuid7()
        mutations.append(("tag", tag, {"name": "Дача", "color": "green", "archived": False}))
    now = datetime.datetime.now(datetime.timezone.utc).replace(microsecond=0)
    lists = {}

    def new_list(name, tag_ids=()):
        time.sleep(0.002)  # distinct milliseconds keep the creation order of the UUIDv7 ids
        lid = uuid7()
        lists[name] = lid
        mutations.append(("shopping_list", lid, {"name": name, "createdBy": me, "tagIds": list(tag_ids)}))
        return lid

    def item(list_id, name, category, added, quantity=None, note=None, tag_ids=(), status="needed", bought_at=None, count=0):
        fields = {
            "listId": list_id,
            "name": name,
            "addedBy": me,
            "addedAt": instant(added),
            "status": status,
            "category": category,
            "tagIds": list(tag_ids),
            "boughtCount": count,
        }
        if quantity:
            fields["quantity"] = quantity
        if note:
            fields["note"] = note
        if bought_at:
            fields.update(boughtBy=me, boughtAt=instant(bought_at))
        iid = uuid7()
        mutations.append(("shopping_item", iid, fields))
        return iid

    day, minute = datetime.timedelta(days=1), datetime.timedelta(minutes=1)
    pharmacy = new_list("Аптека")
    item(pharmacy, "Парацетамол", "pharmacy", now - 3 * day)
    item(pharmacy, "Пластырь", "pharmacy", now - 3 * day)
    dacha = new_list("Дача", [tag])
    item(dacha, "Уголь", "other", now - 2 * day)
    item(dacha, "Перчатки", "other", now - 2 * day, tag_ids=[tag])
    groceries = new_list("Продукты")
    to_buy = [
        ("Молоко 2,5%", "dairy", "2 л", "Без лактозы, если будет"),
        ("Хлеб", "bakery", None, None),
        ("Яблоки", "produce", "1 кг", None),
        ("Курица", "meat_fish", None, None),
        ("Гречка", "grocery", None, None),
        ("Шоколад", "sweets", None, None),
        ("Туалетная бумага", "household", None, None),
        ("Сметана", "dairy", None, None),
    ]
    for i, (name, category, quantity, note) in enumerate(to_buy):
        item(groceries, name, category, now - (50 - i) * minute, quantity, note)
    cleared = [("Яйца", "dairy", 9), ("Сыр", "dairy", 7), ("Бананы", "produce", 6), ("Творог", "dairy", 4),
               ("Кефир", "dairy", 3), ("Огурцы", "produce", 2)]
    for i, (name, category, count) in enumerate(cleared):
        bought = now - (i + 1) * 4 * day
        item(groceries, name, category, bought - day, status="cleared", bought_at=bought, count=count)
    push(token, mutations)
    state["shopping"] = lists
    save_state(state)
    for name in ("Гречка", "Туалетная бумага"):
        tick(name, "dima", now - minute)
    print("shopping lists:", ", ".join(lists))


def tick(name, as_login="olga", at=None):
    """Marks the first live item named NAME (any list, ignoring case) bought by that member."""
    token = login(as_login)
    me = call("GET", "/me", token=token)["memberId"]
    found = next(
        (
            c
            for c in all_changes(token)
            if c["type"] == "shopping_item" and c["data"]["name"].lower() == name.lower()
        ),
        None,
    )
    if found is None:
        sys.exit(f"no shopping item named {name!r}")
    at = at or datetime.datetime.now(datetime.timezone.utc)
    fields = {"status": "bought", "boughtBy": me, "boughtAt": instant(at), "boughtCount": found["data"].get("boughtCount", 0) + 1}
    push(token, [("shopping_item", found["id"], fields)])
    print(f"ticked {found['data']['name']} as {as_login}")


def main(argv):
    if not argv or argv[0] in ("-h", "--help"):
        print(__doc__)
        return
    cmd, args = argv[0], argv[1:]
    if cmd == "family":
        family()
    elif cmd == "tasks":
        tasks()
    elif cmd == "shopping":
        shopping()
    elif cmd == "tick":
        as_login = "olga"
        if "--as" in args:
            i = args.index("--as")
            as_login = args[i + 1]
            args = args[:i] + args[i + 2 :]
        if not args:
            sys.exit("usage: seed.py tick NAME [--as LOGIN]")
        tick(" ".join(args), as_login)
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
