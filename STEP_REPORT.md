# STEP H1 — Part 2 Implementation: Finding 1 (Slack authorization gap)

Option A implemented, with the revisions given after Part 1 landed (below). Not committed
(per instruction — working tree only).

---

## Summary of Part 1 (context, already confirmed)

Finding 13 (cross-tenant leak via `IncidentEmbeddingService.findSimilar()`) — **verified
clear, not a leak.** No code change. See prior recon: the candidate query uses an explicit
JPA `WHERE tenant_id = :tenantId`, RLS on `incident_embeddings` is enabled+forced+correctly
fed, both independently sufficient.

Part 1 also fixed a latent bug found while implementing: `SlackInteractivityController`
never set `TenantContext` before touching tenant-scoped data, so the ack path 404'd in
production independent of the authorization gap. `TenantContext.set(...)`/`.clear()` around
the handler body is unchanged by Part 2 and still required — `timelineService.record(...)`
still writes a tenant-scoped, RLS-protected row.

---

## What changed in Part 2, and why

Part 1 left `ack_incident` executing (with an audit-trail timeline write) and only redirected
`resolve_incident`. On review, that distinction didn't hold: **acknowledging silences
escalation exactly like resolving does.** An unauthorized ack from a bare workspace member is
a suppressed page — the on-call engineer is never told, and everyone believes the incident is
handled. That's the same class of harm as an unauthorized resolve, not a lesser one. Part 2
closes that gap and also removes `/page` outright rather than gating it, per instruction.

### 1. `ack_incident` no longer executes either — both actions are now redirect-only

`src/main/java/com/aetherops/slack/SlackInteractivityController.java`: `ack_incident` and
`resolve_incident` are now one case. Neither calls `incidentService.update(...)` anymore (the
`IncidentService` dependency was removed from the controller entirely — nothing in this class
calls it now). Both:

- log the attempt (incident id, team id, Slack username + user id);
- record it to the incident timeline (see below);
- respond via `slackApiClient.respondToInteraction()` with a link to
  `{dashboardBaseUrl}/incidents/{incidentId}` — same deep link both actions already used,
  now shared by both.

This closes the authorization gap Part 1 left open: a bare Slack workspace member can no
longer change incident state through this endpoint at all, for either action, regardless of
role. The real state change only happens through the dashboard, where
`IncidentController.update()`'s `@PreAuthorize("hasAnyRole('OWNER','ADMIN'))")` already
enforces the correct check.

### 2. Every interaction attempt is now recorded to the timeline, including rejected ones

`IncidentTimeline.TimelineEventType.ACKNOWLEDGED` (Part 1, only ever written for the ack
path) is replaced with `SLACK_ACTION_REQUESTED` (`@Enumerated(EnumType.STRING)`, so still no
migration needed) — written for **both** ack and resolve attempts now, since neither mutates
state anymore and both deserve the same audit trail. `detail` records which action was
requested, the presented Slack identity (username + user id), and that it was redirected with
no state change. This is the accountability record the recon named as a gap; it is explicitly
*not* an authorization check — documented as such in the controller's inline comment, same as
Part 1.

Concretely this is a behavior change from Part 1: previously `resolve_incident` wrote **no**
timeline row (`verifyNoInteractions(timelineService)` in the old test). Now both actions do.

### 3. `/page` removed entirely — not gated behind a feature flag

Part 1's approach (a `FeatureFlag.SLACK_PAGE_COMMAND` toggle, default-enabled) is reverted.
Reasoning: `/page` triggers a **real SMS** to an on-call engineer's phone with no undo, from a
surface with no identity check at all (unlike ack/resolve, there's no REST-side role check to
defer to — no Slack-user-to-AetherOps-user mapping exists anywhere in this codebase). A
feature flag only bounds the blast radius for a tenant that remembers to flip it, and by
default leaves the command live for every workspace member of every other tenant. A
half-gated command is worse than an absent one, because it looks fixed.

