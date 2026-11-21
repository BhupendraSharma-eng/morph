#!/usr/bin/env python3
"""
Reproducible evals for Morph's decision-model calls (typesafe/jev-1.14 via OpenRouter's Decisions API).

The questions below are copied verbatim from the app (AutoReplyEngine.triage / judgeScreening), and the
thresholds are the ones the app ships with. All messages are fictional.

    export OPENROUTER_API_KEY=sk-or-...
    python3 evals/decisions.py              # all suites
    python3 evals/decisions.py triage       # one suite

Standard library only. Cost for a full run is a fraction of a cent.
"""
import json, os, statistics, sys, time, urllib.request
from concurrent.futures import ThreadPoolExecutor

ENDPOINT = "https://openrouter.ai/api/alpha/decisions"
MODEL = os.environ.get("MORPH_DECISION_MODEL", "typesafe/jev-1.14")
URGENT, URGENT_ASK, INTERRUPT = 0.5, 0.75, 0.6   # app thresholds (AutoReplyEngine)


def decide(state, questions):
    key = os.environ.get("OPENROUTER_API_KEY")
    if not key:
        sys.exit("Set OPENROUTER_API_KEY")
    body = json.dumps({"model": MODEL, "state": state, "questions": questions}).encode()
    req = urllib.request.Request(ENDPOINT, body, {"Authorization": f"Bearer {key}", "Content-Type": "application/json"})
    t = time.perf_counter()
    with urllib.request.urlopen(req, timeout=30) as r:
        out = json.load(r)
    return out, (time.perf_counter() - t) * 1000


def noul(instructions, yes=None, no=None):
    q = {"type": "noul", "instructions": instructions}
    if yes and no:
        q["criteria"] = {"true": yes, "false": no}
    return q


# --- Suite 1: emergency triage (incoming chat messages) ---------------------------------------------------
TRIAGE_Q = {
    "urgent": noul(
        "Is someone in danger or in a crisis right now (medical emergency, accident, injury, fire, safety threat) so that "
        "the phone's owner must drop everything and respond immediately? Impatience, work urgency and marketing words "
        "like URGENT do not count.",
        yes="a real emergency involving a person's safety or health",
        no="anything else, including impatient or pushy messages and promotions"),
    "kind": {"type": "choice", "instructions": "What kind of message is this?", "criteria": {
        "emergency": "someone's safety or health is at risk right now",
        "request": "asks the owner to do something or send something",
        "question": "asks the owner a question or where they are",
        "plan": "making or confirming plans",
        "chit_chat": "greetings and casual conversation",
        "automated": "sent by a business or system: OTP, bank alert, promotion, delivery update"}},
}
#           (from, app, message, is_emergency, expected_kind)
TRIAGE = [
    ("Dad", "WhatsApp", "Accident ho gaya highway pe, main theek hoon par gaadi phas gayi, call kar", True, "emergency"),
    ("Mom", "WhatsApp", "Papa ko chest pain ho raha hai, hospital ja rahe hain, jaldi aa", True, "emergency"),
    ("Neha", "WhatsApp", "There's smoke coming from the flat next door, I'm scared, call me", True, "emergency"),
    ("Arjun", "Telegram", "I fell off the bike and my arm is bleeding badly, can you come", True, "emergency"),
    ("Didi", "WhatsApp", "Someone is following me home, stay on call with me please", True, "emergency"),
    ("Rahul", "Slack", "URGENT: need the deck by 5, where are you??", False, "request"),
    ("Swiggy", "WhatsApp", "URGENT! 60% off ends in 10 minutes 🍕", False, "automated"),
    ("HDFC Bank", "Messages", "Your OTP for login is 482910. Do not share it.", False, "automated"),
    ("Ananya", "Instagram", "Cycling tomorrow 6am?", False, "plan"),
    ("Mom", "WhatsApp", "Khana kha liya?", False, "question"),
    ("Rohan", "WhatsApp", "Bro reply fast!!!", False, "chit_chat"),
    ("Vikram", "WhatsApp", "Send me the Netflix password", False, "request"),
    ("Priya", "WhatsApp", "Good morning ☀️", False, "chit_chat"),
    ("Amazon", "Messages", "Your package will be delivered today", False, "automated"),
    ("Karan", "Slack", "Can we move the sync to 4pm?", False, "question"),
    ("Aunt", "WhatsApp", "Where are you? Call when free", False, "question"),
]


