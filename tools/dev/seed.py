#!/usr/bin/env python3
"""Fill the local dev server (tools/dev/server.sh) with a test family and talk to its API.

  tools/dev/seed.py family                 # setup + test family (skips setup if already done)
  tools/dev/seed.py token [login]          # access token (default olga)
  tools/dev/seed.py login-code [name]      # one-time login code + QR payload for a seeded member (default: olga herself)
  tools/dev/seed.py invite [group]         # invite into "Взрослые" (default) or "Дети"; prints code + payload
  tools/dev/seed.py tasks                  # sample tasks and routines (after family; skips if already seeded)
  tools/dev/seed.py shopping               # sample shopping lists (after family; skips if already seeded)
  tools/dev/seed.py calendar               # relatives with birthdays, sample events, memorable date (after family)
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
import uuid
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


def derive_row_id(event_id, salt, marker):
    """architecture/calendar.md § Derived ids, the same algorithm as Ids.kt; marker is xor-ed into byte 7 (0 = none)."""
    b = bytearray(uuid.UUID(event_id).bytes)
    for i, byte in enumerate((salt & 0xFFFFFFFFFFFFFFFF).to_bytes(8, "big")):
        b[8 + i] ^= byte
    b[7] ^= marker
    b[6] = (b[6] & 0x0F) | 0x80
    b[8] = (b[8] & 0x3F) | 0x80
    return str(uuid.UUID(bytes=bytes(b)))


def occurrence_row_id(event_id, occurrence):
    """The id of the event_occurrence row of `occurrence` (a date) of `event_id` (ADR-0022)."""
    return derive_row_id(event_id, (occurrence - datetime.date(1970, 1, 1)).days, marker=0)


def reminder_row_id(event_id, member_id):
    """The id of member_id's event_reminder row for event_id (ADR-0023)."""
    return derive_row_id(event_id, int.from_bytes(uuid.UUID(member_id).bytes[8:], "big"), marker=0x5A)


def place_alert_row_id(member_id, subscriber_id):
    """The id of subscriber_id's place_alert row for member_id (architecture/location.md § Sync data)."""
    return derive_row_id(member_id, int.from_bytes(uuid.UUID(subscriber_id).bytes[8:], "big"), marker=0x3C)


# Golden values of contract IdsTest (M3-01).
_GOLDEN_EVENT = "0192f3a4-5b6c-7d8e-9f01-23456789abcd"
assert occurrence_row_id(_GOLDEN_EVENT, datetime.date(2026, 10, 28)) == "0192f3a4-5b6c-8d8e-9f01-23456789fadf"
assert occurrence_row_id(_GOLDEN_EVENT, datetime.date(1969, 12, 31)) == "0192f3a4-5b6c-8d8e-a0fe-dcba98765432"
assert reminder_row_id(_GOLDEN_EVENT, "0192f3a4-0000-7000-8000-00000000c0de") == "0192f3a4-5b6c-8dd4-9f01-234567896b13"
assert place_alert_row_id(_GOLDEN_EVENT, "0192f3a4-0000-7000-8000-00000000c0de") == "0192f3a4-5b6c-8db2-9f01-234567896b13"


def calendar():
    state = load_state()
    if not state.get("members"):
        sys.exit("no test family; run: seed.py family")
    if state.get("calendar"):
        print("calendar already seeded:", ", ".join(state["calendar"]))
        return
    token = login()
    me = call("GET", "/me", token=token)["memberId"]
    changes = all_changes(token)
    persons = {c["data"]["name"]: c["id"] for c in changes if c["type"] == "person"}
    olga, dima, anya, timur = (persons[n] for n in ("Ольга Петрова", "Дмитрий Петров", "Аня", "Тимур"))
    # Dates in the household zone (Europe/Moscow, UTC+3 all year).
    today = (datetime.datetime.now(datetime.timezone.utc) + datetime.timedelta(hours=3)).date()

    def days(n):
        return today + datetime.timedelta(days=n)

    mutations = []
    valya, kolya = uuid7(), uuid7()
    born = days(3)
    mutations.append(("person", valya, {"name": "Бабушка Валя", "relation": "Бабушка", "birthYear": 1948, "birthMonth": born.month, "birthDay": born.day}))
    born = days(12)
    mutations.append(("person", kolya, {"name": "Дядя Коля", "relation": "Дядя", "birthMonth": born.month, "birthDay": born.day}))
    born = days(40)
    mutations.append(("person", anya, {"birthYear": 2015, "birthMonth": born.month, "birthDay": born.day}))
    tag = next((c["id"] for c in changes if c["type"] == "tag" and c["data"].get("name") == "Школа"), None)
    if tag is None:
        tag = uuid7()
        mutations.append(("tag", tag, {"name": "Школа", "color": "green", "archived": False}))

    def timed(start, end, start_date, end_date=None):
        return {"startDate": str(start_date), "endDate": str(end_date or start_date), "allDay": False, "startTime": start, "endTime": end}

    weekday = today.weekday()
    last_monday = days(-weekday)
    next_wednesday = days((2 - weekday) % 7 or 7)
    moved_monday = next_wednesday + datetime.timedelta(days=5)
    next_saturday = days((5 - weekday) % 7 or 7)
    memorable = days(10)
    samples = [
        ("Стоматолог", {**timed("16:00", "17:00", days(1)), "participantIds": [timur], "place": "Клиника на Ленина", "reminderMinutesBefore": 60}),
        (
            "Танцы",
            {
                **timed("17:30", "18:45", last_monday),
                "participantIds": [anya],
                "place": "ДК «Юность»",
                "recurrence": {"mode": "fixed", "unit": "week", "interval": 1, "weekdays": ["MONDAY", "WEDNESDAY"]},
                "repeatUntil": str(days(180)),
            },
        ),
        ("Родительское собрание", {**timed("18:30", "19:30", days(4)), "participantIds": [olga, dima], "place": "Школа №12", "tagIds": [tag]}),
        ("Генеральная уборка", timed("11:00", "13:00", next_saturday)),
        ("Отпуск", {"startDate": str(days(20)), "endDate": str(days(30)), "allDay": True, "participantIds": [olga, dima, anya, timur]}),
        (
            "Годовщина свадьбы",
            {
                "startDate": str(memorable.replace(year=2010)),
                "endDate": str(memorable.replace(year=2010)),
                "allDay": True,
                "participantIds": [olga, dima],
                "recurrence": {"mode": "fixed", "unit": "year", "interval": 1, "weekdays": []},
                "kind": "memorable_date",
                "countYears": True,
            },
        ),
    ]
    created = {}
    for title, fields in samples:
        eid = uuid7()
        created[title] = eid
        mutations.append(("event", eid, {"title": title, "createdBy": me, **fields}))
    dances = created["Танцы"]
    mutations.append(("event_occurrence", occurrence_row_id(dances, next_wednesday), {"eventId": dances, "occurrence": str(next_wednesday), "cancelled": True}))
    mutations.append(
        (
            "event_occurrence",
            occurrence_row_id(dances, moved_monday),
            {
                "eventId": dances,
                "occurrence": str(moved_monday),
                "startDate": str(moved_monday),
                "endDate": str(moved_monday),
                "startTime": "18:00",
                "endTime": "19:15",
            },
        )
    )
    push(token, mutations)
    state["calendar"] = created
    save_state(state)
    print("calendar:", ", ".join(created), "+ Бабушка Валя, Дядя Коля, birthday of Аня")
    print(f"Танцы: {next_wednesday} cancelled, {moved_monday} moved to 18:00–19:15")


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
    elif cmd == "calendar":
        calendar()
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
