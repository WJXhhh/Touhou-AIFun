"""Combine opt-in live GameTest checks with actual, numeric provider usage.

Usage: python scripts/allowed-tools-live-report.py RESULTS LOG --output REPORT [--before PREVIOUS_REPORT]
Never prints raw log lines, requests, credentials or model reasoning.
"""
import argparse
import json
import gzip
import io
import struct
import re
from collections import Counter, defaultdict
from pathlib import Path


def archives(path):
    """Read only the four tag types used by the versioned QA archive envelope."""
    stream = io.BytesIO(gzip.decompress(Path(path).read_bytes()))
    def number(fmt):
        return struct.unpack(fmt, stream.read(struct.calcsize(fmt)))[0]
    def name():
        return stream.read(number(">H")).decode("utf-8")
    def value(tag):
        if tag == 3:
            return number(">i")
        if tag == 7:
            size = number(">i")
            if not 0 <= size <= 8 * 1024 * 1024:
                raise ValueError("Invalid QA archive size")
            return stream.read(size)
        if tag == 9:
            kind, size = number(">B"), number(">i")
            if not 0 <= size <= 64:
                raise ValueError("Invalid QA archive task count")
            return [value(kind) for _ in range(size)]
        if tag == 10:
            out = {}
            while True:
                kind = number(">B")
                if kind == 0:
                    return out
                key = name()
                out[key] = value(kind)
        raise ValueError("Unexpected QA archive envelope tag")
    if number(">B") != 10:
        raise ValueError("Invalid QA archive root")
    name()
    root = value(10)
    return {task["taskId"]: task for task in (json.loads(row["data"]) for row in root["tasks"])}