def suite_triage():
    def run(c):
        who, app, msg, _, _ = c
        return decide({"app": app, "from": who, "message": msg}, TRIAGE_Q)
    with ThreadPoolExecutor(8) as ex:
        res = list(ex.map(run, TRIAGE))
    tp = fp = fn = kind_ok = 0
    lat, cost = [], 0.0
    for (who, _, msg, emerg, kind), (out, ms) in zip(TRIAGE, res):
        a = out["answers"]
        p = a["urgent"]["noul"]
        k = a["kind"]["choice"]
        hit = p >= URGENT
        tp += hit and emerg; fp += hit and not emerg; fn += (not hit) and emerg
        kind_ok += k == kind
        lat.append(ms); cost += (out.get("usage") or {}).get("cost") or 0
        flag = "✓" if hit == emerg else "✗"
        print(f"  {flag} urgent={p:.2f} kind={k:<10} {who}: {msg[:60]}")
    print(f"emergencies caught {tp}/{tp+fn} · false alarms {fp}/{len(TRIAGE)-tp-fn} · kind {kind_ok}/{len(TRIAGE)}"
          f" · p50 {statistics.median(lat):.0f} ms · cost ${cost:.5f}")
    return fn == 0 and fp == 0


# --- Suite 2: screening "call me urgently" requests --------------------------------------------------------
SCREEN_Q = {
    "interrupt": noul(
        "The sender said it was urgent and was asked what it's about. Judging by their explanation, should the phone's owner be interrupted RIGHT NOW?",
        yes="health, safety, accident, police or legal trouble, money lost or at risk right now, a family crisis, or something time-critical that cannot wait an hour",
        no="it can wait a few hours: routine requests, errands, plans, documents, questions, chit-chat, curiosity"),
    "vague": noul("Is the sender still NOT saying what the matter is (e.g. only 'just call', 'it's urgent', 'tell him to call')?"),
}
ASK = "Morph: He's busy right now. What's it about? If it's urgent I'll get him immediately."
#          (from, first message, explanation, should_interrupt, should_be_vague)
SCREEN = [
    ("Mom", "Call me urgently", "Nani ko hospital le ja rahe hain, BP bahut low hai", True, False),
    ("Kabir", "Bro urgent call kar", "My wallet and phone got stolen, I'm stuck at the station with no money", True, False),
    ("Papa", "Call me, urgent", "Police ne gaadi rok li hai, documents tere paas hain", True, False),
    ("Riya", "Need you urgently!!", "Flight leaves in 40 min and the booking shows your card was declined", True, False),
    ("Dev", "urgent call me", "just call", False, True),
    ("Rohan", "Urgent!!", "Need your Netflix password lol", False, False),
    ("Sana", "Call urgently", "Which charger did you buy last month? Want the same one", False, False),
    ("Manager", "Urgent - call", "Can you share the Q3 doc when you're back?", False, False),
    ("Aman", "urgent", "Party tonight at 9, coming?", False, False),
    ("Tanya", "Please call asap", "It's important, tell him to call me", False, True),
]


def suite_screening():
    def run(c):
        who, first, expl, _, _ = c
        thread = f"{who}: {first}\n{ASK}\n{who}: {expl}"
        return decide({"from": who, "thread": thread}, SCREEN_Q)
    with ThreadPoolExecutor(8) as ex:
        res = list(ex.map(run, SCREEN))
    ok = 0
    for (who, _, expl, want_i, want_v), (out, _) in zip(SCREEN, res):
        i = out["answers"]["interrupt"]["noul"]; v = out["answers"]["vague"]["noul"]
        # The app interrupts on a good reason, asks once more when vague, otherwise handles it itself.
        good = (i >= INTERRUPT) == want_i and (not want_v or v >= 0.6)
        ok += good
        print(f"  {'✓' if good else '✗'} interrupt={i:.2f} vague={v:.2f} {who}: {expl[:60]}")
    print(f"screening {ok}/{len(SCREEN)}")
    return ok == len(SCREEN)


SUITES = {"triage": suite_triage, "screening": suite_screening}

if __name__ == "__main__":
    picked = sys.argv[1:] or list(SUITES)
    passed = True
    for name in picked:
        print(f"\n## {name} ({MODEL})")
        passed &= SUITES[name]()
    sys.exit(0 if passed else 1)
