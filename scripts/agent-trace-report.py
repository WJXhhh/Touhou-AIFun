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
    task_models = defaultdict(list)
    for row in spans.values():
        finish = row["events"].get("finish")
        if finish and finish["stage"] == "model_request" and finish["context"].get("task_id"):
            ctx = finish["context"]
            task_models[(finish["run_id"], ctx["task_id"], ctx["generation"])].append(
                (finish["start_ns"], finish["start_ns"] + finish["duration_ns"], ctx["purpose"]))
    results = []
    for (run, trace, generation, purpose), rows in groups.items():
        stages, phases = defaultdict(list), defaultdict(list)
        intervals, model_intervals, tool_intervals, walls, unfinished, model_requests = [], [], [], [], [], 0
        decision_intervals, decision_requests, decision_fallbacks, deterministic_steps = [], 0, defaultdict(int), 0
        for row in rows:
            events = row["events"]
            start = events.get("start") or events.get("finish")
            finish = events.get("finish")
            if start["stage"] == "model_request":
                model_requests += 1
                for name in ["response_headers", "first_output", "response_body"]:
                    if name in events:
                        phases[name].append(events[name]["duration_ns"] / 1e6)
            if start["stage"].startswith("decision_request:"):
                decision_requests += 1
            if not finish:
                unfinished.append({"span_id": start["span_id"], "stage": start["stage"]})
                continue
            duration = finish["duration_ns"] / 1e6
            stages[(finish["stage"], finish["status"])].append(duration)
            if finish["stage"].startswith("decision_request:"):
                interval = (finish["start_ns"], finish["start_ns"] + finish["duration_ns"])
                decision_intervals.append(interval)
                intervals.append(interval)
            if finish["stage"].startswith("decision_fallback:"):
                decision_fallbacks[finish["stage"].split(":", 1)[1]] += 1
            if finish["stage"].startswith("decision_dispatch:deterministic_"):
                deterministic_steps += 1
            if finish["stage"] == "model_request" or finish["stage"].startswith("tool_total:"):
                interval = (finish["start_ns"], finish["start_ns"] + finish["duration_ns"])
                intervals.append(interval)
                (model_intervals if finish["stage"] == "model_request" else tool_intervals).append(interval)
            if finish["stage"] == "task_execution":
                walls.append((finish["start_ns"], finish["start_ns"] + finish["duration_ns"]))
        result = {"run_id": run, "task_or_chat_id": trace, "generation": generation, "purpose": purpose,
                  "model_requests": model_requests, "unfinished_spans": unfinished,
                  "decision_requests": decision_requests, "decision_fallbacks": dict(decision_fallbacks),
                  "deterministic_steps": deterministic_steps,
                  "model_phases": {k: distribution(v) for k, v in phases.items()},
                  "model_and_tool_occupied_ms": occupied(intervals) / 1e6,
                  "stages": [{"stage": k[0], "status": k[1], **distribution(v)} for k, v in stages.items()]}
        if walls:
            def within_task(items):
                return occupied([(max(start, ws), min(stop, we)) for start, stop in items
                                 for ws, we in walls if max(start, ws) < min(stop, we)]) / 1e6
            wall = occupied(walls) / 1e6
            related = task_models.get((run, trace, generation), []) if purpose == "TASK_EXECUTION" else []
            auxiliary = [(start, stop) for start, stop, kind in related if kind != "TASK_EXECUTION"]
            model = within_task(model_intervals + auxiliary)
            result["latency_breakdown"] = {"task_wall_ms": wall, "model_occupied_ms": model,
                "decision_occupied_ms": within_task(decision_intervals),
                "primary_model_occupied_ms": within_task(model_intervals), "auxiliary_model_occupied_ms": within_task(auxiliary),
                "model_share": model / wall if wall else None, "tool_occupied_ms": within_task(tool_intervals),
                "task_queue_wait_ms": sum(v for (stage, _), values in stages.items() if stage == "task_queue_wait" for v in values),
                "notification_wait_ms": sum(v for (stage, _), values in stages.items() if stage == "notification_wait" for v in values)}
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
