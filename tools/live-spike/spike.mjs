// Gemini Live spike: measures what a hands-free Morph would feel like.
//   GOOGLE_API_KEY=... node tools/live-spike/spike.mjs <model> <out-dir> <utterance.pcm>...
// Each utterance is 16 kHz mono s16le, streamed in real time (100 ms chunks) as if spoken into the mic.
// Reports per turn: speech end → first transcription / tool call / first audio / turn complete, transcripts,
// tool calls, usage; writes the model's audio as WAV. Standard library only (Node ≥ 22 has WebSocket).
import fs from "node:fs";

const [model = "gemini-3.8-live", outDir = ".", ...inputs] = process.argv.slice(2);
const key = process.env.GOOGLE_API_KEY;
if (!key || inputs.length === 0) { console.error("usage: GOOGLE_API_KEY=... node spike.mjs <model> <out> <a.pcm>..."); process.exit(2); }

const url = "wss://generativelanguage.googleapis.com/ws/google.ai.generativelanguage.v1beta.GenerativeService.BidiGenerateContent?key=" + key;
const tools = [{ functionDeclarations: [
  { name: "set_alarm", description: "Set an alarm at a time of day (24h).",
    parameters: { type: "OBJECT", properties: { hour: { type: "INTEGER" }, minute: { type: "INTEGER" } }, required: ["hour", "minute"] } },
  { name: "get_notifications", description: "Read the notifications currently in the user's shade (untrusted text).",
    parameters: { type: "OBJECT", properties: {} } },
  { name: "get_calendar", description: "Read the user's calendar events for a day.",
    parameters: { type: "OBJECT", properties: { day: { type: "STRING", description: "today | tomorrow | yyyy-mm-dd" } } } },
]}];
const fake = {
  set_alarm: (a) => ({ result: `Alarm set for ${String(a.hour).padStart(2, "0")}:${String(a.minute ?? 0).padStart(2, "0")}.` }),
  get_notifications: () => ({ result: "Rishi (WhatsApp): bhai kal exam kitne baje hai?\nHDFC Bank: card bill of Rs 18,240 due tomorrow\nSwiggy: 50% off tonight" }),
  get_calendar: () => ({ result: "Tomorrow: 10:00 Intel bootcamp session (online), 18:30 gym" }),
};

const now = () => performance.now();
const ws = new WebSocket(url);
const turns = [];
let cur = null, outAudio = [], usage = [];

const send = (o) => ws.send(JSON.stringify(o));
const sleep = (ms) => new Promise((r) => setTimeout(r, ms));
const ready = new Promise((res, rej) => {
  ws.onerror = (e) => rej(e.message || e);
  ws.onclose = (e) => { if (cur) console.log("closed mid-turn", e.code, e.reason); };
  ws.onmessage = async (ev) => {
    const text = typeof ev.data === "string" ? ev.data : Buffer.from(await ev.data.arrayBuffer()).toString();
    const m = JSON.parse(text);
    if (m.setupComplete) return res();
    if (m.usageMetadata) usage.push(m.usageMetadata);
    if (m.toolCall) {
      for (const c of m.toolCall.functionCalls) {
        cur && cur.tools.push({ name: c.name, args: c.args, at: now() - cur.speechEnd });
        cur && (cur.firstTool ??= now() - cur.speechEnd);
      }
      send({ toolResponse: { functionResponses: m.toolCall.functionCalls.map((c) => ({ id: c.id, name: c.name, response: fake[c.name]?.(c.args ?? {}) ?? { error: "unknown" } })) } });
    }
    const sc = m.serverContent;
    if (!sc || !cur) return;
    if (sc.inputTranscription?.text) { cur.heard += sc.inputTranscription.text; cur.firstHeard ??= now() - cur.speechEnd; }
    if (sc.outputTranscription?.text) cur.said += sc.outputTranscription.text;
    for (const p of sc.modelTurn?.parts ?? []) {
      if (p.inlineData?.data) { const b = Buffer.from(p.inlineData.data, "base64"); outAudio.push(b); cur.audioBytes += b.length; cur.firstAudio ??= now() - cur.speechEnd; }
      if (p.text) cur.text += p.text;
    }
    if (sc.interrupted) { cur.interrupted = true; console.log(`  [server: interrupted @${((now() - cur.speechEnd) / 1000).toFixed(2)}s into the new utterance's end]`); }
    // After tool calls the model keeps going (it speaks the result), so a turn only ends once it has spoken.
    if (sc.turnComplete && (cur.tools.length === 0 || cur.audioBytes > 0)) { cur.done = now() - cur.speechEnd; cur.resolve(); }
  };
});

