envelope_version=1
sender_type=plan
sender_id=git-testing-fixtures
epic=libraries-initial-implementation
kind=candidate-lesson
created=2026-11-18T06:52:17Z

# Candidate lesson: JUnit constructor-injected fixtures must use the TEST_METHOD extension-context scope

- Source signal: automatic-review, remediated review-bot finding ddd4e9 (coderabbitai, PR #23, LocalRepositoryExtension.java:107)
- Resolution in run: fixed by TASK-7, commit dfe96ce

## What happened

By default, Jupiter (6.1.3) resolves constructor parameters in the class extension context. Take a `PER_METHOD` test class with two tests. Both constructor fixtures were stored under the same store key, so the second registration replaced the first cleanup without closing it. The first repository leaked, and its temporary directory stayed on disk.

## Candidate rule

A `ParameterResolver` that registers a closeable per-parameter resource in the `ExtensionContext.Store` must override `getTestInstantiationExtensionContextScope` to return `TEST_METHOD`. Without it, constructor injection and method injection behave differently. All three git-testing resolvers now do this, and any later fixture resolver should do the same.

Classification is deferred to the orchestrator.
