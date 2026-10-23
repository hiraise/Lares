# Workflow

How agents work on this project. The customer sets goals and accepts results; agents do everything else.

## Session start

1. [AGENTS.md](../../AGENTS.md) is loaded automatically (via `CLAUDE.md`); read [STATUS.md](../STATUS.md).
   For a plan, start with `/plan-run <plan id>` (skill in `.claude/skills/`).
2. Read the spec sections and ADRs for the area you touch; check [open questions](../product/open-questions.md)
   affecting it. Read sections, not whole documents (see AGENTS.md "Working economically").
3. Confirm the build and tests pass before changing anything (once code exists).

## Milestone lifecycle

1. **Detail the spec** right before the milestone: requirements with IDs, permissions, assumptions.
   Ask the customer the open product questions, in Russian, briefly, with a recommended option.
2. **Customer approves the spec** (status `approved`). Technical design does not need customer approval.
3. **Design:** write ADRs for significant choices; update architecture/model docs.
4. **Plan:** a planning session (high effort) writes detailed, self-contained plans in [docs/plans/](../plans/README.md):
   one plan per work item, with all technical decisions made, so an implementation session with a clean context
   (medium effort) can execute it without re-deciding anything.
5. **Implement** plans one by one in fresh sessions (see [plans README](../plans/README.md) and the loop below).
6. **Accept:** build an APK + server image, write a short Russian changelog for the customer, collect feedback,
   turn feedback into requirements or open questions.

## Work item loop

1. Write or update tests first where practical; reference requirement IDs.
2. Implement; keep the change focused.
3. Run build, lint and tests locally with `tools/dev/verify.sh` (skill `build-check`). Manual app checks go to the
   `emulator-tester` agent (skill `emulator`) so screenshots stay out of the implementation session.
4. Self-review the diff (correctness, security, privacy, boundaries); run `/code-review` for non-trivial changes.
5. Update docs in the same change (see DoD).
6. Close the item with the `plan-finish` skill; commit per [Git until MVP](#git-until-mvp), Conventional Commits
   with requirement IDs:
   `feat(tasks): overdue notification (TSK-033)`.

## Git until MVP

Decided by the customer, 2026-10-22.

- Work directly on `main` in the local repository. The customer adds the GitHub remote later.
- Before committing, show the customer a short Russian summary of the change (what and why, files touched,
  tests run). Commit only after explicit approval. One approval may cover one work item or a few related ones.
- Never rewrite published history; never force-push.
- After the MVP (M6) a branch flow replaces this: feature branches, pull requests, CI gate. It will be defined in an
  ADR at M6.
- Releases (version tags, signed APK) already follow [process/release.md](release.md) (ADR-0019).

## Definition of Done

- [ ] Requirement(s) implemented as specified; spec requirement state updated (`done`).
- [ ] Tests cover the behavior, including permissions and household isolation where relevant.
- [ ] Build, lint and all tests pass.
- [ ] UI strings in en and ru; no hardcoded strings.
- [ ] No personal data in logs; no new third-party network calls.
- [ ] New dependencies are AGPL-compatible and justified.
- [ ] Docs updated: STATUS.md, [history.md](../history.md), spec, ADR (if a decision was made), glossary (if new terms),
  CHANGELOG (user-visible changes).

## Escalation

Ask the customer (via open-questions.md and chat):

- product behavior not covered by a spec, scope changes, priorities;
- anything user-visible that changes the agreed UX;
- anything that costs money, creates external accounts, or publishes something (public repo, releases, store listings).

Do **not** ask the customer about: languages, libraries, architecture, code structure, test strategy.
Decide, record an ADR if significant, and mention it in the milestone changelog only if it affects the customer.

When blocked on a product question: choose a reversible default, mark it `ASSUMPTION (Q-NNN)` in the spec, continue.

## Communicating with the customer

- Russian, short, plain language, outcome first.
- Questions come with options and a recommendation.
- Milestone changelog: what can be tried now, what changed, known issues — no technical detail unless asked.

## Session end

Update STATUS.md (last completed, in progress, next steps, blockers, new risks) and add a short entry to
[history.md](../history.md). Keep STATUS.md short; the log of past work lives in history.md.
