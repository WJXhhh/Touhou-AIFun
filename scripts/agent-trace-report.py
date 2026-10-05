"""Read metadata-only AIFun spans. Usage: python scripts/agent-trace-report.py logfile [--timeline]
Overlapping stage totals are never added to task wall time. No source lines/payloads are printed.
"""
import argparse
import json
import math
import statistics
from collections import defaultdict
from pathlib import Path


def distribution(values):
    values = sorted(values)
    return {"samples": len(values), "p50_ms": statistics.median(values),
            "p95_ms": values[max(0, math.ceil(.95 * len(values)) - 1)], "total_ms": sum(values)}


def occupied(intervals):
    end = total = 0
    for start, stop in sorted(intervals):
        total += max(0, stop - max(start, end))
        end = max(end, stop)
    return total


def report(path, timeline=False):
    spans, malformed = {}, 0
    # Trace JSON is ASCII metadata; UTF-8 replacement also tolerates GBK log prefixes.
    with Path(path).open(encoding="utf-8", errors="replace") as stream:
        for line in stream:
            if "AIFun trace " not in line:
                continue
            try:
                event = json.loads(line.split("AIFun trace ", 1)[1])
                if event["version"] != 1:
                    continue
                key = (event["run_id"], event["span_id"])
                row = spans.setdefault(key, {"events": {}})
                row["events"][event["event"]] = event
            except (KeyError, ValueError, TypeError):
                malformed += 1
    groups = defaultdict(list)
    for row in spans.values():
        event = row["events"].get("start") or row["events"].get("finish")
        if not event:
            continue
        ctx = event["context"]
        groups[(event["run_id"], ctx.get("task_id") or ctx["trace_id"], ctx["generation"], ctx["purpose"])].append(row)
    results = []
    for (run, trace, generation, purpose), rows in groups.items():
        stages, phases = defaultdict(list), defaultdict(list)
        intervals, unfinished, model_requests = [], [], 0
        for row in rows:
            events = row["events"]
            start = events.get("start") or events.get("finish")
            finish = events.get("finish")
            if start["stage"] == "model_request":
                model_requests += 1
                for name in ["response_headers", "first_output", "response_body"]:
                    if name in events:
                        phases[name].append(events[name]["duration_ns"] / 1e6)
            if not finish:
                unfinished.append({"span_id": start["span_id"], "stage": start["stage"]})
                continue
            duration = finish["duration_ns"] / 1e6
            stages[(finish["stage"], finish["status"])].append(duration)
            if finish["stage"] == "model_request" or finish["stage"].startswith("tool_total:"):
                intervals.append((finish["start_ns"], finish["start_ns"] + finish["duration_ns"]))
        result = {"run_id": run, "task_or_chat_id": trace, "generation": generation, "purpose": purpose,
                  "model_requests": model_requests, "unfinished_spans": unfinished,
                  "model_phases": {k: distribution(v) for k, v in phases.items()},
                  "model_and_tool_occupied_ms": occupied(intervals) / 1e6,
                  "stages": [{"stage": k[0], "status": k[1], **distribution(v)} for k, v in stages.items()]}
        if timeline:
            result["timeline"] = sorted([{"span_id": e["span_id"], "parent_span_id": e.get("parent_span_id"),
                "call_id": e["context"].get("call_id"), "stage": e["stage"], "start_ms": e["start_ns"] / 1e6,
                "duration_ms": e["duration_ns"] / 1e6, "status": e["status"], "result_chars": e["result_chars"]}
                for r in rows if (e := r["events"].get("finish"))], key=lambda e: e["start_ms"])
        results.append(result)
    return {"note": "Stage totals overlap. Use task_execution for wall time; model_http_ready is not full generation time. Small samples do not establish a performance improvement.",
            "malformed_records": malformed, "groups": results}


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("logfile")
    parser.add_argument("--timeline", action="store_true")
    args = parser.parse_args()
    result = report(args.logfile, args.timeline)
    if not result["groups"]:
        raise SystemExit("No AIFun trace records. Enable agentRuntimeV1 diagnostics and use the new build.")
    print(json.dumps(result, indent=2))