def report(results, log, archive_path=None):
    rows = json.loads(Path(results).read_text(encoding="utf-8"))
    saved = {}
    for path in ([archive_path] if isinstance(archive_path, (str, Path)) else archive_path or []):
        saved.update(archives(path))
    usage = defaultdict(list)
    requests = Counter()
    seen_usage, seen_requests = set(), set()
    for line in Path(log).read_text(encoding="utf-8", errors="replace").splitlines():
        marker = "AIFun usage " if "AIFun usage " in line else "AIFun trace "
        if marker not in line:
            continue
        try:
            event = json.loads(line.split(marker, 1)[1])
            task = event["context"].get("task_id")
            if marker == "AIFun usage ":
                key = (task, event.get("model_span_id"))
                if key in seen_usage:
                    continue
                seen_usage.add(key)
                usage[task].append(event)
            elif event["event"] == "start" and event["stage"] == "model_request":
                key = (event["run_id"], event["span_id"])
                if key not in seen_requests:
                    seen_requests.add(key)
                    requests[task] += 1
        except (ValueError, KeyError, TypeError):
            continue
    output = {"scope": "One paired real API trial; actual server inventory and mutation checks; not a statistical benchmark.",
              "compression": "475000 input budget retained; completed real history deliberately archived once at a 6000-token QA projection target.",
              "trials": []}
    for row in rows:
        task = row["task_id"]
        events = usage[task]
        counters = {}
        for field in ("input_tokens", "output_tokens", "total_tokens", "cached_input_tokens",
                      "uncached_input_tokens", "cache_write_input_tokens", "reasoning_tokens"):
            values = [e["usage"].get(field) for e in events]
            counters[field] = sum(values) if values and all(v is not None for v in values) else None
        plans = []
        source_plans = row["model_plans"]
        archived = saved.get(task)
        if archived:
            archived.setdefault("results", {}).setdefault("values", {})
            # The live observer preserves already observed raw evidence before bounded-store eviction.
            archived["results"]["values"] = {**row.get("raw_evidence", {}), **archived["results"]["values"]}
            raw = archived["results"]["values"]
            source_plans = [json.loads(raw[r["argumentsRef"]]) for r in archived["records"]
                            if r["kind"] == "tool_call" and r.get("tool") == "update_task_plan" and r.get("argumentsRef") in raw]
        for plan in source_plans:
            if isinstance(plan, dict):
                plans.append({k: plan[k] for k in ("steps", "current_step", "allowed_tools", "complete") if k in plan})
        trial = {k: row[k] for k in ("mode", "task_id", "model", "input_budget", "status", "completed",
                                     "model_requests", "wall_seconds", "checks", "selections", "failures",
                                     "compression_before_messages", "compression_after_messages")}
        trial["scenario"] = row.get("scenario", "workshop")
        trial.update({"actual_provider_usage": counters, "usage_events": len(events),
                      "all_model_requests_including_auxiliary": requests[task],
                      "usage_complete": bool(events) and len(events) == requests[task] and all(
                          counters[field] is not None for field in ("input_tokens", "output_tokens", "total_tokens", "uncached_input_tokens")),
                      "automatic_model_plans": plans, "tool_call_counts": dict(Counter(row["tools"]))})
        menus = [re.fullmatch(r"stage=(\w+); schemas=(\d+); schema_budget=(\d+)", s) for s in row["selections"]]
        menus = [m for m in menus if m]
        if menus:
            trial["mean_schema_count"] = round(sum(int(m[2]) for m in menus) / len(menus), 2)
            trial["mean_estimated_schema_reserve"] = round(sum(int(m[3]) for m in menus) / len(menus), 2)
        trial["plans_with_automatic_allowlist"] = sum(bool(p.get("allowed_tools")) for p in plans)
        if archived:
            raw = archived["results"]["values"]
            if "expected_gross_transfers" in row:
                registered = [json.loads(raw[r["resultRef"]]) for r in archived["records"]
                              if r["kind"] == "completion_defined" and r.get("resultRef") in raw]
                contract = row.get("final_completion") or (registered[-1] if registered else {})
                phases = contract.get("phases", []) if contract.get("kind") == "sequence" else []
                held, delivery = [], []
                for i, phase in enumerate(phases):
                    leaves = phase.get("conditions", []) if phase.get("kind") == "all" else [phase]
                    for leaf in leaves:
                        if leaf.get("item") == "aifun_gui_test:machine":
                            if leaf.get("kind") == "inventory" and leaf.get("count") == 24:
                                held.append(i)
                            if leaf.get("kind") == "transfer" and leaf.get("to_maid") is False:
                                delivery.append(i)
                verified = row.get("final_completion_stage", archived.get("verifiedCompletionStage"))
                trial["registered_milestone_audit"] = {
                    "phase_count": len(phases), "held_all_products_phases": held,
                    "delivery_phases": sorted(set(delivery)), "server_verified_phase": verified,
                    "required_milestones_verified": bool(held and delivery) and 0 < max(held) < min(delivery)
                    and verified == len(phases) - 1,
                    "note": "Additional final cleanup phases are valid; original fixture pass/fail is retained separately."}
            timeline = []
            for record in archived["records"]:
                if record["kind"] == "tool_selection" and record.get("resultRef") in raw:
                    timeline.append({"request": len(timeline) + 1, **json.loads(raw[record["resultRef"]])})
            trial["actual_selection_timeline"] = timeline
            wire = [json.loads(raw[r["resultRef"]]) for r in archived["records"]
                    if r["kind"] == "wire_prefix" and r.get("resultRef") in raw]
            trial["wire_prefix_telemetry_complete"] = bool(wire) and len(wire) == len(events) == row["model_requests"]
            trial["wire_schema_changes"] = sum(w.get("same_tools_as_previous") is False for w in wire)
            trial["wire_system_changes"] = sum(w.get("same_system_as_previous") is False for w in wire)
            trial["wire_prefix_timeline"] = [{"request": i + 1, **w,
                                               **({"actual_provider_usage": events[i]["usage"]} if len(wire) == len(events) else {})}
                                              for i, w in enumerate(wire)]
            if trial["wire_prefix_telemetry_complete"]:
                buckets = defaultdict(list)
                for w, event in zip(wire, events):
                    bucket = ("initial" if "same_tools_as_previous" not in w else "system_changed"
                              if not w["same_system_as_previous"] else "schemas_unchanged" if w["same_tools_as_previous"] else "schemas_changed")
                    buckets[bucket].append(event["usage"])
                trial["cache_usage_by_wire_change"] = {
                    key: {"requests": len(values), **{
                        field: sum(v[field] for v in values) if all(v.get(field) is not None for v in values) else None
                        for field in ("input_tokens", "cached_input_tokens", "uncached_input_tokens")}}
                    for key, values in buckets.items()}
            if len(wire) == len(timeline):
                trial["schema_changes_within_stable_owner_phase_and_step"] = sum(
                    wire[i].get("same_tools_as_previous") is False
                    for i in range(1,len(wire)) if all(timeline[i][k] == timeline[i-1][k]
                                                     for k in ("goal_version","completion_stage","current_step")))
                stable_active = [i for i in range(1,len(wire))
                                 if timeline[i].get("allowed_tools") and timeline[i-1].get("allowed_tools")
                                 and set(timeline[i]["allowed_tools"]) == set(timeline[i-1]["allowed_tools"])
                                 and all(timeline[i][k] == timeline[i-1][k] for k in ("goal_version","completion_stage","current_step"))]
                trial["stable_active_selection_transitions"] = len(stable_active)
                trial["active_schema_changes_without_selection_or_scope_change"] = sum(
                    wire[i].get("same_tools_as_previous") is False for i in stable_active)
            trial["requests_with_active_allowlist"] = sum(bool(m.get("allowed_tools")) for m in timeline)
            trial["selection_sources"] = dict(Counter(m.get("selection_source", "model_hint") for m in timeline if m.get("allowed_tools")))
            streak = longest = 0
            previous_scope = None
            for menu in timeline:
                scope = (menu["goal_version"], menu["completion_stage"], menu["current_step"], tuple(sorted(menu.get("allowed_tools") or [])))
                streak = (streak + 1 if scope == previous_scope else 1) if menu.get("allowed_tools") else 0
                longest = max(longest, streak)
                previous_scope = scope
            trial["longest_stable_active_selection_requests"] = longest
            trial["selection_telemetry_complete"] = len(timeline) == row["model_requests"]
            trial["active_allowlists_by_verified_phase"] = {
                str(phase): [list(mask) for mask in sorted({tuple(sorted(m["allowed_tools"])) for m in timeline
                                                          if m["completion_stage"] == phase and m.get("allowed_tools")})]
                for phase in sorted({m["completion_stage"] for m in timeline})}
            trial["phase_or_owner_transitions"] = sum(
                a["completion_stage"] != b["completion_stage"] or a["goal_version"] != b["goal_version"]
                for a, b in zip(timeline, timeline[1:]))
            trial["transitions_with_unrestricted_next_request"] = sum(
                not b.get("allowed_tools") for a, b in zip(timeline, timeline[1:])
                if a["completion_stage"] != b["completion_stage"] or a["goal_version"] != b["goal_version"])
            scoped = [m for m in timeline if m.get("allowed_tools")]
            if scoped and all("allowed_goal_version" in m for m in scoped):
                trial["checks"]["active_allowlists_bound_to_current_owner_phase_and_step"] = all(
                    m["goal_version"] == m["allowed_goal_version"] and m["completion_stage"] == m["allowed_completion_stage"]
                    and m["current_step"] == m["allowed_current_step"] for m in scoped)
            trial["compression_events"] = [json.loads(r["summary"]) for r in archived["records"] if r["kind"] == "qa_compression"]
            groups, current_group = [], []
            for record in archived["records"]:
                if record["kind"] == "model_request":
                    if current_group:
                        groups.append(current_group)
                    current_group = []
                elif record["kind"] == "tool_call":
                    current_group.append(record)
            if current_group:
                groups.append(current_group)
            trial["transfer_and_next_open_in_same_request"] = sum(
                any(c.get("tool") == "gui_batch" for c in group) and any(c.get("tool") == "open_gui" for c in group)
                for group in groups)
            trial["close_only_requests"] = sum(all(c.get("tool") == "close_gui" for c in group) for group in groups)
            trial["schema_load_only_requests"] = sum(all(c.get("tool") == "load_tool_schema" for c in group) for group in groups)
            trial["planning_only_requests"] = sum(all(call.get("tool") == "update_task_plan" for call in group) for group in groups)
            trial["metadata_only_planning_requests"] = sum(
                all(call.get("tool") == "update_task_plan" and call.get("argumentsRef") in archived["results"]["values"] and "completion" not in json.loads(
                    archived["results"]["values"].get(call.get("argumentsRef"), "{}")) for call in group) for group in groups)
            trial["planning_details_complete"] = all(call.get("argumentsRef") in archived["results"]["values"]
                                                     for group in groups for call in group if call.get("tool") == "update_task_plan")
            trial["planning_with_action_requests"] = sum(any(call.get("tool") == "update_task_plan" for call in group)
                                                       and any(call.get("tool") in ("open_gui", "gui_action", "gui_batch") for call in group) for group in groups)
            hint_calls = []
            for record in archived["records"]:
                if record["kind"] == "tool_call" and record.get("tool") in ("open_gui", "gui_action", "gui_batch"):
                    arguments = archived["results"]["values"].get(record.get("argumentsRef"))
                    args = json.loads(arguments) if arguments else {}
                    hints = {k: args[k] for k in ("next_step", "next_allowed_tools") if k in args}
                    if hints:
                        hint_calls.append({"call_id": record.get("callId"), "tool": record["tool"], **hints})
            trial["inline_hint_calls"] = hint_calls
            trial["inline_hints_applied"] = 0
            trial["inline_hints_ignored"] = []
            for record in archived["records"]:
                if record["kind"] == "tool_result" and record.get("tool") in ("open_gui", "gui_action", "gui_batch"):
                    raw_result = archived["results"]["values"].get(record.get("resultRef"))
                    if raw_result:
                        try:
                            parsed_result = json.loads(raw_result)
                            trial["inline_hints_applied"] += parsed_result.get("task_hint_applied") is True
                            if parsed_result.get("task_hint_ignored"):
                                trial["inline_hints_ignored"].append({"call_id": record.get("callId"), "reason": parsed_result["task_hint_ignored"]})
                        except (ValueError, AttributeError):
                            pass
            mutations = [json.loads(r["summary"]) for r in archived["records"] if r["kind"] == "world_mutation"]
            transfers = defaultdict(int)
            for fact in mutations:
                if fact.get("external_transfer"):
                    key = (tuple(fact["target_position"]), fact["moved_item"], fact["to_maid"])
                    transfers[key] += fact["moved_count"]
            trial["gross_external_transfers"] = [{"position": p, "item": item, "to_maid": receive, "count": count}
                                                  for (p, item, receive), count in transfers.items()]
            actual = Counter()
            for (_, item, receive), count in transfers.items():
                actual[(item, receive)] += count
            # Workbench outputs are also authoritative external receipts; count them separately from delivery.
            expected = Counter({("minecraft:oak_planks", True): 6, ("minecraft:iron_ingot", True): 3,
                                ("minecraft:redstone", True): 1, ("aifun_gui_test:machine", True): 6,
                                ("aifun_gui_test:machine", False): 6, ("minecraft:redstone", False): 1})
            if "expected_gross_transfers" in row:
                expected_by_position = Counter({(tuple(t["position"]), t["item"], t["to_maid"]): t["count"]
                                                for t in row["expected_gross_transfers"]})
                trial["checks"]["gross_quantities_no_replayed_mutations"] = Counter(transfers) == expected_by_position
                # Verify actual event order independently of model-written plans or phase labels.
                received = Counter()
                ordered = True
                for fact in mutations:
                    if not fact.get("external_transfer"):
                        continue
                    item, inward, count = fact["moved_item"], fact["to_maid"], fact["moved_count"]
                    if item == "aifun_gui_test:machine":
                        ordered &= received["minecraft:oak_planks"] == 24 and received["minecraft:iron_ingot"] == 12
                        if not inward:
                            ordered &= received[item] == 24
                    if inward:
                        received[item] += count
                trial["checks"]["actual_mutations_follow_phase_order"] = ordered
            else:
                trial["checks"]["gross_quantities_no_replayed_mutations"] = actual == expected
            initial, final = row["receipts_at_compression"], row["final_receipts"]
            trial["checks"]["compressed_receipts_survive_until_finish"] = bool(initial) and all(
                k in final and (final[k] >= v if v > 0 else final[k] <= v) for k, v in initial.items())
            trial["mutations_after_compression"] = sum(abs(v) for v in final.values()) > sum(abs(v) for v in initial.values())
            destinations = [p for p, item, receive in transfers if item == "aifun_gui_test:machine" and not receive]
            if row.get("forbidden_position") or len(destinations) == 1:
                target = destinations[0] if destinations else None
                forbidden = row.get("forbidden_position") or [target[0] + 4, target[1], target[2]]
                openings = [r for r in archived["records"] if r["kind"] == "tool_result" and r.get("tool") == "open_gui"]
                complete_openings, opened_forbidden = True, False
                for opening in openings:
                    text = archived["results"]["values"].get(opening.get("resultRef"))
                    if text is None:
                        complete_openings = False
                    else:
                        try:
                            opened_forbidden |= json.loads(text).get("target_position") == forbidden
                        except (ValueError, AttributeError):
                            # A generation guard records a plain-text result before opening anything.
                            if not (text.startswith(("goal_updated_before_dispatch:", "tool_not_in_allowed_tools:"))
                                    and "no action started" in text):
                                complete_openings = False
                trial["checks"]["no_forbidden_chest_opened"] = complete_openings and not opened_forbidden
            trial["completed"] = trial["completed"] and all(trial["checks"].values())
            trial["mutation_receipts"] = mutations
            loads = []
            results_by_call = {r.get("callId"): r for r in archived["records"] if r["kind"] == "tool_result"}
            for call in archived["records"]:
                if call["kind"] == "tool_call" and call.get("tool") == "load_tool_schema":
                    arguments = archived["results"]["values"].get(call.get("argumentsRef"))
                    result = results_by_call.get(call.get("callId"), {})
                    loads.append({"arguments": json.loads(arguments) if arguments else None,
                                  "result_summary": result.get("summary")})
            trial["schema_load_attempts"] = loads
            trial["tool_results_with_errors"] = [{"tool": r.get("tool"), "summary": r["summary"]}
                                                 for r in archived["records"] if r["kind"] == "tool_result"
                                                 and any(s in r["summary"] for s in ("error", "invalid_", "failed", "exceeds_remaining", "not_in_allowed"))]
        if counters["input_tokens"] is not None and events:
            trial["mean_input_tokens_per_request"] = round(counters["input_tokens"] / len(events), 2)
        output["trials"].append(trial)
    if len(output["trials"]) == 2:
        arms = {t["mode"]: t for t in output["trials"]}
        if set(arms) != {"baseline", "automatic_allowed_tools"}:
            raise ValueError("A/B requires exactly one baseline and one automatic arm")
        baseline, allowed = arms["baseline"], arms["automatic_allowed_tools"]
        if baseline["model"] != allowed["model"] or baseline["input_budget"] != allowed["input_budget"]:
            raise ValueError("A/B must use the same model and input budget; mixed-model savings are invalid")
        comparison = {"both_completed": baseline["completed"] and allowed["completed"],
                      "model_round_delta": allowed["model_requests"] - baseline["model_requests"],
                      "usage_complete": baseline["usage_complete"] and allowed["usage_complete"]}
        if not comparison["both_completed"] or not comparison["usage_complete"]:
            comparison["performance_comparison_valid"] = False
            comparison["reason"] = "Incomplete correctness or provider usage; partial task costs are not savings."
            output["comparison"] = comparison
            return output
        comparison["performance_comparison_valid"] = True
        for field in ("input_tokens", "output_tokens", "total_tokens", "uncached_input_tokens"):
            a, b = baseline["actual_provider_usage"][field], allowed["actual_provider_usage"][field]
            if a is not None and b is not None:
                comparison[field + "_saved"] = a - b
                comparison[field + "_reduction_percent"] = round(100 * (a - b) / a, 2) if a else None
        for field in ("mean_schema_count", "mean_estimated_schema_reserve", "mean_input_tokens_per_request"):
            a, b = baseline.get(field), allowed.get(field)
            if a:
                comparison[field + "_reduction_percent"] = round(100 * (a - b) / a, 2)
        output["comparison"] = comparison
    return output


