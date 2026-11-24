# Gemini Live spike — 2026-11-22

Goal: before building hands-free Morphy, measure what Gemini Live actually feels like with Morphy-style tools.
Script: [`tools/live-spike/spike.mjs`](../tools/live-spike/spike.mjs) (Node ≥ 22, no dependencies).
Input: two utterances synthesised with Gemini TTS (voice "Puck"), resampled to 16 kHz mono, streamed in real time in
100 ms chunks. Tools: `set_alarm`, `get_notifications`, `get_calendar` with fake results.

```
GOOGLE_API_KEY=... node tools/live-spike/spike.mjs gemini-3.8-live <out-dir> u1.pcm u2.pcm
BARGE=1 ...   # second utterance starts 1.5 s after the model starts talking
```

## Results (`gemini-3.8-live`, Live API over WebSocket, from India on home Wi-Fi)

| | "Hey Morphy, set an alarm for six thirty tomorrow morning, and tell me if anything important came in." | "Bhai, kal ka plan kya hai? Mere calendar mein kuch hai kya?" |
|---|---|---|
| Heard (input transcription) | exact | exact, Hinglish kept in Latin script |
| Tool calls | `set_alarm(6:30)` + `get_notifications()` **in parallel** | `get_calendar("tomorrow")` |
| End of speech → tool call | **0.82 s** | **0.89 s** |
| End of speech → first audio | **1.68 s** | **1.42 s** |
| Reply | correct alarm + picked Rishi's exam question and the HDFC bill as "important" | correct, in Hinglish |

- Session setup: 0.7–1.2 s (do it when hands-free starts, not per question).
- **Barge-in:** the server sends `interrupted` while the user is still talking over the reply → playback can stop
  immediately. The utterance after a barge-in got no answer in this script (it had already sent `audioStreamEnd`
  once); a real mic stream never sends that, but this must be verified on device.
- **Language:** the first, English question was answered in Hinglish — the system prompt must say "match the
  language of *this* message", not "the user's language".
- **Usage** (2 turns, ~8 s of speech in, ~15 s of speech out): ~1.4 k prompt tokens (≈ 600 text: system + tools,
  ≈ 600 audio) + ~160 audio-out tokens + ~80–150 thinking tokens. Audio is ~25 tokens/s.

## Cost
Vertex AI lists Gemini 3.8 Live input audio at $3.00 / 1M tokens (≈ $0.0045 per minute of listening). The Gemini API
price for `gemini-3.8-live` and its audio-output rate were not found — check the pricing page before setting the
default monthly cap. Biggest lever regardless: don't stream the mic while nobody is talking (stop on silence).

## Verdict: go
Fast enough to feel live (~1.5 s to first word, tools under a second), understands Hinglish, calls tools in parallel.
Build `LiveSession` as planned, with: the language rule above, barge-in verified on device, cost meter from
`usageMetadata`, and **non-blocking tools** so long tasks (the operator) run while the conversation continues.
