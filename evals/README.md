# Evals

Morphy's System-1 decisions (emergency triage, urgent-request screening) are typed calls to a decision model,
so they can be measured like classifiers. `decisions.py` sends the **exact questions the app ships with** and
checks them against the **app's thresholds**, using fictional messages.

```bash
export OPENROUTER_API_KEY=sk-or-...
python3 evals/decisions.py            # triage + screening
python3 evals/decisions.py triage     # one suite
MORPHY_DECISION_MODEL=other/model python3 evals/decisions.py   # compare a model
```

It needs only the standard library. A full run costs well under a cent. The script exits non-zero on any missed emergency, false alarm or wrong screening call, so it can gate CI.

Results and methodology are in [../docs/EVALS.md](../docs/EVALS.md).
