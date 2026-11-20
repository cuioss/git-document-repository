envelope_version=1
sender_type=plan
sender_id=plan-07-store-module-registry-spi
epic=libraries-initial-implementation
kind=candidate-lesson
created=2026-11-20T12:21:18Z

# Candidate lesson: review-bot findings fixed in-run over two rounds, each loop-back invited a new round

**Source:** automatic-review signal (remediated-in-run): 15 pr-comment findings resolved fixed, plus 10 Sonar
findings, over two loop-back iterations
**Plan:** plan-07-store-module-registry-spi (PR #41, merged)

## Observation

- Round 1 (loop-back 1/3): fix tasks 12, 13, 14 for 18 review/Sonar findings. Examples: the provider-selection
  rule in `doc/specification/git-store.adoc` contradicted the overview paragraph (two providers with the same
  id stop the start vs. only a configured id does); Sonar S1192, S1845 and S5778 in registry, configuration
  and PersonEntryTest; a JGit 7.8.0 port-overflow claim in RepositoryRegistry; StoreProviders secrecy and
  module `requires`.
- Round 2 (loop-back 2/3): fix task 15 for 7 new CodeRabbit findings raised on the pushed fix head.
- CodeRabbit re-reviews every pushed head, so every loop-back round can raise a further review round.

## Candidate rules

1. Defect class: a spec edit that changes a rule must also update every summary or overview paragraph that
   restates it in the same document (the CodeRabbit spec-contradiction finding).
2. Process: because the review bot re-reviews every pushed head, consider batching fixes before pushing, or
   budget for at least one extra review round per loop-back.
