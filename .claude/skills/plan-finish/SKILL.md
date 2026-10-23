---
name: plan-finish
description: Close a Lares work item or plan — Definition of Done check, docs, STATUS and history, Russian handoff for the customer, commit only after approval. Use at the end of every plan or work item, before presenting results to the customer.
---

# Finish a work item

## 1. Gate

- `tools/dev/verify.sh` (full gate: spotlessApply, check, debug APK) passes. Run it in the background.
- Plan "Acceptance" items are met, or each unmet one is listed with the reason.
- Manual check done by the `emulator-tester` agent when the plan asks for one (screenshots in `build/dev/shots/`).

## 2. Definition of Done (docs/process/workflow.md)

- [ ] Requirement states in the spec updated (`done`, or what remains).
- [ ] Tests cover the behavior, incl. permissions and household isolation where relevant.
- [ ] UI strings in en and ru; none hardcoded.
- [ ] No personal data in logs; no new third-party network calls; new dependencies license-checked.
- [ ] Docs: the plan's "Docs to update", glossary (new terms), ADR (new decision), CHANGELOG (user-visible changes).

## 3. Status

- `docs/plans/README.md`: plan status `done` (or `blocked` with the reason).
- `docs/history.md`: add one entry at the end (date, plan id, what was built, test counts, notable fixes). Keep it
  to a short paragraph.
- `docs/STATUS.md`: update "Phase", "Last completed", "Next steps", "Waiting for the customer", risks. Keep STATUS
  short: history goes to history.md, not here.

## 4. Handoff (Russian, for the customer)

Plain language, outcome first, no jargon. Cover the plan's "Handoff" section, and also:
what can be tried now and how; how it was tested (counts, not details); deviations from the plan and why; screenshots
(paths); new dependencies; the next plan. Then ask for approval to commit.

## 5. Commit (only after the customer approves)

- `git add` the files of this work item only; check `git status` first.
- Conventional Commit with the plan id: `feat(client): family management screens (M0-08)`.
- End the message with the attribution lines from the session's system reminder.
- After the commit: `tools/dev/server.sh stop`, `tools/dev/emu.sh stop` if you started them.
