envelope_version=1
sender_type=plan
sender_id=plan-10-store-signing-trailers-verify
epic=libraries-initial-implementation
kind=candidate-lesson
created=2026-11-23T14:35:21Z

# Candidate lesson: external-tool compatibility tests could skip or hang instead of failing

Source: review-bot findings 81bfd8 (ExternalTools.java:103, interrupted wait left the child process running) and 3f202d (ExternalTools.java:168, the probe key came from the store's own encoder), both resolution fixed (TASK-17, TASK-18). The first review body (b99d0f, handled in TASK-13) raised five more of the same class: HOME isolation that ignores XDG_CONFIG_HOME, readAllBytes before the timed wait, `ssh-keygen -?` as a capability probe, ssh and ssh-keygen picked from different installations, and a missing POSIX-permission guard.

Component: git-store tests (ExternalTools, GitVerifyCommitCompatibilityTest, SshSignaturesTest, FileSigningKeySourceTest)

## What happened

The cross-checks against stock git and ssh-keygen are guarded by `assumeTrue` probes. Several probes could be passed or failed for the wrong reason. The worst case: the probe used a key encoded by `SshSignatures.publicKeyBlob`, so a broken encoder made the probe fail and the compatibility test skip, which hid the very defect the test exists to catch.

## Candidate rule

A capability probe that guards an interoperability test must be independent of the code under test: generate its fixtures with the external tool itself. Probe the exact executable the test then uses, for the exact operation it needs. Run subprocesses with output redirected to a file, a deadline on the wait, and destroy the child in a finally block. Isolate git configuration with GIT_CONFIG_GLOBAL and GIT_CONFIG_NOSYSTEM, not HOME alone.
