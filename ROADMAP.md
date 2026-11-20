# Morph → V1 Nightly

**V1 =** Morph is my only home screen for 2 straight weeks without wanting to switch back.

## Decisions (2026-11-21)
- **Audience:** just me. No multi-user onboarding, no Play Store, no third-party crash service.
- **Privacy:** notification contents may go to the cloud model (triage, briefs, auto-reply).
- **Cost:** credits are plentiful, but nothing wasteful: cheap model for triage, smart model for conversation,
  local fast paths before any model call, no polling loops that call the model.
- **Repo:** standalone `morph` repo; T-UI (`AI-Launcher`) is frozen as reference.
- **Shipping:** CI/CD nightlies skipped for now — local release builds over adb. CI still runs on push.
- **No root:** the phone stays stock (bootloader not unlockable, UPI/banking). Deep control comes from an
  AccessibilityService (+ optional Shizuku) instead — see Operator mode.

## Exit criteria
| Bar | Target |
|---|---|
| Stability | 0 crashes/ANRs for 7 days; state survives reboot/process death; basics work offline |
| Agent | ≥90% on ~50 golden tasks; first streamed token < 1.5 s median |
| Proactivity | ≥3 proactive moments I'd miss if removed |
| Trust | Every automatic action logged + undoable; injection defenses; one-tap kill switch |

## Plan
### Week 1 — Nightly 0
- [x] Own repo, CI on every push, nightly workflow (03:00 IST, skips when unchanged, date-based versionCode)
- [x] Real release key (outside repo + GitHub secrets), CI verifies the signing certificate
- [x] Persistence: canvas, conversation history (agent resumes the thread after restart)
- [x] Local crash reporter (files + "I crashed last time" card)
- [~] ~~Obtainium on the phone tracking GitHub releases~~ (skipped with CI/CD)
- [x] Action ledger (persist auto-reply log; every automatic action, when and why)

### Week 2 — Daily driver
- [x] Universal instant search in the bar: apps, contacts, settings, app shortcuts, web, all local before any AI
- [x] Launcher essentials: swipe-down shade, long-press (shortcuts / uninstall / hide), work profile, portrait lock, offline mode
- [x] `AgentRuntime`: app-scoped runtime with a priority queue (me > proactive > background)
- [x] Conversational first run: name → brain → permissions at the moment they're needed

### Done along the way
- [x] Agent researches the web itself: search_news, web_search (cited), read_webpage; untrusted-content marking
- [x] Glance notification rows open their target (player, chat, post) like the shade does

### Week 3 — Smarter agent
- [ ] Model tiers: fast/cheap (triage, verdicts) vs smart (conversation), per-role config
- [ ] Trust boundary: third-party content tagged untrusted; Act/Handoff tools after untrusted reads need a confirm card
- [ ] Memory v2 (facts + learned routines, editable); tools: calendar read, now-playing, real web results, weather, location
- [ ] Live surfaces (timer, now playing with controls, battery bound to device state) + `update_ui(id)`; token/cost meter in a dev panel
- [ ] **Interactive surfaces — the agent asks with UI, not just text.** When the agent needs input it composes
  the controls for it, and the turn waits for your answer:
  - New input blocks: text field (single/multi-line), slider (min/max/step/unit), toggle/checkbox,
    single choice (segmented button group / radio), multi choice (checkbox chips), date & time pickers, stepper, rating
  - `ask_user(surface)` tool: renders a form card and **suspends the turn** until you submit, then returns
    structured values (`{"budget": 15000, "dates": [...], "vegetarian": true}`) as the tool result.
    Dismiss/timeout returns "user declined". Client-side validation (required, ranges); drafts survive leaving home.
  - Editable confirms: before sending a message or creating a rule, the agent shows the draft in an editable field
    — you tweak it and tap Send (this *is* the trust-boundary confirm card, generalized)
  - Foundation for: clarifying questions in Operator mode, quick check-ins in proactive moments,
    rule/settings editors the agent builds on the fly

### Proposed: Operator mode — the agent uses apps like you do (pending go-ahead)
- [ ] AccessibilityService: read any app's UI tree; tap / type / scroll / swipe / back / home; screenshots for a vision model when the tree isn't enough
- [ ] Observe → decide → act loop with step budget; visible overlay with live step + big Stop button
- [ ] Interactive confirm before anything final (pay, send, buy, delete, book, unsubscribe)
- [ ] Learned task recipes (successful routes replayed faster/cheaper next time)
- [ ] Optional Shizuku for shell-level actions (toggles, permissions, force-stop) without root

### Week 4 — Proactive v1
- [ ] Signals (notifications, time, calendar, charging, headphones, geofence, app usage) → triage (rules + fast model, budgeted) → Moments
- [ ] Morning brief · message triage · event prep
- [ ] Spaces: pin any surface to home · quiet hours · interruption budget · learn from dismissals

### Week 5 — Polish & evals
- [ ] `:evals` golden tasks in CI (mocked tools, real model) with pass rate in release notes
- [ ] Emergency-detection eval set (precision/recall gate)
- [ ] Baseline profile + Macrobenchmark (cold start < 500 ms, no jank streaming), screenshot tests for surfaces
- [ ] Ledger UI, kill-switch Quick Settings tile, export/delete data → tag **v1.0-nightly**
