"""Summarize redacted fixed-world GameTest metrics; never print fixture text or credentials.
Usage: python scripts/agent-live-report.py build/gui-qa-server/agent-live-world-results.json
The two modes use the current jar. They do not establish a before/after release baseline.
"""
import json
import math
import statistics
import sys
from pathlib import Path

data = json.loads(Path(sys.argv[1]).read_text(encoding="utf-8"))
groups = {}
for trial in data["trials"]:
    groups.setdefault((trial["provider"], trial["model"], trial["mode"]), []).append(trial)

def metric(values):
    values = sorted(values)
    return {"p50": statistics.median(values), "p95": values[max(0, math.ceil(len(values) * .95) - 1)]}

rows = []
for (provider, model, mode), trials in sorted(groups.items()):
    complete = [t for t in trials if t["completed"]]
    row = {"provider": provider, "model": model, "mode": mode, "samples": len(trials),
           "completed": len(complete), "completion_rate": len(complete) / len(trials)}
    if len(complete) == len(trials):
        row["wall_seconds"] = metric([t["wall_seconds"] for t in complete])
        row["model_requests"] = metric([t["model_requests"] for t in complete])
    else:
        row["performance_comparison"] = "Unavailable: correctness trials did not all complete."
    rows.append(row)
print(json.dumps({"scope": "Current-jar mode comparison. Small samples; no pre-change jar baseline, no processing-wait subtraction.",
                  "complete_report": data.get("finished", True), "groups": rows}, indent=2))
