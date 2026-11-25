# Lessons moved to the plan-marshall repository (2026-11-24)

On the operator's word ("move all plan-marshall related stuff in the corresponding inbox"), the 76 lessons of this repository's lessons store whose component
is `plan-marshall:*` or a domain bundle `pm-*` (74 and, in a second step the same day, 2 `pm-dev-java`) were filed as `candidate-lesson` messages into the `lessons-routing` epic of `/home/oliver/git/plan-marshall`
(`.plan/local/worktrees/_orchestrator/.plan/orchestrator/lessons-routing/inbox/`, sender `libraries-initial-implementation`), and then retired here with
`manage-lessons remove --coverage-verdict superseded` (the tombstone names the message). One `finding` message, `libraries-initial-implementation-075.md`, carries the
epic's tooling findings that are no lessons (review-gate and Sonar reads undecidable on merged PRs, landing facts left `unknown`, token and wall figures,
the silent `manage-logging decision`, the stale architecture inventory). The 9 lessons of this repository's own modules stay in this store (8 `git-store`, 1 `git-core`). The messages sit as untracked files in the plan-marshall ledger worktree until that repository lands its ledger.
Lesson ids cited elsewhere in this ledger (`landings/PLAN-NN.md`, `epic.md`) for those components resolve through this table.

