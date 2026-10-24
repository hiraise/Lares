---
id: M2-06
status: ready
depends_on: [M2-05]
requirements: [SHP-012, SHP-013, SHP-014, TAG-004, FND-063]
---
# M2-06: Today shopping card, tag page section, manual and M2 wrap-up

## Goal

Finish M2: "Today" shows the active shopping list as the gradient hero card with who is shopping; the tag page gets a
«Покупки» section; user manual "Shopping" (en, ru), changelog, spec states and the M2 acceptance checklist. After this
plan M2 is ready for acceptance with the customer.

## Read first

- [Shopping spec](../../product/features/shopping.md) — whole; [tags spec](../../product/features/tags.md) TAG-004;
  [foundation spec](../../product/features/foundation.md) FND-063
- Mockups `docs/design/mockups/Main.dc.html` and `TodayDark.dc.html` — section «Активный список покупок» (exact sizes:
  radius 24, padding 18, gap 10; label row 13 sp 600 with a `shopping_cart` 20 dp icon and the count pill 13 sp 700 on
  white 20 %; title 22 sp 800; remaining line 14 sp; progress 6 dp, white on white 28 %; bottom row:
  shopper avatar + text 13 sp 600, white pill button «Открыть» 36 dp with `#1B45C4` text)
- [Visual style](../../design/visual-style.md) § Lake gradient (allowed on this hero card and its progress bar only)
- [Shopping design](../../architecture/shopping.md) § Models and views (`active`, `forTag`)
- Code: `ui/main/Sections.kt` (`TodayScreen`, `TodayTasks`), `ui/main/TodayViewModel.kt`, `ui/main/MainScaffold.kt`
  (`TodayActions`, tab switching), `ui/family/TagPage.kt` (`TagViewModel`, tasks section), `ui/theme/Colors.kt`
  (gradient brush), M2-04/05 `ui/shopping/*`; tests `TodayViewModelTest.kt`, `TagViewModelTest.kt`;
  `docs/user/ru/tasks.md` (manual style)

## Scope

- Today hero card (SHP-013, SHP-012) and the «Ещё N списка» link.
- Tag page «Покупки» section (TAG-004, SHP-014).
- `docs/user/en/shopping.md`, `docs/user/ru/shopping.md`; `docs/user/README.md` list.
- CHANGELOG `[Unreleased]` complete for M2; spec, tags spec, foundation FND-063 notes, roadmap M2 state.

Out of scope: shopping notifications (SHP-017), Today blocks of later modules.

## Decisions (do not revisit)

- **Today card** (only when `observeActive()` is non-null, i.e. the member sees Shopping and some list has items to
  buy): placed first on Today, above the tasks block, 24 dp apart. `TodayViewModel` combines `ShoppingRepository
  .observeActive()` with its task inputs; `TodayUiState.shopping: TodayShoppingUi?` (`listId`, `name`, `bought`,
  `total`, `remaining: List<String>`, `more: Int`, `shopper: MemberView?`, `othersToBuy: Int`). Content as in the mockup:
  «Список покупок» + count pill «6 из 9»; the list name; «Осталось: Молоко, Хлеб, Яблоки» (names as stored, + « и ещё N» when more); all text is 100 % white — the mockup's
  90–92 % opacities fall below 4.5:1 at the light end of the gradient, so they are not used;
  gradient progress (white fill, bought / total); bottom row — when `shopper` is set, a 24 dp avatar (personal color,
  white 2 dp ring) + «Дмитрий сейчас в магазине», otherwise nothing; «Открыть» pill → the list page. Under the card,
  when `othersToBuy > 0`, a `LinkButton` «Ещё 2 списка» (plural) → the Shopping tab. The Today empty state shows only
  when neither the shopping card nor the tasks block is shown.
- **Tag page**: `TagViewModel` adds `ShoppingRepository.observeForTag(tagId)`; a section «Покупки · N» after the tasks
  section, only when it has rows: item rows (name, meta = list name, trailing quantity; a tap opens the list page with
  the tag filter set to this tag — add an optional `tagId` to `ShoppingListDestination`/`ShoppingListArgs`), then list
  rows (`shopping_cart` icon, name, «Осталось N»; a tap opens the list).
- **Manual** (`shopping.md`, en and ru, style of `tasks.md`): where lists are (tab, Today card), creating lists, adding
  several items in one line with quantities, departments and correcting them, ticking in the store without network,
  «Куплено» and «Убрать купленные», suggestions and «Больше не предлагать», who is shopping, tags, who may do what
  (Adults / Kids table in plain words). No screenshots.

## Steps

1. Today card (view model, composable, navigation wiring for «Открыть» and «Ещё N списка»).
2. Tag page section; `tagId` argument of the list page.
3. Manual en/ru; README of `docs/user`.
4. Tests:
   - jvmTest `TodayViewModelTest` (extend): no card without lists to buy; the most recently changed list wins;
     `othersToBuy`; shopper appears and expires after 15 minutes with the test clock; a member without `shopping.view`
     gets no card; empty state only when nothing is shown.
   - jvmTest `TagViewModelTest` (extend): shopping items and lists with the tag; section absent without rows.
   - Compose UI test: the card renders name, count, remaining and «Открыть»; `ScreensUiTest` Today variants still pass.
5. Emulator walkthrough via the `emulator-tester` agent — the M2 acceptance run: `server.sh reset`, `seed.py family`,
   `seed.py tasks`, `seed.py shopping`; as Ольга: run `seed.py tick Хлеб --as dima`, then the Today card shows «Продукты» with «Дмитрий сейчас в
   магазине», «Открыть», «Ещё 2 списка»; tag page «Дача» from Family › Tags shows the shopping
   section and opens the filtered list; then the spec scenarios 1–7 end to end (scenario 2 offline as in M2-04:
   `adb reverse --remove tcp:8080`) — for scenarios 3 and 4 keep Ольга's list
   open and run `seed.py tick Сметана --as dima`: the tick appears within seconds; ru and en; light and dark.
   Screenshots: Today with the card (ru light, ru dark), tag page.

## Acceptance

- Full gate `tools/dev/verify.sh` passes.
- Emulator walkthrough done (or skipped with the reason), screenshot paths in the handoff.
- Every MVP requirement of the shopping spec is `done` (or explicitly reported as not done with the reason).

## Docs to update

- Shopping spec: all MVP requirements `done` with short notes; status stays `approved` until the customer accepts M2
  (then `implemented`, done by the planning session).
- Tags spec TAG-002/003/004 notes; foundation FND-063 note (shopping card).
- Roadmap: M2 state "implemented (M2-01…M2-06); customer acceptance pending".
- CHANGELOG `[Unreleased]` — M2 complete in user language.
- STATUS.md: phase and next steps (M2 acceptance; then M3 plans); history.md; plan status.

## Stop and report if

- A layout of the card from the mockup does not fit a 360 dp wide screen without clipping the list name or the button.

## Handoff

Russian summary with screenshots: the active list on Today with who is in the store, shopping on the tag page, the
manual. Then the M2 acceptance checklist for the customer (Russian, plain words): what to try on the phones — create
lists, add several items in one line, tick offline in a store, two people shopping at once, suggestions next week,
correcting a department, kids' rights.