**What `/page` did before this change (for confirmation nothing legitimate is lost):**
Any member of the connected Slack workspace could type `/page <service-id> [message]` and
trigger `OnCallService.pageOnCall(...)` — a real, synchronous SMS send via the configured
paging provider (Twilio) to whoever `OnCallRotationRepository` currently has scheduled for
that `service-id`. The command answered immediately with an ephemeral "Paging on-call
engineer..." acknowledgment, then `SlackPageResponseService` (an `@Async` bean, needed
because Slack's slash-command window is 3 seconds) posted the real outcome — paged
successfully, no rotation configured, no engineer available, or provider error — back to the
same channel via Slack's `response_url` once known. This was the *only* text-based way to
manually page on-call from Slack; `/oncall` (unchanged, read-only, still works) just looks up
and masks the on-call phone number without paging anyone.

After this change: `/page` still exists as a recognized command (so a workspace member typing
it gets a clear answer, not "Unrecognized command"), but always responds with an ephemeral
message pointing at the dashboard and never calls `OnCallService.pageOnCall(...)` — no SMS is
ever sent through this path anymore, regardless of tenant, workspace, or message text. The
Slack user id + username of every rejected attempt is logged (`log.warn`, not the timeline —
`/page` isn't incident-scoped, so there's no timeline row to attach it to) for audit
visibility.

**Nothing else lost:** `OnCallService.pageOnCall(...)` itself is untouched and still used by
the automatic paths (`IncidentPagingService`, `EscalationService`) — those aren't
Slack-triggered and aren't in scope for this finding. Only the manual, unauthenticated
Slack-triggered entry point is gone.

Removed as dead code (only caller was the `/page` case, which no longer calls it):
- `src/main/java/com/aetherops/slack/SlackPageResponseService.java`
- `src/test/java/com/aetherops/slack/SlackPageResponseServiceTest.java`
- `FeatureFlag.SLACK_PAGE_COMMAND` (`src/main/java/com/aetherops/config/FeatureFlag.java`) —
  no other reference anywhere in the codebase, confirmed by grep before removal.

### 4. Slack notification messages are untouched — buttons/deep-links preserved

Checked every place this codebase builds an outbound Slack incident message before touching
anything, specifically to confirm this change doesn't strip anything useful:

- `SlackNotifier.buildPayload()` (legacy incoming-webhook path, Phase 1 tenants) already sends
  exactly one interactive element: a "View Incident" **URL button** deep-linking to
  `{dashboardBaseUrl}/incidents/{id}` — i.e., it already opens the dashboard with the incident
  preselected. Not modified.
- `SlackConversationService.postIncidentAlert()` (bot-token path, Phase 2 tenants) sends
  plain text only — it never had any buttons, ack/resolve or otherwise. Not modified, and not
  a regression introduced here — this was already the state of that path before Part 1 or
  Part 2.
- **No code anywhere in this codebase builds a Block Kit button with `action_id`
  `ack_incident` or `resolve_incident`.** Those action ids are handled by
  `SlackInteractivityController` but nothing in this repo sends a message containing them —
  grepped the full source tree (main, test, resources) to confirm before relying on this.
  Whatever previously produced those buttons (a Slack app manifest shortcut, a client outside
  this repo, or a dropped feature) is outside this codebase's control; there's no button-
  emitting code here to update or strip.

Net effect: no Slack message loses any button or becomes "bare" as a result of this change,
because no message-building code was touched.

---

## Files changed

**Main:**
- `src/main/java/com/aetherops/slack/SlackInteractivityController.java` — `ack_incident` and
  `resolve_incident` merged into one redirect-only case; `IncidentService` dependency removed
  (no longer called anywhere in this class); both actions now write
  `SLACK_ACTION_REQUESTED` to the timeline.
- `src/main/java/com/aetherops/oncall/IncidentTimeline.java` — `TimelineEventType.ACKNOWLEDGED`
  replaced with `SLACK_ACTION_REQUESTED` (still `@Enumerated(EnumType.STRING)`, no migration).