| Lesson id (here, retired) | Component | Inbox message |
|---|---|---|
| `2026-11-24-07-001` | `plan-marshall:phase-3-outline` | `libraries-initial-implementation-001.md` |
| `2026-11-18-07-001` | `plan-marshall:phase-3-outline` | `libraries-initial-implementation-002.md` |
| `2026-11-18-07-002` | `plan-marshall:manage-findings` | `libraries-initial-implementation-003.md` |
| `2026-11-18-07-003` | `plan-marshall:phase-6-finalize` | `libraries-initial-implementation-004.md` |
| `2026-11-18-07-004` | `plan-marshall:phase-3-outline` | `libraries-initial-implementation-005.md` |
| `2026-11-18-07-005` | `plan-marshall:phase-5-execute` | `libraries-initial-implementation-006.md` |
| `2026-11-18-07-006` | `plan-marshall:manage-metrics` | `libraries-initial-implementation-007.md` |
| `2026-11-18-07-007` | `plan-marshall:phase-6-finalize` | `libraries-initial-implementation-008.md` |
| `2026-11-18-13-001` | `plan-marshall:automatic-review` | `libraries-initial-implementation-009.md` |
| `2026-11-18-13-002` | `plan-marshall:workflow-integration-git` | `libraries-initial-implementation-010.md` |
| `2026-11-18-13-003` | `plan-marshall:manage-findings` | `libraries-initial-implementation-011.md` |
| `2026-11-18-13-004` | `plan-marshall:manage-change-ledger` | `libraries-initial-implementation-012.md` |
| `2026-11-18-13-005` | `plan-marshall:automatic-review` | `libraries-initial-implementation-013.md` |
| `2026-11-18-13-006` | `` | `libraries-initial-implementation-014.md` |
| `2026-11-18-13-007` | `plan-marshall:execute-task` | `libraries-initial-implementation-015.md` |
| `2026-11-18-13-008` | `plan-marshall:automatic-review` | `libraries-initial-implementation-016.md` |
| `2026-11-18-13-009` | `plan-marshall:manage-metrics` | `libraries-initial-implementation-017.md` |
| `2026-11-18-18-001` | `plan-marshall:phase-3-outline` | `libraries-initial-implementation-018.md` |
| `2026-11-18-18-002` | `plan-marshall:phase-3-outline` | `libraries-initial-implementation-019.md` |
| `2026-11-18-18-003` | `plan-marshall:manage-architecture` | `libraries-initial-implementation-020.md` |
| `2026-11-18-18-004` | `plan-marshall:build-server-client` | `libraries-initial-implementation-021.md` |
| `2026-11-19-06-001` | `` | `libraries-initial-implementation-022.md` |
| `2026-11-19-06-002` | `` | `libraries-initial-implementation-023.md` |
| `2026-11-19-06-003` | `plan-marshall:phase-6-finalize` | `libraries-initial-implementation-024.md` |
| `2026-11-19-06-004` | `plan-marshall:phase-5-execute` | `libraries-initial-implementation-025.md` |
| `2026-11-19-06-005` | `plan-marshall:manage-change-ledger` | `libraries-initial-implementation-026.md` |
| `2026-11-19-06-006` | `plan-marshall:phase-5-execute` | `libraries-initial-implementation-027.md` |
| `2026-11-19-06-007` | `plan-marshall:execute-task` | `libraries-initial-implementation-028.md` |
| `2026-11-19-06-008` | `plan-marshall:execute-task` | `libraries-initial-implementation-029.md` |
| `2026-11-19-14-001` | `` | `libraries-initial-implementation-030.md` |
| `2026-11-19-14-002` | `plan-marshall:manage-metrics` | `libraries-initial-implementation-031.md` |
| `2026-11-19-14-003` | `plan-marshall:phase-4-plan` | `libraries-initial-implementation-032.md` |
| `2026-11-19-19-002` | `plan-marshall:manage-tasks` | `libraries-initial-implementation-033.md` |
| `2026-11-19-19-003` | `plan-marshall:phase-3-outline` | `libraries-initial-implementation-034.md` |
| `2026-11-19-19-004` | `plan-marshall:workflow-integration-git` | `libraries-initial-implementation-035.md` |
| `2026-11-20-07-001` | `plan-marshall:phase-3-outline` | `libraries-initial-implementation-036.md` |
| `2026-11-20-07-002` | `plan-marshall:phase-3-outline` | `libraries-initial-implementation-037.md` |
| `2026-11-20-07-003` | `plan-marshall:phase-3-outline` | `libraries-initial-implementation-038.md` |
| `2026-11-20-07-004` | `plan-marshall:manage-architecture` | `libraries-initial-implementation-039.md` |
| `2026-11-20-07-005` | `plan-marshall:manage-solution-outline` | `libraries-initial-implementation-040.md` |
| `2026-11-20-14-001` | `plan-marshall:phase-3-outline` | `libraries-initial-implementation-041.md` |
| `2026-11-20-14-002` | `plan-marshall:phase-3-outline` | `libraries-initial-implementation-042.md` |
| `2026-11-20-17-001` | `plan-marshall:manage-references` | `libraries-initial-implementation-043.md` |
| `2026-11-20-18-001` | `plan-marshall:phase-6-finalize` | `libraries-initial-implementation-044.md` |
| `2026-11-21-12-001` | `plan-marshall:workflow-integration-git` | `libraries-initial-implementation-045.md` |
| `2026-11-21-12-002` | `plan-marshall:phase-6-finalize` | `libraries-initial-implementation-046.md` |
| `2026-11-22-13-001` | `plan-marshall:tools-script-executor` | `libraries-initial-implementation-047.md` |
| `2026-11-22-13-002` | `plan-marshall:workflow-integration-sonar` | `libraries-initial-implementation-048.md` |
| `2026-11-22-13-004` | `plan-marshall:plan-marshall` | `libraries-initial-implementation-049.md` |
| `2026-11-22-13-005` | `plan-marshall:platform-runtime` | `libraries-initial-implementation-050.md` |
| `2026-11-22-13-006` | `plan-marshall:phase-3-outline` | `libraries-initial-implementation-051.md` |
| `2026-11-22-21-002` | `plan-marshall:phase-6-finalize` | `libraries-initial-implementation-052.md` |
| `2026-11-22-21-003` | `plan-marshall:phase-6-finalize` | `libraries-initial-implementation-053.md` |
| `2026-11-22-21-004` | `plan-marshall:phase-6-finalize` | `libraries-initial-implementation-054.md` |
| `2026-11-23-12-001` | `plan-marshall:phase-3-outline` | `libraries-initial-implementation-055.md` |
| `2026-11-23-12-002` | `plan-marshall:phase-5-execute` | `libraries-initial-implementation-056.md` |
| `2026-11-23-12-003` | `plan-marshall:manage-metrics` | `libraries-initial-implementation-057.md` |
| `2026-11-23-12-004` | `plan-marshall:build-maven` | `libraries-initial-implementation-058.md` |
| `2026-11-23-12-005` | `plan-marshall:manage-execution-manifest` | `libraries-initial-implementation-059.md` |
| `2026-11-23-12-006` | `plan-marshall:platform-runtime` | `libraries-initial-implementation-060.md` |
| `2026-11-23-14-007` | `plan-marshall:manage-findings` | `libraries-initial-implementation-061.md` |
| `2026-11-23-14-008` | `plan-marshall:plan-marshall` | `libraries-initial-implementation-062.md` |
| `2026-11-23-14-009` | `plan-marshall:phase-6-finalize` | `libraries-initial-implementation-063.md` |
| `2026-11-23-14-010` | `plan-marshall:phase-5-execute` | `libraries-initial-implementation-064.md` |
| `2026-11-23-14-011` | `plan-marshall:phase-6-finalize` | `libraries-initial-implementation-065.md` |
| `2026-11-24-07-003` | `` | `libraries-initial-implementation-066.md` |
| `2026-11-24-07-004` | `plan-marshall:phase-3-outline` | `libraries-initial-implementation-067.md` |
| `2026-11-24-07-006` | `plan-marshall:phase-3-outline` | `libraries-initial-implementation-068.md` |
| `2026-11-24-07-007` | `plan-marshall:phase-4-plan` | `libraries-initial-implementation-069.md` |
| `2026-11-24-07-008` | `plan-marshall:phase-6-finalize` | `libraries-initial-implementation-070.md` |
| `2026-11-24-07-009` | `plan-marshall:plan-marshall` | `libraries-initial-implementation-071.md` |
| `2026-11-24-07-010` | `plan-marshall:phase-6-finalize` | `libraries-initial-implementation-072.md` |
| `2026-11-24-07-011` | `plan-marshall:phase-6-finalize` | `libraries-initial-implementation-073.md` |
| `2026-11-24-07-012` | `plan-marshall:manage-execution-manifest` | `libraries-initial-implementation-074.md` |
| `2026-11-19-19-001` | `pm-dev-java:junit-core` | `libraries-initial-implementation-076.md` |
| `2026-11-22-13-003` | `pm-dev-java:ext-triage-java` | `libraries-initial-implementation-077.md` |

