"""Audit actual Flash/native provider usage and authoritative mutations from isolated live trials."""
import argparse
import importlib.util
import json
import re
from collections import Counter, defaultdict
from pathlib import Path


def report(directory, log):
    directory, log = Path(directory), Path(log)
    spec = importlib.util.spec_from_file_location("allowed_report", Path(__file__).with_name("allowed-tools-live-report.py"))
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    saved = module.archives(directory / "stage-decision-live-archive.dat")
    usage, timings = defaultdict(dict), defaultdict(dict)
    for line in log.read_text(encoding="utf-8", errors="replace").splitlines():
        marker = "AIFun usage " if "AIFun usage " in line else "AIFun trace "
        if marker not in line:
            continue
        try:
            event = json.loads(line.split(marker, 1)[1])
            task = event["context"].get("task_id")
            if marker == "AIFun usage ":
                usage[task][event["model_span_id"]] = event["usage"]
            elif event.get("event") == "finish" and event.get("stage", "").startswith("decision_request:"):
                timings[task][event["span_id"]] = event["duration_ns"] / 1e6
        except (ValueError, KeyError):
            continue
    trials = []
    for row in json.loads((directory / "stage-decision-live-results.json").read_text(encoding="utf-8")):
        task = row["task_id"]
        records = saved[task]["records"]
        mutations = [json.loads(r["summary"]) for r in records if r["kind"] == "world_mutation"]
        automatic, automatic_call = [], False
        for record in records:
            if record["kind"] == "tool_call":
                automatic_call = record.get("callId", "").startswith("decision_")
            if automatic_call and record["kind"] == "world_mutation":
                automatic.append(json.loads(record["summary"]))
        actual = Counter()
        received = Counter()
        order_ok = True
        for fact in mutations:
            if not fact.get("external_transfer"):
                continue
            item, inward, count = fact["moved_item"], fact["to_maid"], fact["moved_count"]
            actual[(tuple(fact["target_position"]), item, inward)] += count
            if item == "aifun_gui_test:machine":
                order_ok &= received["minecraft:oak_planks"] == 8 and received["minecraft:iron_ingot"] == 4
                if not inward:
                    order_ok &= received[item] == 8
            if inward:
                received[item] += count
        expected = Counter({(tuple(t["position"]), t["item"], t["to_maid"]): t["count"] for t in row["expected_gross_transfers"]})
        checks = dict(row["checks"])
        checks.update(gross_quantities_no_replayed_mutations=actual == expected, actual_mutations_follow_phase_order=order_ok)
        events = list(usage[task].values())
        totals = {key: sum(u[key] for u in events) if events and all(u.get(key) is not None for u in events) else None
                  for key in ("input_tokens", "output_tokens", "total_tokens", "cached_input_tokens", "uncached_input_tokens")}
        responses = [r["summary"] for r in records if r["kind"] == "decision_response"]
        native_tokens = [tuple(map(int, m.groups())) for s in responses if (m := re.search(r"input=(\d+); output=(\d+)", s))]
        trial = {key: row[key] for key in ("mode", "task_id", "model", "status", "wall_seconds", "model_requests")}
        trial.update(checks=checks, completed=row["completed"] and all(checks.values()), flash_usage=totals,
                     flash_usage_complete=len(events) == row["model_requests"],
                     native_responses=len(responses), native_request_ms=list(timings[task].values()),
                     native_input_tokens=sum(p[0] for p in native_tokens), native_output_tokens=sum(p[1] for p in native_tokens),
                     installed_segments=[r["summary"] for r in records if r["kind"] == "decision_stage_installed"],
                     answers=[r["summary"] for r in records if r["kind"] == "decision_stage_answer"],
                     fallbacks=[r["summary"] for r in records if r["kind"] == "decision_fallback"])
        trial["automatic_committed_mutations"] = automatic
        trial["native_request_attempts"] = len(timings[task])
        trial["native_usage_complete"] = len(native_tokens) == len(timings[task])
        # Stale/cancelled native replies do not retain usage today. Never count missing usage as zero.
        trial["combined_reported_total_tokens"] = totals["total_tokens"] + sum(sum(p) for p in native_tokens) if totals["total_tokens"] is not None and trial["native_usage_complete"] else None
        trials.append(trial)
    return {"scope": "Real configured Flash and native decision APIs, isolated Forge inventories; startup templates disabled. Timings include owner amendment and deliberate 4000-token QA history compaction; production budgets remain 475000. Native cache usage is not reported and is not counted as zero.", "trials": trials}


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("directory")
    parser.add_argument("log")
    parser.add_argument("--output", required=True)
    args = parser.parse_args()
    result = report(args.directory, args.log)
    Path(args.output).write_text(json.dumps(result, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print(json.dumps([{k: r[k] for k in ("mode", "completed", "model_requests", "wall_seconds", "native_responses", "combined_reported_total_tokens")} for r in result["trials"]]))
