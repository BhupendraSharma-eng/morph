#!/usr/bin/env python3
"""
Reproducible evals for Morph's decision-model calls.

The questions below are copied verbatim from the app (AutoReplyEngine.triage / judgeScreening,
NotificationRanker, ForYou.consider, OperatorTool end_on), and the thresholds are the ones the app ships with.
All messages are fictional.

Jev (hosted, OpenRouter's Decisions API):
    export OPENROUTER_API_KEY=sk-or-...
    python3 evals/decisions.py              # all suites
    python3 evals/decisions.py triage       # one suite

A local System One server (e.g. Kev via `llama serve`, POST /v1/systemone, no key):
    MORPH_DECISION_URL=http://localhost:8080 python3 evals/decisions.py rank for_you end_on

Standard library only. Cost for a full run is a fraction of a cent.
"""
import json, os, statistics, sys, time, urllib.request
from concurrent.futures import ThreadPoolExecutor

LOCAL = os.environ.get("MORPH_DECISION_URL", "").rstrip("/")
ENDPOINT = LOCAL + "/v1/systemone" if LOCAL else "https://openrouter.ai/api/alpha/decisions"
MODEL = os.environ.get("MORPH_DECISION_MODEL", "local" if LOCAL else "typesafe/jev-1.14")
URGENT, URGENT_ASK, INTERRUPT = 0.5, 0.75, 0.6   # app thresholds (AutoReplyEngine)


def decide(state, questions):
    key = os.environ.get("OPENROUTER_API_KEY")
    if not key and not LOCAL:
        sys.exit("Set OPENROUTER_API_KEY (or MORPH_DECISION_URL for a local server)")
    body = json.dumps({"model": MODEL, "state": state, "questions": questions}).encode()
    headers = {"Content-Type": "application/json"}
    if key and not LOCAL:
        headers["Authorization"] = f"Bearer {key}"
    req = urllib.request.Request(ENDPOINT, body, headers)
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
    with ThreadPoolExecutor(int(os.environ.get("EVAL_WORKERS", "8"))) as ex:
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
    with ThreadPoolExecutor(int(os.environ.get("EVAL_WORKERS", "8"))) as ex:
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


# --- Suite 3: notification ranking (NotificationRanker) ----------------------------------------------------
RANK_LEVELS = ["can be ignored", "worth a look today", "should see within the hour", "needs attention now"]
NEEDS_YOU = 0.5   # NotificationRanker.NEEDS_YOU, on score / 3
#        (app, title, text, needs_you)
RANK = [
    ("WhatsApp", "Mom", "Bank ke papers kahan rakhe hain? Abhi chahiye", True),
    ("Messages", "HDFC Bank", "Autopay of Rs 12,499 for your credit card will be debited tomorrow. Ensure sufficient balance.", True),
    ("WhatsApp", "Riya", "Are you coming to the station? Train leaves in 30 min", True),
    ("Gmail", "Dr. Mehta Clinic", "Your appointment today at 5:30 PM is confirmed. Reply C to cancel.", True),
    ("Slack", "Manager", "Can you join the client call in 10 minutes?", True),
    ("Amazon", "Out for delivery", "Your package will arrive today by 8 PM", False),
    ("Instagram", "instagram", "rahul_23 liked your story", False),
    ("Swiggy", "Hungry?", "Flat 50% off on your favourites, today only 🍕", False),
    ("YouTube", "New video", "AndroWide uploaded: This is OPPO K14 Plus Just After 15 Days", False),
    ("Google", "Weather", "Light rain expected this evening, 24°C", False),
    ("WhatsApp", "College Group", "Priya: 😂😂😂", False),
    ("Play Store", "Updates", "5 apps were updated", False),
]


def suite_rank():
    def run(c):
        app, title, text, _ = c
        # The app batches; one per call here keeps per-item results independent of batch order.
        state = {"notifications": [{"id": "n0", "app": app, "title": title, "text": text}]}
        q = {"n0": {"type": "score", "instructions": "Consider ONLY the notification with id n0 in the state. How soon should the phone's owner look at it?",
                    "criteria": RANK_LEVELS}}
        return decide(state, q)
    with ThreadPoolExecutor(int(os.environ.get("EVAL_WORKERS", "8"))) as ex:
        res = list(ex.map(run, RANK))
    ok, lat = 0, []
    for (app, title, text, want), (out, ms) in zip(RANK, res):
        f = out["answers"]["n0"]["score"] / 3
        good = (f >= NEEDS_YOU) == want
        ok += good; lat.append(ms)
        print(f"  {'✓' if good else '✗'} score={f:.2f} {title}: {text[:60]}")
    print(f"rank {ok}/{len(RANK)} · p50 {statistics.median(lat):.0f} ms")
    return ok == len(RANK)


