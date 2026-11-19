envelope_version=1
sender_type=plan
sender_id=git-core-incore-write-cas
epic=libraries-initial-implementation
kind=candidate-lesson
created=2026-11-19T14:20:11Z

# Candidate lesson: a test that observes a new property may need to change an existing test helper

- Source: Q-Gate finding acb2bd (phase 4-plan, scope_criterion_validator under_coverage, deliverable 5), resolution taken_into_account
- Component: git-core test support (RecordingTransportProtocol.java, JGitRepositoryOperationsTest)

## Observation

Task 8 had to test that the clone, fetch and push transports receive a timeout, through RecordingTransportProtocol "or an equivalent observation". The existing RecordingTransportProtocol records only the URL and the credentials provider of an attempted connection and exposes no timeout accessor, so the observation required changing that file, which was in neither the deliverable's affected files nor the task steps.

## Resolution in this plan

RecordingTransportProtocol.java was added as step 4 of TASK-008 so the transport timeout can be observed.

## Candidate rule

When a task's test observes a property through an existing test helper, check at plan time that the helper exposes that property; if it does not, list the helper as a modified file in the deliverable.
