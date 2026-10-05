"""Usage-only AIFun diagnostic report. Never prints prompts, credentials or model output.
Usage: python scripts/agent-token-report.py logfile [--output result.json]
"""
import argparse
import json
from collections import defaultdict
from pathlib import Path


def report(path):
    groups = defaultdict(list)
    malformed = 0
    for line in Path(path).read_text(encoding="utf8", errors="replace").splitlines():
        if "AIFun usage " not in line:
            continue
        try:
            event = json.loads(line.split("AIFun usage ", 1)[1])
            if event["version"] != 1:
                continue
            context = event["context"]
            key = (context.get("task_id") or context["trace_id"], context["generation"], context["purpose"], event["protocol"])
            # Accept only a numeric whitelist; unknown fields cannot leak private text into reports.
            usage = {k: v for k, v in event["usage"].items() if k in (
                "input_tokens", "output_tokens", "total_tokens", "cached_input_tokens",
                "uncached_input_tokens", "cache_write_input_tokens", "reasoning_tokens")
                and isinstance(v, int) and not isinstance(v, bool) and v >= 0}
            groups[key].append(usage)
        except (ValueError, KeyError, TypeError):
            malformed += 1
    results = []
    for (task, generation, purpose, protocol), rows in groups.items():
        known = [r for r in rows if "cached_input_tokens" in r]
        cached = sum(r["cached_input_tokens"] for r in known)
        input_known = sum(r.get("input_tokens", 0) for r in known)
        uncached = [r["uncached_input_tokens"] for r in rows if "uncached_input_tokens" in r]
        writes = [r["cache_write_input_tokens"] for r in rows if "cache_write_input_tokens" in r]
        results.append({"task_or_chat_id": task, "generation": generation, "purpose": purpose, "protocol": protocol,
            "requests_with_usage": len(rows), "requests_with_cache_usage": len(known),
            "requests_without_cache_usage": len(rows)-len(known),
            "input_tokens": sum(r.get("input_tokens", 0) for r in rows),
            "output_tokens": sum(r.get("output_tokens", 0) for r in rows),
            "cached_input_tokens": cached if known else None,
            "cache_hit_ratio_for_reported_requests": cached / input_known if input_known else None,
            "requests_with_uncached_usage": len(uncached),
            "uncached_input_tokens_reported": sum(uncached) if uncached else None,
            "cache_write_input_tokens_reported": sum(writes) if writes else None})
    return {"note": "Unknown cache usage is not zero. Ratios use only requests with reported cache usage. Tokens are not currency; prices depend on provider/model. Older logs cannot establish cache hit rate.",
            "malformed_records": malformed, "groups": results}


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("logfile")
    parser.add_argument("--output")
    args = parser.parse_args()
    data = json.dumps(report(args.logfile), indent=2)
    if args.output:
        Path(args.output).write_text(data, encoding="utf8")
    print(data)
