"""Summarize opt-in AgentTelemetry log records; never prints source log lines.
Usage: python scripts/agent-metrics.py before.log after.log
Collect both logs using the same world, model, tasks and repeat count.
Stages may overlap: their durations must not be added as task wall time.
"""
import sys, re, json, statistics, math
from pathlib import Path
pattern = re.compile(r"AIFun agent stage=(\S+) duration_ms=(\d+) result_chars=(\d+)")
def read(path):
    rows={}
    for match in pattern.finditer(Path(path).read_text(encoding="utf-8",errors="replace")):
        stage,ms,chars=match.groups(); rows.setdefault(stage,[]).append((int(ms),int(chars)))
    result={}
    for stage,values in rows.items():
        times=sorted(v[0] for v in values)
        result[stage]={"samples":len(times),"p50_ms":statistics.median(times),"p95_ms":times[max(0,math.ceil(.95*len(times))-1)],"result_chars":sum(v[1] for v in values)}
    return result
if len(sys.argv)!=3: raise SystemExit("Usage: agent-metrics.py before.log after.log")
before,after=read(sys.argv[1]),read(sys.argv[2])
if not before or not after: raise SystemExit("No agent diagnostic records in one or both logs; no performance claim can be made.")
print(json.dumps({"before":before,"after":after,"note":"Per-stage measurements only; excludes no processing waits automatically and does not establish completion rate."},indent=2))
