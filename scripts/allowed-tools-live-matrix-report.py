"""Summarize every completed Flash pair, including regressions and ties.

Usage: python scripts/allowed-tools-live-matrix-report.py REPORT... --output MATRIX
Reads the content-free, audited paired reports, never API credentials or raw prompts.
"""
import argparse
import json
import statistics
from pathlib import Path


def matrix(paths):
    pairs = []
    for path in paths:
        report = json.loads(Path(path).read_text(encoding="utf-8"))
        baseline = next(t for t in report["trials"] if t["mode"] == "baseline")
        automatic = next(t for t in report["trials"] if t["mode"] == "automatic_allowed_tools")
        valid = (report["comparison"].get("performance_comparison_valid", False)
                 and all(t["model"] == "deepseek-flash" and t["input_budget"] == 475000
                         and t["completed"] and t["usage_complete"] and all(t["checks"].values())
                         and t.get("selection_telemetry_complete") and t.get("wire_prefix_telemetry_complete")
                         for t in (baseline, automatic))
                 and baseline["scenario"] == automatic["scenario"]
                 and baseline["requests_with_active_allowlist"] == 0)
        pair = {"report": str(path), "valid": valid, "scenario": automatic["scenario"],
                "first_arm": report["trials"][0]["mode"], "metrics": {},
                "automatic_active_requests": automatic["requests_with_active_allowlist"],
                "automatic_longest_stable_active_requests": automatic["longest_stable_active_selection_requests"],
                "automatic_selection_sources": automatic.get("selection_sources", {}),
                "automatic_active_schema_churn": automatic["active_schema_changes_without_selection_or_scope_change"],
                "automatic_allowlists_by_phase": automatic["active_allowlists_by_verified_phase"],
                "automatic_compression_events": automatic["compression_events"],
                "automatic_planning_only_requests": automatic["planning_only_requests"],
                "automatic_metadata_only_planning_requests": automatic["metadata_only_planning_requests"],
                "baseline_planning_only_requests": baseline["planning_only_requests"],
                "automatic_errors": automatic["failures"], "baseline_errors": baseline["failures"],
                "automatic_tool_calls": automatic["tool_call_counts"],
                "baseline_tool_calls": baseline["tool_call_counts"]}
        for field in ("total_tokens", "uncached_input_tokens", "rounds"):
            a = baseline["all_model_requests_including_auxiliary"] if field == "rounds" else baseline["actual_provider_usage"][field]
            b = automatic["all_model_requests_including_auxiliary"] if field == "rounds" else automatic["actual_provider_usage"][field]
            pair["metrics"][field] = {"baseline": a, "automatic": b,
                                     "reduction_percent": round(100 * (a - b) / a, 2) if a and valid else None}
        pair["all_three_strictly_lower"] = valid and all(m["automatic"] < m["baseline"] for m in pair["metrics"].values())
        pair["all_three_no_increase"] = valid and all(m["automatic"] <= m["baseline"] for m in pair["metrics"].values())
        pairs.append(pair)
    valid = bool(pairs) and all(p["valid"] for p in pairs)
    output = {"scope": "Small real-task paired sample; per-pair variability retained, not a guarantee or statistical significance claim.",
              "model": "deepseek-flash", "input_budget": 475000, "all_pairs_valid": valid, "pairs": pairs,
              "strict_joint_wins": sum(p["all_three_strictly_lower"] for p in pairs),
              "joint_no_increase_pairs": sum(p["all_three_no_increase"] for p in pairs)}
    if valid:
        output["metrics"] = {}
        for field in ("total_tokens", "uncached_input_tokens", "rounds"):
            values = [p["metrics"][field] for p in pairs]
            a, b = sum(v["baseline"] for v in values), sum(v["automatic"] for v in values)
            output["metrics"][field] = {"baseline_sum": a, "automatic_sum": b,
                                       "aggregate_reduction_percent": round(100 * (a - b) / a, 2),
                                       "median_pair_reduction_percent": statistics.median(v["reduction_percent"] for v in values),
                                       "min_pair_reduction_percent": min(v["reduction_percent"] for v in values),
                                       "max_pair_reduction_percent": max(v["reduction_percent"] for v in values),
                                       "strict_wins": sum(v["automatic"] < v["baseline"] for v in values),
                                       "ties": sum(v["automatic"] == v["baseline"] for v in values),
                                       "regressions": sum(v["automatic"] > v["baseline"] for v in values)}
    return output


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("reports", nargs="+")
    parser.add_argument("--output", required=True)
    args = parser.parse_args()
    result = matrix(args.reports)
    Path(args.output).write_text(json.dumps(result, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print(json.dumps({k: result[k] for k in ("all_pairs_valid", "strict_joint_wins", "joint_no_increase_pairs")}, ensure_ascii=False))
