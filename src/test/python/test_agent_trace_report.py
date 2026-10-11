import importlib.util
import json
import tempfile
import unittest
from pathlib import Path

path = Path(__file__).resolve().parents[3] / "scripts" / "agent-trace-report.py"
spec = importlib.util.spec_from_file_location("trace_report", path)
module = importlib.util.module_from_spec(spec)
spec.loader.exec_module(module)


class TraceReportTest(unittest.TestCase):
    def test_overlap_and_unfinished_generations_are_not_summed_into_wall_time(self):
        events = []
        def add(span, stage, start, duration, event="finish", generation=1, status="ok"):
            events.append({"version": 1, "run_id": "run", "span_id": span, "stage": stage,
                "event": event, "status": status, "start_ns": start * 1000000, "duration_ns": duration * 1000000,
                "result_chars": 0, "context": {"task_id": "task", "trace_id": "trace", "generation": generation, "purpose": "TASK_EXECUTION"}})
        add("task", "task_execution", 0, 100)
        add("model", "model_request", 0, 60)
        add("model", "model_request", 0, 10, "response_headers")
        add("model", "model_request", 0, 30, "first_output")
        add("tool", "tool_total:gui_action", 40, 40)
        add("incomplete", "model_request", 200, 0, "start", 2)
        with tempfile.TemporaryDirectory() as directory:
            log = Path(directory) / "test.log"
            log.write_text("private conversation and API key, never print source\n" + "\n".join("AIFun trace " + json.dumps(e) for e in events), encoding="utf8")
            report = module.report(log, True)
        first, second = report["groups"]
        self.assertEqual(80, first["model_and_tool_occupied_ms"])
        self.assertEqual(60, first["stages"][1]["p50_ms"])
        self.assertEqual(30, first["model_phases"]["first_output"]["p50_ms"])
        self.assertEqual(100, first["latency_breakdown"]["task_wall_ms"])
        self.assertEqual(.6, first["latency_breakdown"]["model_share"])
        self.assertEqual(40, first["latency_breakdown"]["tool_occupied_ms"])
        self.assertEqual(1, len(second["unfinished_spans"]))
        self.assertNotIn("private conversation", json.dumps(report))

    def test_log_rotation_changes_span_namespace(self):
        self.assertEqual(20, module.occupied([(0, 10), (0, 10), (10, 20)]))

    def test_decisions_have_separate_wall_time_and_fallback_counts(self):
        events = []
        for span, stage, start, duration in [("task", "task_execution", 0, 100),
                ("model", "model_request", 0, 20), ("decision", "decision_request:perplexity", 20, 40),
                ("tool", "tool_total:gui_action", 50, 30), ("fallback", "decision_fallback:uncertain_or_none", 60, 0),
                ("direct", "decision_dispatch:deterministic_step", 80, 0)]:
            events.append({"version": 1, "run_id": "run", "span_id": span, "stage": stage,
                "event": "finish", "status": "ok", "start_ns": start * 1000000,
                "duration_ns": duration * 1000000, "result_chars": 0,
                "context": {"task_id": "task", "trace_id": "trace", "generation": 1, "purpose": "TASK_EXECUTION"}})
        with tempfile.TemporaryDirectory() as directory:
            log = Path(directory) / "test.log"
            log.write_text("\n".join("AIFun trace " + json.dumps(e) for e in events), encoding="utf8")
            result = module.report(log)["groups"][0]
        self.assertEqual(1, result["decision_requests"])
        self.assertEqual(1, result["deterministic_steps"])
        self.assertEqual({"uncertain_or_none": 1}, result["decision_fallbacks"])
        self.assertEqual(40, result["latency_breakdown"]["decision_occupied_ms"])
        self.assertEqual(20, result["latency_breakdown"]["model_occupied_ms"])
        self.assertEqual(80, result["model_and_tool_occupied_ms"])

    def test_model_share_clips_late_spans_and_excludes_queue_and_notification_wait(self):
        events = []
        for span, stage, start, duration in [("task", "task_execution", 10, 90),
                ("model", "model_request", 0, 130), ("tool", "tool_total:gui_action", 30, 30),
                ("child", "tool_execution", 30, 30), ("queue", "task_queue_wait", 0, 10),
                ("notice", "notification_wait", 100, 20), ("summary", "model_request", 20, 10)]:
            events.append({"version": 1, "run_id": "run", "span_id": span, "stage": stage,
                "event": "finish", "status": "ok", "start_ns": start * 1000000,
                "duration_ns": duration * 1000000, "result_chars": 0,
                "context": {"task_id": "task", "trace_id": "trace", "generation": 1,
                    "purpose": "KNOWLEDGE_EXTRACTION" if span == "summary" else "TASK_EXECUTION"}})
        with tempfile.TemporaryDirectory() as directory:
            log = Path(directory) / "test.log"
            log.write_text("\n".join("AIFun trace " + json.dumps(e) for e in events), encoding="utf8")
            result = module.report(log)["groups"][0]["latency_breakdown"]
        self.assertEqual(90, result["task_wall_ms"])
        self.assertEqual(90, result["model_occupied_ms"])
        self.assertEqual(10, result["auxiliary_model_occupied_ms"])
        self.assertEqual(1, result["model_share"])
        self.assertEqual(30, result["tool_occupied_ms"])
        self.assertEqual(10, result["task_queue_wait_ms"])
        self.assertEqual(20, result["notification_wait_ms"])


if __name__ == "__main__":
    unittest.main()
