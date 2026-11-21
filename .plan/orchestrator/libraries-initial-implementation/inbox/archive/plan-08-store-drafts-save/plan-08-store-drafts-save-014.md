envelope_version=1
sender_type=plan
sender_id=plan-08-store-drafts-save
epic=libraries-initial-implementation
kind=candidate-lesson
created=2026-11-21T10:53:51Z

# Candidate lesson: a file/directory path overlap was not detected as a conflict (review bot, fixed in run)

- Signal: review-bot finding remediated in run, hash 11ad35, PR #48, CodeRabbit inline at `ExplicitSave.java:335`
- Fix: TASK-14, fix commit 9f3e622d
- Plan: plan-08-store-drafts-save (git-document-repository)

## Observation

A draft adding `config/app.yml` while a concurrent writer added the file `config` passed the zero-id expectation check. JGit's default `PathEdit` replacement then silently removed the remote file `config`. The lease did not catch this, because the lease tip already contained `config`.

## Resolution in run

File and directory overlaps are detected before the edits are applied and are returned as a conflict.

## Possible rule (for the orchestrator to classify)

Conflict detection on tree edits must cover prefix overlaps (file versus directory), not only exact-path changes.
