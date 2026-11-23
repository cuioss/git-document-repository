envelope_version=1
sender_type=plan
sender_id=plan-10-store-signing-trailers-verify
epic=libraries-initial-implementation
kind=candidate-lesson
created=2026-11-23T14:34:53Z

# Candidate lesson: an allowed-signers parser written from scratch drifted from OpenSSH semantics in several places

Source: review-bot findings 9f4307 (AllowedSigners.java:246, validity interval end not after start) and 652d8f (AllowedSigners.java:314, Major, Security: repeated valid-before / valid-after / namespaces option overwrote the earlier value), both resolution fixed (TASK-17). The first review body (b99d0f, taken into account through TASK-10) raised three more of the same class: duplicate namespaces threw an unchecked exception, SMART date resolution turned 30 February into 28 February, and negated or wildcard namespace patterns were stored as literals.

Component: git-store (AllowedSigners)

## What happened

The store reimplements the OpenSSH allowed-signers format instead of calling ssh-keygen. Five gaps against OpenSSH's own parser (sshsig.c) surfaced over three review rounds. Two of them widened authorisation: an expired signer became valid again through a repeated option, and a key that OpenSSH refuses for `git` was accepted.

## Candidate rule

When code reimplements a security-relevant format whose reference parser exists (OpenSSH sshsig.c for allowed signers), list the reference parser's refusals before the first commit and test each one: repeated options, empty or inverted intervals, invalid calendar dates (ResolverStyle.STRICT), pattern syntax, duplicates. When a feature of the format is not implemented, refuse it at parse time instead of storing it as a literal.