# --- Suite 4: For you (ForYou.consider) ---------------------------------------------------------------------
FOR_OWNER = 0.6   # ForYou.FOR_OWNER
FY_Q = {
    "for_owner": noul(
        "Does the latest message need the phone owner's personal attention: a question only they can answer, a request "
        "or favour from them, a decision, or short information they should be told?",
        yes="asks the owner something, wants something from them, needs their decision, or tells them something they should know",
        no="greetings, reactions, small talk, 'ok'/'thanks', forwards and memes, automated or promotional messages"),
    "automated": noul("Is this sent by a business, a bot or a system rather than a person?"),
    "urgency": {"type": "score", "instructions": "Consider the latest message. How soon should the phone's owner look at it?",
                "criteria": ["can be ignored", "worth a look today", "should see within the hour", "needs attention now"]},
}
#        (app, from, recent_messages, needs_owner)
FY = [
    ("WhatsApp", "Rishi", "Rishi: Bhai kal ka exam kitne baje hai?", True),
    ("WhatsApp", "Mom", "Mom: Shaam ko sabzi le aana, aur doodh bhi", True),
    ("WhatsApp", "Ananya", "Ananya: Can you send me the notes from today's lecture?", True),
    ("Telegram", "Kabir", "Kabir: Should we book the 7pm or 9pm show? Tell me by 5", True),
    ("WhatsApp", "Papa", "Papa: Electricity bill aa gaya, 3200 ka hai, bhar dena", True),
    ("WhatsApp", "Priya", "Priya: Good morning 🌸", False),
    ("WhatsApp", "Rohan", "Me: Sent it\nRohan: ok thanks 👍", False),
    ("WhatsApp", "Family Group", "Chacha: *Forwarded* Drinking warm water every morning cures 100 diseases", False),
    ("WhatsApp", "Dev", "Dev: 😂😂", False),
    ("WhatsApp", "Zomato", "Zomato: Your order from Behrouz is on the way!", False),
    ("Messages", "AX-JIOINF", "Jio: 2GB/day data pack expires tomorrow. Recharge now for uninterrupted service.", False),
]


def suite_for_you():
    def run(c):
        app, who, thread, _ = c
        return decide({"app": app, "from": who, "recent_messages": thread}, FY_Q)
    with ThreadPoolExecutor(int(os.environ.get("EVAL_WORKERS", "8"))) as ex:
        res = list(ex.map(run, FY))
    ok, lat = 0, []
    for (app, who, thread, want), (out, ms) in zip(FY, res):
        a = out["answers"]
        fo, auto = a["for_owner"]["noul"], a["automated"]["noul"]
        shown = fo >= FOR_OWNER and auto < 0.6
        good = shown == want
        ok += good; lat.append(ms)
        print(f"  {'✓' if good else '✗'} for_owner={fo:.2f} automated={auto:.2f} urgency={a['urgency']['score']/3:.2f} {who}: {thread.splitlines()[-1][:55]}")
    print(f"for_you {ok}/{len(FY)} · p50 {statistics.median(lat):.0f} ms")
    return ok == len(FY)


# --- Suite 5: operator wrap-up (OperatorTool end_on) --------------------------------------------------------
END_Q = {"end_on": {"type": "choice",
    "instructions": "The task is finished. Judge by its GOAL, not the steps taken: where should the user be left now?",
    "criteria": {
        "home": "the goal was to get something DONE — a message was sent (even if a chat had to be opened to send it), "
                "a setting changed, someone added or invited, something counted or looked up",
        "app": "the goal was for the user to SEE or USE that screen — media playing, a page/article/product to read, "
               "a chat they asked to open without sending anything, a form left for them to finish"}}}
#        (task, outcome, tapped_send, expected)
END = [
    ("open the chat with Rishi and send hi", "Opened Rishi's chat and sent 'hi'", True, "home"),
    ("turn on dark mode in Instagram", "Switched Instagram theme to dark", False, "home"),
    ("add Ananya to the College group", "Added Ananya to College group", False, "home"),
    ("how many unread emails do I have in Gmail", "Counted 14 unread emails in the inbox", False, "home"),
    ("play Kesariya on YouTube Music", "Kesariya is playing on YouTube Music", False, "app"),
    ("open the Flipkart page for the boAt Airdopes 141", "Opened the product page for boAt Airdopes 141", False, "app"),
    ("open my chat with Mom", "Opened Mom's chat", False, "app"),
    ("fill the Intel bootcamp form with my name and email", "Filled name and email; left the remaining questions for you", False, "app"),
]


def suite_end_on():
    def run(c):
        task, outcome, sent, _ = c
        return decide({"task": task, "outcome": outcome, "tapped_send_or_post": sent}, END_Q)
    with ThreadPoolExecutor(int(os.environ.get("EVAL_WORKERS", "8"))) as ex:
        res = list(ex.map(run, END))
    ok, lat = 0, []
    for (task, _, _, want), (out, ms) in zip(END, res):
        a = out["answers"]["end_on"]
        good = a["choice"] == want
        ok += good; lat.append(ms)
        print(f"  {'✓' if good else '✗'} {a['choice']:<4} ({a.get('confidence', 0):.2f}) {task[:60]}")
    print(f"end_on {ok}/{len(END)} · p50 {statistics.median(lat):.0f} ms")
    return ok == len(END)


SUITES = {"triage": suite_triage, "screening": suite_screening, "rank": suite_rank, "for_you": suite_for_you, "end_on": suite_end_on}

if __name__ == "__main__":
    picked = sys.argv[1:] or list(SUITES)
    passed = True
    for name in picked:
        print(f"\n## {name} ({MODEL})")
        passed &= SUITES[name]()
    sys.exit(0 if passed else 1)
