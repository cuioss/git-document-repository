envelope_version=1
sender_type=plan
sender_id=plan-22-store-hardening
epic=libraries-initial-implementation
kind=candidate-lesson
created=2026-11-25T08:06:48Z

Candidate lesson (Q-Gate 3-outline, finding c2c182, false_positive_check, D3/D4)

What happened: D3 and D4 planned new or changed log records (a WARN for an unreadable StoreIndex source, changed GIT_STORE-7 semantics, class-only records for throwing providers and signing sources). The module's LogRecord holder GitStoreLogMessages.java and the catalogue check LogMessagesCatalogueTest.java were in no deliverable's affected files.

Evidence: qgate finding c2c182. Resolved as taken_into_account: GitStoreLogMessages.java, LogMessagesCatalogueTest.java and LogMessages.adoc were added to the task file lists, and TASK-5/7 added or changed GIT_STORE-112, 113, 6, 7 and 110.

Suggested rule: In this repository a deliverable that adds or changes a log record also touches the module's LogRecord holder, its LogMessages.adoc and the catalogue test. List all three, or state that no identifier changes.
