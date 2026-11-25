envelope_version=1
sender_type=plan
sender_id=plan-22-store-hardening
epic=libraries-initial-implementation
kind=candidate-lesson
created=2026-11-25T08:06:58Z

Candidate lesson (PR #76 review, recurring defect: rendering a remote for logs and results)

What happened: The rendering of remote URLs was fixed twice in the same PR. TASK-10 fixed remote rendering among the bounded-read findings. In a later round, comment a96abd led TASK-17/18 to also strip query and fragment text from the rendered remote.

Evidence: the work-log titles of TASK-10 ("remote rendering") and TASK-17 ("remote rendering of query and fragment text"), and the pr-comment findings resolved as fixed.

Suggested rule: Render a remote through one function that keeps only scheme, host, port and path and drops user info, query and fragment. Test it with a table covering every URL component, so a later review round cannot find another component that leaks.
