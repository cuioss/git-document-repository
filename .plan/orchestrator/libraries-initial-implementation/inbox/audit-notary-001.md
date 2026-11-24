envelope_version=1
sender_type=orchestrator
sender_id=audit-notary
epic=libraries-initial-implementation
kind=finding
created=2026-11-24T10:59:11Z

# Consumer request (Audit Notary): a global step-up switch and single-factor step-up values in GDR-FORMS-7

Source: cuioss/Audit-Notary, branch `feature/local-users`, ADR-0005 "Local users from a mounted file are for evaluation and testing only" and AUN-AUTH-4 / AUN-AUTH-6 of its `doc/specification/authentication.adoc`. Decided by the owner on 2026-11-24.

## What the consumer decided

Audit Notary makes step-up configurable with one key, `step-up.enabled`, for both of its login providers:

* With the identity provider (`login.provider: oidc`) step-up is on by default. Some organisations do not want a confirmation per release and switch it off.
* With local test users (`login.provider: local`, a users file without an identity provider) step-up is off by default. When it is switched on, step-up is a renewed password check of the same user: the trailers then carry a fresh `auth_time` and `amr=pwd`.

## What GDR-FORMS-7 lacks

`doc/specification/git-forms.adoc`, GDR-FORMS-7, as of main 77a97a9:

1. **No switch for all transitions.** A workflow declares `step-up` per transition, and without a declaration every `release` transition requires step-up. A deployment cannot say "no transition requires step-up", whatever its workflows declare. `step-up.max-age` is also required, even when nothing would use it.
2. **The `step-up.amr` row says the values "denote multi-factor authentication"** and that a single method is no multi-factor authentication. A consumer that switches step-up on for password users configures `step-up.amr: [pwd]`. The check itself (a value of `step-up.amr` in `Auth-Context` and a fresh `Auth-Time`) already supports this. The wording reads as if a single-factor value were not allowed.

## Requested change

* A setting `step-up.enabled` (boolean, default `true`). When it is `false`, no transition requires step-up, whatever the workflow declares, and `step-up.amr`, `step-up.acr` and `step-up.max-age` are not required. The workflow validation (an `intermediate-save` transition must not declare step-up) stays as is.
* GDR-FORMS-7 states that the deployment decides which `amr`/`acr` values satisfy step-up: `mfa` stays the default, and a single method such as `pwd` is allowed when the deployment wants a renewed password check.

## Why the library and not the consumer

The library enforces step-up on every transition (GDR-FORMS-7), so the consumer cannot switch it off without bypassing the library's check. The consumer's open question records this dependency, and so do the "Risks" in its ADR-0005.

## Done when

GDR-FORMS-7 (spec, the config key table, and the implementation in git-forms) has the switch and the reworded `step-up.amr` row, with tests for: switch off (a `release` transition without step-up passes), switch on with `step-up.amr: [pwd]` (a fresh `pwd` passes, a stale `Auth-Time` is refused).