def compare_automatic_before(previous, current):
    """Compare completed automatic arms; keep this separate from the current paired A/B."""
    old = [t for t in previous["trials"] if t["mode"] == "automatic_allowed_tools"]
    new = [t for t in current["trials"] if t["mode"] == "automatic_allowed_tools"]
    if len(old) != 1 or len(new) != 1:
        raise ValueError("Before/after comparison requires exactly one automatic arm in each report")
    a, b = old[0], new[0]
    if a["model"] != b["model"] or a["input_budget"] != b["input_budget"]:
        raise ValueError("Before/after comparisons require the same model and input budget")
    complete = all(t["completed"] and t["usage_complete"] and all(t["checks"].values()) for t in (a, b))
    numeric = all(t["actual_provider_usage"].get(k) is not None for t in (a, b)
                  for k in ("input_tokens", "output_tokens", "total_tokens", "uncached_input_tokens"))
    result = {"performance_comparison_valid": complete and numeric,
              "scope": "Historical automatic arm versus current automatic arm, not a controlled cache or statistical comparison.",
              "before_task_id": a["task_id"], "after_task_id": b["task_id"]}
    if not result["performance_comparison_valid"]:
        result["reason"] = "Incomplete correctness or provider usage; partial task costs are not savings."
        return result
    for key in ("input_tokens", "output_tokens", "total_tokens", "uncached_input_tokens"):
        before, after = a["actual_provider_usage"][key], b["actual_provider_usage"][key]
        result[key] = {"before": before, "after": after,
                       "reduction_percent": round(100 * (before - after) / before, 2) if before else None}
    for key in ("all_model_requests_including_auxiliary", "mean_schema_count"):
        result[key] = {"before": a[key], "after": b[key]}
    for key in ("load_tool_schema", "query_recipes"):
        result[key] = {"before": a["tool_call_counts"].get(key, 0), "after": b["tool_call_counts"].get(key, 0)}
    return result


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("results")
    parser.add_argument("log")
    parser.add_argument("--output", required=True)
    parser.add_argument("--archive", nargs="+")
    parser.add_argument("--before", help="Previous completed report; separately compare the automatic arms")
    args = parser.parse_args()
    result = report(args.results, args.log, args.archive)
    if args.before:
        result["automatic_before_after"] = compare_automatic_before(
            json.loads(Path(args.before).read_text(encoding="utf-8")), result)
    Path(args.output).write_text(json.dumps(result, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print(json.dumps(result.get("comparison", {"trials": len(result["trials"])}), ensure_ascii=False))
