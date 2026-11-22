# Morph → V1 Nightly

**V1 =** Morph is my only home screen for 2 straight weeks without wanting to switch back.

## Decisions (2026-11-21)
- **Audience:** just me. No multi-user onboarding, no Play Store, no third-party crash service.
- **Privacy:** notification contents may go to the cloud model (triage, briefs, auto-reply).
- **Cost:** credits are plentiful, but nothing wasteful: cheap model for triage, smart model for conversation,
  local fast paths before any model call, no polling loops that call the model.
- **Repo:** standalone `morph` repo; T-UI (`AI-Launcher`) is frozen as reference.
- **Shipping:** CI/CD nightlies skipped for now — local release builds over adb. CI still runs on push.
- **Autonomy over guardrails (2026-11-21):** default *Autonomous* — long tasks end to end, no confirmations except
  spending money; passwords never typed; everything in the Activity log; guardrails return via the Autonomy dial.
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
- [x] Release signing from a keystore outside the repo (env-configured); no key material in git
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
- [x] **For You:** what people need from you, summarised, with suggested replies and agent-drawn inputs; instant replies via the notification
- [x] **Urgent-request screening:** "call me, urgent" gets asked for a reason; you're interrupted only if it holds up
- [x] Wallpaper-aware light/dark theme; compact For You strip during conversations
- [x] Demo mode with fictional data for screenshots and presentations
- [x] Reproducible decision-model evals (`evals/decisions.py`, see docs/EVALS.md)
- [x] Agent researches the web itself: search_news, web_search (cited), read_webpage; untrusted-content marking
- [x] Digital assistant: Morph holds the assistant role; floating screen-aware panel over any app, same AgentRuntime
- [x] Glance notification rows open their target (player, chat, post) like the shade does

### Decision models (System One) — 2026-11-21
- [x] TypeSafe Jev 1.14 via OpenRouter `/api/alpha/decisions` (Gemini Decision 1 403s for this account; Luna not on OpenRouter)
- [x] Typed `DecisionModel` API in core (yesNo / pick / rate) + LLM fallback for other brains
- [x] Auto-reply triage decision-first (5/5 emergencies, 0/11 false alarms; automated messages skip the LLM)
- [x] Notification importance ranking on the glance (one batched call)
- [x] Operator end-on via Jev (10/10 on real tasks)
- [ ] Next candidates: injection check on untrusted content, proactive moment triage (Week 4), fast operator steps for obvious taps

### Week 3 — Smarter agent
- [x] Model tiers: fast/cheap (triage, verdicts) vs smart (conversation), per-role config
- [x] Trust boundary: third-party content tagged untrusted; Act/Handoff tools after untrusted reads need a confirm card
- [ ] Memory v2 (facts + learned routines, editable); tools: calendar read, now-playing, real web results, weather, location
- [ ] Live surfaces (timer, now playing with controls, battery bound to device state) + `update_ui(id)`; token/cost meter in a dev panel
- [x] **Interactive surfaces — the agent asks with UI, not just text.** When the agent needs input it composes
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

### Operator mode — the agent uses apps like you do
- [x] AccessibilityService: read any app's UI tree; tap / type / scroll / swipe / back / home
- [ ] Screenshots for a vision model when the tree isn't enough (canvas UIs, images, games)
- [x] Observe → decide → act loop with step budget; visible overlay with live step + big Stop button
- [x] Interactive confirm before anything final (pay, send, buy, delete, book, unsubscribe) — enforced in code, not just the prompt; never types passwords
- [x] Long tasks: autonomy-scaled budgets (up to 40 agent rounds / 100 operator steps), notes scratchpad, wait, stuck-screen detection
- [ ] Learned task recipes (successful routes replayed faster/cheaper next time)
- [ ] Optional Shizuku for shell-level actions (toggles, permissions, force-stop) without root

### Week 4 — Proactive v1
- [ ] Signals (notifications, time, calendar, charging, headphones, geofence, app usage) → triage (rules + fast model, budgeted) → Moments
- [ ] Morning brief · message triage · event prep
- [ ] Quiet hours · interruption budget · learn from dismissals (pinning surfaces moved to V1.1 → Widgets page)

### Week 5 — Polish & evals
- [ ] `:evals` golden tasks in CI (mocked tools, real model) with pass rate in release notes
- [x] Emergency-detection + screening eval sets (`evals/decisions.py`, exits non-zero on any miss)
- [ ] Wire the eval script into CI as a gate
- [ ] Baseline profile + Macrobenchmark (cold start < 500 ms, no jank streaming), screenshot tests for surfaces
- [ ] Ledger UI, kill-switch Quick Settings tile, export/delete data → tag **v1.0-nightly**