- `src/main/java/com/aetherops/slack/SlackCommandController.java` — `/page` no longer pages;
  always responds with a dashboard redirect. `FeatureFlagService`/`SlackPageResponseService`
  dependencies removed; `dashboardBaseUrl` added (`@Value`, explicit constructor, same pattern
  as `SlackInteractivityController`); `user_id`/`user_name` request params added for audit
  logging on rejected attempts.
- `src/main/java/com/aetherops/config/FeatureFlag.java` — `SLACK_PAGE_COMMAND` removed
  (unused after the above).

**Main — deleted (dead code, no remaining callers):**
- `src/main/java/com/aetherops/slack/SlackPageResponseService.java`

**Test — changed:**
- `src/test/java/com/aetherops/slack/SlackInteractivityControllerTest.java` — constructor
  call updated (no `incidentService` mock); ack and resolve tests both now assert no state
  mutation, a `SLACK_ACTION_REQUESTED` timeline write with the Slack identity, and the
  dashboard-link response; forbidden-path tests assert `timelineService` sees no interactions
  instead of `incidentService`.
- `src/test/java/com/aetherops/slack/SlackCommandControllerTest.java` — constructor call
  updated (`dashboardBaseUrl` literal, no `FeatureFlagService`/`SlackPageResponseService`
  mocks); `/page` tests replaced with two asserting it always redirects and never pages,
  regardless of message text.

**Test — deleted (covered a now-deleted class):**
- `src/test/java/com/aetherops/slack/SlackPageResponseServiceTest.java`

No production code outside the four files above was touched.

---

## Verification

Docker unavailable locally (same as every prior step in this session) — Testcontainers-gated
integration tests can't run here.

- `mvn -o test-compile` — clean, full main + test tree, no errors.
- Targeted: `SlackInteractivityControllerTest` (7 tests) + `SlackCommandControllerTest`
  (8 tests) → **15 run, 0 failures, 0 errors.**
- Full Slack-package sweep (`-Dtest="com.aetherops.slack.**"`, 69 tests across 12 classes):
  **60 pass, 0 failures.** The only errors are `SlackAlertIntegrationTest`'s 9 tests, all
  failing with the identical, pre-existing `IllegalStateException: Could not find a valid
  Docker environment` (`Testcontainers`/`AbstractIntegrationTest`) — same signature every
  prior `STEP_REPORT.md` in this session has documented as the local-Docker-unavailability
  baseline, unrelated to this change.

Not verified in this session (needs Docker): any end-to-end HTTP-level check that a real
Slack interactivity or slash-command POST reaches this code with a real Spring context +
Postgres + RLS. Recommend running the Slack package alongside a Docker-backed integration
pass before this ships.

Nothing committed.

---

## Follow-ups (explicitly out of scope for this step — recorded, not built)

1. **Option C — real identity linking.** A `slackUserId` column on the AetherOps user record
   plus self-service Slack account linking (e.g. an OAuth-style "connect your Slack account"
   flow) is the correct long-term fix for this whole finding — it would let Slack actions
   defer to the same role checks the REST API already has, instead of being permanently
   redirect-only. Not built this step; this step is the safe stopgap until it exists.
2. **The timeline has no actor field on *any* path**, not just Slack's. `IncidentTimeline`
   has no user/actor column at all — the REST path (`IncidentController.update()` →
   `IncidentService`) writes timeline rows (e.g. `RESOLVED`) with no record of which
   authenticated user did it, same gap as the Slack path had before this step, just with a
   real role check attached. `SLACK_ACTION_REQUESTED`'s `detail` string is a workaround
   specific to the Slack path (no structured actor column exists to write to); it does not
   close this gap generally. This is a bigger, cross-cutting change (schema migration +
   touching every timeline-writing call site) and belongs in its own batch, not bundled into
   an authorization fix.
