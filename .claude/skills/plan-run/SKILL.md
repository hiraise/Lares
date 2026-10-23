---
name: plan-run
description: Execute one implementation plan from docs/plans/ end to end as the executor (Lares). Use when the customer says "делаем M0-09", "приступаем к шагу M0-10" or runs /plan-run M0-09.
argument-hint: "[plan id, e.g. M0-09]"
---

# Execute plan $ARGUMENTS

You are the executor. Work strictly by the plan and the accepted ADRs. If a step is impossible or the plan contradicts
an ADR, stop and describe the problem; do not improvise. Do not commit until the customer approves.

## Start (keep it short)

1. AGENTS.md is already in your context (CLAUDE.md includes it). Do not read it again.
2. Read `docs/STATUS.md` and the plan file (`docs/plans/<milestone>/<NN>-*.md`; find it with `ls docs/plans/*/`).
3. "Read first": read only the named sections. Find them with `grep -n '^#' <file>`, then `sed -n 'A,Bp'`.
   Read a whole document only if the plan names the whole document.
4. Set the plan status to `in-progress` in `docs/plans/README.md`.

## While implementing

- **Reading code:** `grep -n` for the symbols you need, then read line ranges. Read a whole file only when you are
  about to change most of it. Do not re-read a file after editing it; your edit is already known.
- **Editing:** use the Edit tool for exact replacements; Write for new files.
- **Build and tests:** use the `build-check` skill (`tools/dev/verify.sh`). While iterating, run only the affected
  module or test class; run the full gate once before the handoff.
- **Long commands** (full gate, emulator boot): `run_in_background: true` and continue with other work; you are
  notified when it ends. Never `sleep N; cat …` to wait.
- **Manual app check:** delegate it to the `emulator-tester` agent (see the `emulator` skill). Do not drive the
  emulator or read screenshots in this session: they stay in the context and make every later step expensive.
- **Server check by hand:** `tools/dev/server.sh start` and `tools/dev/seed.py` (test family, tokens, codes, API calls).
- **Questions:** technical ones you decide yourself (ADR if significant). Product ones: ASSUMPTION + open question,
  as AGENTS.md says.

## Finish

Follow the `plan-finish` skill: Definition of Done, docs, STATUS, Russian handoff, commit after approval.