Moved at the drain of 2026-11-24 (PLAN-13's landing): eight `candidate-lesson` inbox messages of the plan `plan-13-forms-output-workflow-stepup` were never promoted
into this repository's lessons store; they went straight from the inbox into the `lessons-routing` inbox of `/home/oliver/git/plan-marshall`, and the local messages
were archived. The first column names the local inbox message instead of a lesson id.

| Local inbox message (archived) | Component | Inbox message |
|---|---|---|
| `plan-13-forms-output-workflow-stepup-001.md` | `plan-marshall:workflow-integration-sonar` | `libraries-initial-implementation-080.md` |
| `plan-13-forms-output-workflow-stepup-002.md` | `plan-marshall:manage-change-ledger` | `libraries-initial-implementation-081.md` |
| `plan-13-forms-output-workflow-stepup-003.md` | `plan-marshall:phase-5-execute` | `libraries-initial-implementation-082.md` |
| `plan-13-forms-output-workflow-stepup-004.md` | `plan-marshall:recipe-code-review` | `libraries-initial-implementation-083.md` |
| `plan-13-forms-output-workflow-stepup-005.md` | `plan-marshall:manage-status` | `libraries-initial-implementation-084.md` |
| `plan-13-forms-output-workflow-stepup-006.md` | `plan-marshall:phase-6-finalize` | `libraries-initial-implementation-085.md` |
| `plan-13-forms-output-workflow-stepup-007.md` | `plan-marshall:phase-5-execute` | `libraries-initial-implementation-086.md` |
| `plan-13-forms-output-workflow-stepup-008.md` | `plan-marshall:phase-2-refine` | `libraries-initial-implementation-087.md` |

Moved at the drain of 2026-11-25 (PLAN-17's landing), the same way: eleven `candidate-lesson` inbox messages of the plan `plan-17-timestamp-rfc3161`.
Message 005 is a recurrence of `-081` (and of `2026-11-18-13-004`, `-012`) and says so in its payload.

| Local inbox message (archived) | Component | Inbox message |
|---|---|---|
| `plan-17-timestamp-rfc3161-001.md` | `pm-dev-java:java-core` | `libraries-initial-implementation-088.md` |
| `plan-17-timestamp-rfc3161-002.md` | `plan-marshall:workflow-integration-sonar` | `libraries-initial-implementation-089.md` |
| `plan-17-timestamp-rfc3161-003.md` | `plan-marshall:automatic-review` | `libraries-initial-implementation-090.md` |
| `plan-17-timestamp-rfc3161-004.md` | `plan-marshall:tools-file-ops` | `libraries-initial-implementation-091.md` |
| `plan-17-timestamp-rfc3161-005.md` | `plan-marshall:manage-change-ledger` | `libraries-initial-implementation-092.md` |
| `plan-17-timestamp-rfc3161-006.md` | `plan-marshall:phase-6-finalize` | `libraries-initial-implementation-093.md` |
| `plan-17-timestamp-rfc3161-007.md` | `plan-marshall:workflow-integration-git` | `libraries-initial-implementation-094.md` |
| `plan-17-timestamp-rfc3161-008.md` | `plan-marshall:workflow-integration-sonar` | `libraries-initial-implementation-095.md` |
| `plan-17-timestamp-rfc3161-009.md` | `plan-marshall:phase-2-refine` | `libraries-initial-implementation-096.md` |
| `plan-17-timestamp-rfc3161-010.md` | `plan-marshall:build-maven` | `libraries-initial-implementation-097.md` |
| `plan-17-timestamp-rfc3161-011.md` | `plan-marshall:execution-context` | `libraries-initial-implementation-098.md` |
