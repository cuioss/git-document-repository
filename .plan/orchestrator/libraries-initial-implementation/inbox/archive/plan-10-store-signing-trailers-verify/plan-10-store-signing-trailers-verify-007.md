envelope_version=1
sender_type=plan
sender_id=plan-10-store-signing-trailers-verify
epic=libraries-initial-implementation
kind=candidate-lesson
created=2026-11-23T14:35:33Z

# Candidate lesson: a security-heavy git-store plan needed six review loop-backs before CodeRabbit ran dry

Source: automatic-review signal over PR #58: four CodeRabbit review rounds (commits 8fca54a, c156f44, 9ae4572, then a clean pass), 17 pr-comment findings (13 fixed, 3 taken into account or accepted), fix tasks TASK-10 to TASK-18, loop-back iterations 1/3 through 6/6 for wait-region-unified-triage. Two of the loop-backs were Sonar-only (S6206, S6878, S1135).

Component: epic planning (libraries-initial-implementation); git-store

## What happened

Each review round found new defects in code that the previous round's fixes had touched (release-subject guard, shallow-boundary check, signer bytes), plus fresh findings in untouched areas. The plan spent roughly five of its seven and a half hours in finalize review loops. CodeRabbit's allowance of one included review per hour paced the rounds.

## Candidate observation for the orchestrator

For plans that add signing, parsing of a security format, and recovery logic together, either split the deliverables into smaller plans (codec and signer; allowed signers and verifier; write-path integration) or budget for three or more review rounds. A self-review pass against the reference implementation (OpenSSH sshsig.c, git verify-commit) before the PR opens would likely have caught the AllowedSigners and shallow-history classes in round one.
