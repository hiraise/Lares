---
id: M5-06
status: done
depends_on: [M5-05]
requirements: [BUD-011, BUD-013, BUD-021]
---
# M5-06: Month-only changes, extra incomes, Today card, manual, M5 wrap-up

## Goal

The last budget pieces: changing one month without the plan (marked everywhere), adding an extra income, the Today
card, the user manual "Budget", and the M5 wrap-up (spec `implemented` once the customer accepts).

## Read first

- [Budget design](../../architecture/budget.md) § UI (Month edit, Today card, extra income), § Client (writer
  `editMonth`, `resetMonth`, extra income functions; `today`)
- [Budget spec](../../product/features/budget.md) § Scenario 4; BUD-011, BUD-013, BUD-021
- Code templates: `ui/main/TodayViewModel.kt`, `Sections.kt` (the «Семья сейчас» card of M4-10 as the newest card);
  user manuals `docs/user/en/map.md`, `docs/user/ru/map.md` (structure); the M4-10 plan's wrap-up steps

## Scope

- Month mode of `PlanScreen` (M5-05): opened from the Budget screen menu «Изменить этот месяц»; title «Только
  <месяц>»; no history; saving calls `editMonth`; differences from the plan marked with a dot in the lists and the
  table. «Вернуть как в плане» in the Budget menu (when the month differs) asks and calls `resetMonth`.
- Differences on the Budget screen and income page: a dot «изменено» on changed categories and cells (from
  `MonthView.differences` / `FundingRow.changed`), a line under the hero «В этом месяце план изменён» that opens the
  month editor.
- Extra income: «Добавить поступление» on the Budget screen opens a sheet (название — optional, сумма, дата); extra
  incomes appear after planned ones with their label or «Дополнительное поступление»; their page has no categories and
  a «Удалить» action.
- Today card (BUD-021) after «Семья сейчас», only with `budget.view` and a plan.
- User manual `docs/user/en/budget.md` and `docs/user/ru/budget.md`: the plan, a month, ticks, month-only changes,
  versions, who sees what; linked from the manuals index.
- Wrap-up: budget spec progress notes complete; roadmap § M5 "Implemented: …"; CHANGELOG; STATUS (M5 implemented,
  acceptance next); plans README statuses.

Out of scope: reminders (Q-032), analytics (Q-033).

## Decisions (do not revisit)

- Month mode starts its draft from `tableFor(month)` (the month copy if any, else the plan's table).
- A one-off category added in month mode is a normal category in the month copy (new id); it never enters the plan.
- The Today card: title «Бюджет»; line 1 the next income whose actual amount is not recorded (planned date ≥ today,
  else the earliest unrecorded one) «20 декабря · 160 000 ₽»; line 2 «Не переведено: N категорий» (planned cells of
  recorded incomes not ticked; hidden when 0); all recorded and funded → «Всё распределено». Tap → Budget screen.
- Extra income label ≤ 60 characters; amount required; date default today.

## Steps

1. Month mode, reset, difference marks; unit tests for the view model in month mode (save writes `editMonth`, equal to
   plan writes null).
2. Extra income sheet and page; Today card; strings.
3. UI tests: scenario 4 (Gifts 30 000 only in December) shows the dot and the line under the hero; «Вернуть как в
   плане» clears it; an extra income adds to free money and can be deleted; Today card texts for the three states;
   no card without `budget.view` or without a plan.
4. Manual, CHANGELOG, roadmap, spec, STATUS.
5. Final emulator walkthrough by `emulator-tester` of all seven spec scenarios in ru, light and dark, with a member
   from the Kids group; screenshots for the customer.

## Acceptance

- Full gate `tools/dev/verify.sh`; emulator report without open bugs.

## Docs to update

- Everything listed in Scope § Wrap-up; budget design "Implementation notes (M5-06)".

## Stop and report if

- A spec scenario cannot be walked through end to end.

## Handoff

Russian summary for the M5 acceptance: what the budget does now, how to try it on the family's phones (APK and server
update), screenshots, the open questions Q-032 (напоминания) and Q-033 (аналитика).
