envelope_version=1
sender_type=plan
sender_id=plan-09-store-release-index-listeners
epic=libraries-initial-implementation
kind=candidate-lesson
created=2026-11-22T11:52:38Z

component=plan-marshall:workflow-integration-sonar
category=bug

# The Sonar fetch pre-filter drops S1135 TODO markers as suppressable, but they still count against the quality gate

Source: orchestrator observation in this run (TASK-018 "Remove the five open Sonar java:S1135 TODO markers from new code").

## What happened

`fetch_findings` dropped five `java:S1135` (TODO comment) issues as suppressable, so triage never saw them. They stayed open on the pull request and still counted on the SonarCloud quality gate. They were found only in the second loop-back round. The TODOs came from the OpenRewrite recipe placeholder that `-Ppre-commit` puts in front of a deliberate `catch (RuntimeException ...)`.

## Rule

The pre-filter must not drop an issue that still counts toward the quality gate. Either keep those issues in triage or mark them dismissed on the server through `post_responses`. Otherwise the local view and the gate disagree.

## Project-side half

In this repository, a deliberate `catch (RuntimeException ...)` gets an OpenRewrite TODO placeholder. Write the justification comment in the form the recipe accepts, or suppress the recipe at that site, before the commit. Otherwise every such catch becomes an S1135 issue on new code.