## V1.1 — Three pages (after the V1 two-week stability bar)

Home stays the default page. Swipe **left** for conversations and **right** for widgets. Android's own
swipe-up Home gesture always slides back to home (Morph already resets on Home).

Gesture rules carried over from V1:
- Page swipes start on the clock, empty space or the input bar. Sideways swipes on a card still dismiss the card.
- Swipes starting at the very screen edge stay Android's back gesture, so page swipes start a little inward.
- A scrollable conversation keeps its vertical swipes. Swipe up from the input bar for apps, down from the clock for the shade.

### 1. Conversations page (swipe left), first
- [x] Conversation sessions: each has its own agent history and canvas. A new one starts after a quiet gap (30 min); ✕ archives instead of discarding.
- [x] Auto-titles from the fast model (a few words, in the conversation's language)
- [x] List grouped by day, newest first, with the last line as a preview; the open conversation shown as "Now"
- [x] Local full-text search over titles and messages (on-device, no model call)
- [x] Tap to resume: restores the canvas and the agent's thread exactly where it left off
- [x] Long-press → Delete (a sideways swipe on a row would fight the page swipe). Memory v2 builds on these sessions.

### 2. Spaces (swipe right): what Morph keeps track of for you
Rethought after real use: frozen pinned cards went stale and their controls were dead. Spaces is a living board.

| Section | Source | Stays fresh because |
|---|---|---|
| **Active** | Running auto-replies, conversations working in the background | Derived live; **Turn off** really turns it off; gone when it ends |
| **Waiting on you** | For You (people who need something from you) | Clears when you reply, open, swipe away or read the chat in the app, and after 24 h |
| **Watching** | `keep_track` watch: a question Morph re-checks on a schedule | Background agent check; alerts only on a real change |
| **Saved** | `keep_track` saved: links/references, optional deadline | Opens what it points to; countdown to the deadline |
| **Widgets** | Android widgets | 4-column grid at each widget's designed size |

- [x] Live sections derived (never stored, never stale)
- [x] `keep_track` tool + "Keep track of this" on any card (the fast model classifies it into watch/saved)
- [x] Watches re-checked every 15 min when due + on resume; CHANGED/SAME protocol; notification opens Spaces
- [x] No dead controls: kept items only carry wired buttons (Open, Check now, Ask, Turn off)
- [x] Widgets on a grid (target cells, or derived from min size); Edit = compact list to reorder/remove
- [x] "Needs you" resolves when handled outside Morph (notification removal reasons, startup reconciliation, 24 h expiry)
- [ ] Auto-reply "for N minutes" should expire, not recur daily in the same window (Phase C below)
- [ ] Watch history (a small sparkline for prices)

## V1.2 — Focus & voice (plan fused 2026-11-22)
Order: Later shelf → learned shelving → stability checkpoint → voice in phases. Each phase ends with on-device checks.

### Home focus
- [x] Collapse what can wait (one slim row per app / chat), ✕ to shelve, playful but light dismiss animation
- [x] Contextual, usage-learned suggestion chips; learned app suggestions; whole unread burst per chat
- [x] **Phase A — Later shelf** (Spaces → Later): move-not-clear, AI digest, auto-return on new message, 48 h expiry,
  quiet evening reminder, `later_items` tool
- [ ] **Phase B — learned shelving by content** (topic keys like `sender|repo`, suggest → rule, auto-shelve, undo)
- [ ] **Phase C — stability checkpoint**: expiring auto-reply windows, AI-switch Quick Settings tile, evals gate in CI,
  Compose API deprecations (BasicTextField state, Slider state, sheet state)

### Voice (Gemini)
- [x] **Voice 1 — Morph speaks**: Gemini streaming TTS (on-device fallback), spoken-style answers, 🔊 on every card
- [x] **Live spike** (docs/LIVE_SPIKE.md): ~0.85 s to tool call, ~1.5 s to first audio, Hinglish exact → go
- [x] **Hands-free** (hold the orb): Gemini Live with the same tools, captions, barge-in, resumption, mic service
- [x] Sees the screen (`look_at_screen`, screenshot frames) and drives apps itself (`screen` tool) — no hand-off
- [x] Voice approvals (`confirm_action`), "bye" ends it, cost meter + monthly cap
- [ ] Verify on device: echo on loudspeaker, the question after a barge-in, long sessions past the server cap
- [ ] Headset button / assistant gesture to start; continuous mode polish; voice for the assistant overlay

