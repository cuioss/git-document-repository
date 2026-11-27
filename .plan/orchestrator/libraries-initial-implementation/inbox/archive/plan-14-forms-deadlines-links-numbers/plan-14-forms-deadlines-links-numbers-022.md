envelope_version=1
sender_type=plan
sender_id=plan-14-forms-deadlines-links-numbers
epic=libraries-initial-implementation
kind=candidate-lesson
created=2026-11-27T20:01:40Z

# Expose the Sonar project key to sonar-roundtrip leaves

- Source: plan-retrospective (llm-to-script-opportunities, chat-history-analysis)
- Plan: plan-14-forms-deadlines-links-numbers
- Component hint: plan-marshall:workflow-integration-sonar
- Category: improvement
- Confidence: high

## Context

Across five sonar-roundtrip iterations the leaves located the project key three different ways: reading `sonar.project-key` from `.github/project.yml`, a `git grep` through Bash (a rule break, returned nothing), and the README badge URLs ("because the script surfaces do not expose it").

## Root cause

The Sonar provider's verbs need the project key but no script verb returns it, so every leaf rediscovers it by hand.

## Proposed action

Resolve the project key inside the provider (from `.github/project.yml` or marshal.json) or add a read verb that returns it, so the leaf passes nothing it had to find itself.

## Evidence

- sonar-roundtrip iteration 3: "the key is sonar.project-key in .github/project.yml"
- iteration 4: "I once used git grep through Bash to look for the Sonar key"
- iteration 5: "The key came from the README badge URLs, because the script surfaces do not expose it"
