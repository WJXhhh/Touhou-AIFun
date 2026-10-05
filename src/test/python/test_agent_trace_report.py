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
        self.assertEqual(1, len(second["unfinished_spans"]))
        self.assertNotIn("private conversation", json.dumps(report))

    def test_log_rotation_changes_span_namespace(self):
        self.assertEqual(20, module.occupied([(0, 10), (0, 10), (10, 20)]))


if __name__ == "__main__":
    unittest.main()
