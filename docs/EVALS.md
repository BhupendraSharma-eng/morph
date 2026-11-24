# Evals & measurements

Morphy decides things on your behalf: whether a message is an emergency, whether to wake you, when a task is finished. Those decisions are measured here. Rows marked **(scripted)** can be re-run with [`evals/decisions.py`](../evals/decisions.py). The others were measured on a real device (Motorola Edge 60 Fusion, Android 17) during development.

All test messages are fictional.

## 1. Emergency triage (scripted)

Every incoming chat message gets one decision call to `typesafe/jev-1.14` with five typed questions (`urgent`, `kind`, `urgent_ask`, `needs_reply`, `what`). The alarm fires when `urgent ≥ 0.5`.

| Set | Result |
|---|---|
| 5 real emergencies (accident, chest pain, fire, injury, being followed); Hinglish and English | **5/5 caught**, scores 0.74–0.98 |
| 11 non-emergencies, including traps: "URGENT: need the deck by 5", "URGENT! 60% off", bank OTP, "Bro reply fast!!!" | **0/11 false alarms**, all scores ≤ 0.05 |
| Message kind (emergency / request / question / plan / chit-chat / automated) | 14–15 of 16 match the hand label; the misses are borderline labels (e.g. "Bro reply fast!!!" as request vs chit-chat) |
| Latency | p50 ≈ 0.5–0.7 s per message |
| Cost | ≈ $0.00002 per message |

The **margin** matters as much as the accuracy: the lowest emergency scores 0.74 and the highest non-emergency 0.05, so the 0.5 threshold sits far from both groups.

Sample run (2026-11-21):

```
emergencies caught 5/5 · false alarms 0/11 · kind 14/16 · p50 709 ms · cost $0.00036
```

### Earlier comparison: LLM verdicts
Before decision models, triage was a fast-LLM prompt that returned a JSON verdict (GLM 5.3 Flash: 7/7 on a smaller set). It worked, but it was slower, cost more per message, and needed parsing that could break. Decision models replaced it on the hot path, and the LLM verdict remains the fallback for brains without a decision endpoint (`LlmDecisions`).

## 2. "Call me, it's urgent": screening (scripted)

If a person asks for you urgently (`urgent_ask ≥ 0.75`, which is a stricter bar than impatience), Morphy replies asking what it's about. The explanation then gets a second decision call:

* `interrupt ≥ 0.6` → full alarm
* `vague ≥ 0.6` → ask once more
* otherwise → Morphy handles it itself and leaves you a note

| Case type | Result |
|---|---|
| Real reasons (grandmother in hospital, stolen wallet, police stop, card declined before a flight) | 4/4 interrupt (0.84–0.98) |
| Routine reasons (Netflix password, charger model, share a doc, party invite) | 4/4 handled, no interrupt (≤ 0.05) |
| Still vague ("just call", "it's important, tell him to call me") | 2/2 flagged vague (0.95–0.97) |
| **Total** | **10/10** |

`urgent_ask` on genuine asks from family (Mom 0.86, Dad 0.83) vs a pushy work message (0.67) motivated the 0.75 threshold: 11/11 on that set.

## 3. For You: "is this for the owner?"

For each person's message, `for_owner ≥ 0.6` decides whether it becomes a For You item (a question or request that needs *you*, not a reply Morphy can handle). The result was **11/12**. The one false positive was a food-delivery promotion written in a chatty voice, and the same call now also asks whether the sender is a business, a bot or a system (`automated`), which excludes such messages.

## 4. Operator mode

| Metric | Result |
|---|---|
| Ending on the right screen (stay in the app to show a result vs return home after sending) | **10/10** real tasks with goal-based wording (7/8 with the first, step-based wording) |
| Step latency, default reasoning effort | ~4.5 s per step |
| Step latency, `effort=low` (escalates to the user's setting only when stuck or looping) | **~0.9 s per step** |
| Loop protection | Repeated screen+action pairs are detected and the agent is told to change approach. A 38-step loop seen in early testing no longer happens. |

The `ask_user` parser accepts all 4 shapes GLM produced in practice (chips, buttons, list options, plain options), which are covered by `AskUserShapesTest`.

## 5. Notification ranking

The glance on the home screen ranks the visible notifications with **one batched decision call**: 7 notifications in 917 ms for $0.000045. In the demo, an interview e-mail scores 0.82, a failed autopay 0.64 and "order delivered" 0.12.

## 6. Performance & battery

Measured with `dumpsys gfxinfo`, `dumpsys meminfo` and `top` on the device:

| Metric | Result |
|---|---|
| Background CPU (home hidden, listeners active) | ~0.6% of one core |
| Frames rendered while hidden | **0**. The agent orb used to animate forever; it now animates only while the agent is active. |
| Memory | 27 MB PSS |
| Cold start | ~745 ms |
| Build | Release, R8, full AOT (`cmd package compile -m speed`) |

## 7. Unit tests

`./gradlew :core:test` runs **42 JVM tests**: agent loop & tool rounds, surface parsing (including malformed model output), message codecs, priority lanes, auto-reply rules & rate limiting, trust boundary & `ask_user`, the operator loop (finishing, loops, confirm gates), and decision-model encoding and parsing.

## Methodology notes

* Thresholds are tied to a **pinned model version** (`jev-1.14`, not a floating alias), because calibration belongs to a specific model.
* The questions in `evals/decisions.py` are copied verbatim from `AutoReplyEngine`. If you change one, change the other.
* The sets are small and hand-built (tens of cases, not thousands). They work as regression gates and sanity checks, not as benchmark claims.
