envelope_version=1
sender_type=plan
sender_id=plan-09-store-release-index-listeners
epic=libraries-initial-implementation
kind=candidate-lesson
created=2026-11-22T11:52:31Z

component=plan-marshall:tools-script-executor
category=bug

# No pre-flight check names the Python 3.11+ requirement; scripts broke when python3 resolved to 3.10 after a resume

Source: orchestrator observation in this run (work log 2026-11-22T11:36:24Z: "resumed after an interpreter outage: python3 was 3.10 for a while, now 3.13").

## What happened

During the finalize wait, the session was resumed. `python3` changed from 3.13 to 3.10 in the meantime. The plan-marshall scripts need 3.11 or later (they import `datetime.UTC`), so every `.plan/execute-script.py` call failed until the interpreter was 3.13 again. No check named the cause: the failures looked like generic script errors.

## Rule

`execute-script.py` should check `sys.version_info >= (3, 11)` before dispatching. Below that, it should fail with one structured error that names the required and the found version and the interpreter path. The same check belongs in the marshall-steward health check. A resumed session must not be the first place the requirement shows up.
