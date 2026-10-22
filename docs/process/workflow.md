# Workflow

How agents work on this project. The customer sets goals and accepts results; agents do everything else.

## Session start

1. Read [AGENTS.md](../../AGENTS.md) → [STATUS.md](../STATUS.md).
2. Read the spec and ADRs for the area you touch; check [open questions](../product/open-questions.md) affecting it.
3. Confirm the build and tests pass before changing anything (once code exists).

## Milestone lifecycle

1. **Detail the spec** right before the milestone: requirements with IDs, permissions, assumptions.
   Ask the customer the open product questions, in Russian, briefly, with a recommended option.
2. **Customer approves the spec** (status `approved`). Technical design does not need customer approval.
3. **Design:** write ADRs for significant choices; update architecture/model docs.
4. **Plan:** break the milestone into small work items in STATUS.md (or GitHub Issues once the repo exists).
5. **Implement** work items one by one (see below).
6. **Accept:** build an APK + server image, write a short Russian changelog for the customer, collect feedback,
   turn feedback into requirements or open questions.

## Work item loop

1. Write or update tests first where practical; reference requirement IDs.
2. Implement; keep the change focused.
3. Run build, lint and tests locally.
4. Self-review the diff (correctness, security, privacy, boundaries); run `/code-review` for non-trivial changes.
5. Update docs in the same change (see DoD).
6. Commit per [Git until MVP](#git-until-mvp), Conventional Commits with requirement IDs:
   `feat(tasks): overdue notification (TSK-033)`.

## Git until MVP

Decided by the customer, 2026-10-22.

- Work directly on `main` in the local repository. The customer adds the GitHub remote later.
- Before committing, show the customer a short Russian summary of the change (what and why, files touched,
  tests run). Commit only after explicit approval. One approval may cover one work item or a few related ones.
- Never rewrite published history; never force-push.
- After the MVP (M6) a release flow replaces this: feature branches, pull requests, CI gate, version tags.
  It will be defined in an ADR at M6.

## Definition of Done

- [ ] Requirement(s) implemented as specified; spec requirement state updated (`done`).
- [ ] Tests cover the behavior, including permissions and household isolation where relevant.
- [ ] Build, lint and all tests pass.
- [ ] UI strings in en and ru; no hardcoded strings.
- [ ] No personal data in logs; no new third-party network calls.
- [ ] New dependencies are AGPL-compatible and justified.
- [ ] Docs updated: STATUS.md, spec, ADR (if a decision was made), glossary (if new terms), CHANGELOG (user-visible changes).

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

Update STATUS.md: done, in progress, next steps, blockers, new risks.
