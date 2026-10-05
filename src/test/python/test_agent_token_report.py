import importlib.util
import json
import tempfile
import unittest
from pathlib import Path

path=Path(__file__).resolve().parents[3]/"scripts"/"agent-token-report.py"
spec=importlib.util.spec_from_file_location("token_report",path)
module=importlib.util.module_from_spec(spec);spec.loader.exec_module(module)


class TokenReportTest(unittest.TestCase):
    def test_unknown_cache_usage_does_not_lower_reported_hit_ratio(self):
        with tempfile.TemporaryDirectory() as directory:
            log=Path(directory)/"test.log"
            events=[]
            for usage in [{"input_tokens":100,"output_tokens":9,"cached_input_tokens":80},
                          {"input_tokens":500,"output_tokens":10,"private_text":"SECRET"}]:
                events.append("AIFun usage "+json.dumps({"version":1,"context":{"task_id":"task","generation":1,"purpose":"TASK_EXECUTION"},"protocol":"anthropic","usage":usage}))
            log.write_text("private prompt never print\n"+"\n".join(events),encoding="utf8")
            data=module.report(log)
        group=data["groups"][0]
        self.assertEqual(.8,group["cache_hit_ratio_for_reported_requests"])
        self.assertEqual(600,group["input_tokens"])
        self.assertEqual(1,group["requests_without_cache_usage"])
        self.assertNotIn("SECRET",json.dumps(data))

    def test_old_log_does_not_fabricate_zero_cache_hit_rate(self):
        with tempfile.TemporaryDirectory() as directory:
            log=Path(directory)/"old.log";log.write_text("old log without numeric usage",encoding="utf8")
            self.assertEqual([],module.report(log)["groups"])
