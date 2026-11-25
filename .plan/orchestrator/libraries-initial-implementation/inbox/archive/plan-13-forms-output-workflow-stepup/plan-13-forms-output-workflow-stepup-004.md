envelope_version=1
sender_type=plan
sender_id=plan-13-forms-output-workflow-stepup
epic=libraries-initial-implementation
kind=candidate-lesson
created=2026-11-24T16:19:43Z

## Candidate lesson: the review bot found defect classes the self-review missed, in three rounds

Signal: automated review (CodeRabbit on PR #64, remediated in-run, 3 loop-backs).

What happened: CodeRabbit found real defects in git-forms over three review rounds:
- a lost update (read-modify-write of a reference without compare-and-swap on the value that was read);
- a release that was not pinned to the commit it was checked against;
- numbers rendered in exponential notation by the YAML/JSON emitter;
- missing size bounds on untrusted input.

Each round cost a loop-back and a new review wait. Also, origin/main moved during the review and forced a rebase with conflicts in the docs.

Suggested rule: before the first push of a store/forms write path, check the diff against four questions. Is every read-modify-write of a reference done by compare-and-swap on the id that was read? Is every release or tag pinned to the commit that was validated? Is every number emitted in canonical non-exponential form? Is every untrusted input bounded in size and depth? To keep rebases small during long reviews, sync with origin/main before every push of a fix round, not only at the end.

Component hint: plan-marshall:recipe-code-review / plan-marshall:persona-code-reviewer (pre-push self-review checklist), project git-forms and git-store.
