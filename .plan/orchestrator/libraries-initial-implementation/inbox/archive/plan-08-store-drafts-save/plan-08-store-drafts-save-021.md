envelope_version=1
sender_type=plan
sender_id=plan-08-store-drafts-save
epic=libraries-initial-implementation
kind=candidate-lesson
created=2026-11-21T10:54:24Z

# Candidate lesson: 18 valid review-bot findings after a green local build; Sonar found 33 more on tests and nullable JGit values

- Signal: automatic-review outcome (signal 2), summary across the run
- Plan: plan-08-store-drafts-save (git-document-repository), PR #48

## Observation

The local build, including `verify -Ppre-commit`, was green. CodeRabbit then raised 18 valid findings: 12 inline across two rounds plus the minor and nitpick items of one review body, sent as separate messages. Most were race and atomicity defects in draft, expiry and save: check-then-CAS windows, lock scope, retry identity, misread JGit result codes, and TOCTOU guards. The Sonar roundtrip then produced 33 findings on test code (S5778: more than one call that can throw inside an `assertThrows` lambda; S1130: unused `throws` declarations) and S4449 on nullable JGit return values passed to non-null parameters. All were fixed in code, with no suppression.

## Possible rule (for the orchestrator to classify)

- For git-store concurrency work, ask for an explicit interleaving analysis in the outline or the task (which operations share which lock, and what is read before the CAS). Ask for pause-point tests, because unit tests did not catch any of these.
- In test authoring, keep one throwing call per `assertThrows` lambda and drop unused `throws`. Treat JGit returns such as `getRef`, `resolve` and `getObjectId` as `@Nullable` under JSpecify `@NullMarked`, and null-check them before passing them on.