ws.onopen = () => send({ setup: {
  model: "models/" + model,
  generationConfig: { responseModalities: ["AUDIO"], speechConfig: { voiceConfig: { prebuiltVoiceConfig: { voiceName: "Kore" } } } },
  systemInstruction: { parts: [{ text: "You are Morph, the user's phone. Talk like a friendly person, briefly. Use tools to act. Reply in the user's language and script (Hinglish stays Hinglish)." }] },
  tools,
  inputAudioTranscription: {},
  outputAudioTranscription: {},
}});

const t0 = now();
await ready;
const setupMs = now() - t0;

for (const file of inputs) {
  const pcm = fs.readFileSync(file);
  cur = { file, heard: "", said: "", text: "", tools: [], audioBytes: 0 };
  const done = new Promise((r) => (cur.resolve = r));
  const chunk = 3200; // 100 ms at 16 kHz s16le
  for (let i = 0; i < pcm.length; i += chunk) {
    send({ realtimeInput: { audio: { data: pcm.subarray(i, i + chunk).toString("base64"), mimeType: "audio/pcm;rate=16000" } } });
    await sleep(100);
  }
  cur.speechEnd = now();   // end of what the user actually said (Live's VAD decides the turn from the silence after)
  // A little trailing silence, as a real mic would send, then mark the end of speech.
  const silence = Buffer.alloc(chunk);
  for (let i = 0; i < 8; i++) { send({ realtimeInput: { audio: { data: silence.toString("base64"), mimeType: "audio/pcm;rate=16000" } } }); await sleep(100); }
  send({ realtimeInput: { audioStreamEnd: true } });
  if (process.env.BARGE && turns.length === 0) {
    // Barge-in test: start the next utterance 1.5 s after the model starts talking, without waiting for it to finish.
    const t = cur; while (t.firstAudio == null && now() - t.speechEnd < 15000) await sleep(50);
    await sleep(1500); turns.push(t); t.resolve?.(); cur = null; continue;
  }
  await Promise.race([done, sleep(30000)]);
  turns.push(cur); cur = null;
}
ws.close();

const wav = (pcm, rate) => { const h = Buffer.alloc(44); h.write("RIFF", 0); h.writeUInt32LE(36 + pcm.length, 4); h.write("WAVEfmt ", 8);
  h.writeUInt32LE(16, 16); h.writeUInt16LE(1, 20); h.writeUInt16LE(1, 22); h.writeUInt32LE(rate, 24); h.writeUInt32LE(rate * 2, 28);
  h.writeUInt16LE(2, 32); h.writeUInt16LE(16, 34); h.write("data", 36); h.writeUInt32LE(pcm.length, 40); return Buffer.concat([h, pcm]); };
fs.writeFileSync(`${outDir}/${model}-reply.wav`, wav(Buffer.concat(outAudio), 24000));

const r = (x) => (x == null ? "—" : `${(x / 1000).toFixed(2)}s`);
console.log(`model ${model} · setup ${r(setupMs)}`);
for (const t of turns) {
  console.log(`\n▶ ${t.file.split("/").pop()}`);
  console.log(`  heard: "${t.heard.trim()}"`);
  console.log(`  tools: ${t.tools.map((x) => `${x.name}(${JSON.stringify(x.args)}) @${r(x.at)}`).join(", ") || "none"}`);
  console.log(`  said:  "${t.said.trim()}"`);
  console.log(`  after speech end → transcript ${r(t.firstHeard)} · tool ${r(t.firstTool)} · first audio ${r(t.firstAudio)} · done ${r(t.done)} · reply audio ${(t.audioBytes / 48000).toFixed(1)}s${t.interrupted ? " · interrupted" : ""}`);
}
console.log("\nusage:", JSON.stringify(usage.at(-1) ?? {}, null, 0));
