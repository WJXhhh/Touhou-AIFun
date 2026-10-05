"""Synthetic tool-call/result smoke test; reads existing credentials without logging them.
Not a Minecraft task or latency benchmark. Does not modify provider configuration.
"""
import json, time, urllib.request, urllib.error
from pathlib import Path

sites = json.loads(Path("run/config/touhou_little_maid/sites/llm.json").read_text(encoding="utf-8"))
reports = []
for name in ("anthropic", "stepfun"):
    site = sites.get(name, {})
    if not site.get("enabled") or not site.get("secret_key"):
        reports.append(dict(provider=name,status="skipped_unconfigured")); continue
    model = site["models"][0]
    if isinstance(model,dict): model = model["name"]
    anthropic = site.get("api_type") == "anthropic"
    url = site["url"].rstrip("/")
    suffix = "/v1/messages" if anthropic else "/chat/completions"
    if not url.endswith(suffix): url += suffix
    headers = {"Content-Type":"application/json"}
    if anthropic: headers.update({"x-api-key":site["secret_key"],"anthropic-version":"2023-06-01"})
    else: headers["Authorization"] = "Bearer " + site["secret_key"]
    schema = {"type":"object","properties":{"count":{"type":"integer"}},"required":["count"]}
    tool = {"name":"runtime_probe","description":"Synthetic test only; returns a count without world operations."}
    if anthropic: tool["input_schema"] = schema
    else: tool = {"type":"function","function":dict(tool,parameters=schema)}
    messages = [{"role":"user","content":"Call runtime_probe with count=7 once. After receiving the result, reply exactly OK7."}]
    body = {"model":model,"max_tokens":2048,"messages":messages,"tools":[tool]}
    started = time.monotonic()
    try:
        def send():
            req = urllib.request.Request(url,json.dumps(body).encode(),headers=headers)
            with urllib.request.urlopen(req,timeout=90) as response: return json.load(response)
        first=send()
        if anthropic:
            calls=[v for v in first.get("content",[]) if v.get("type")=="tool_use"]
            if not calls: raise ValueError("no_tool_call")
            call=calls[0]
            if call["name"]!="runtime_probe" or call["input"].get("count")!=7: raise ValueError("invalid_tool_arguments")
            messages.append({"role":"assistant","content":first["content"]})
            messages.append({"role":"user","content":[{"type":"tool_result","tool_use_id":call["id"],"content":'{"count":7}'}]})
            final=send(); text="".join(b.get("text","") for b in final.get("content",[]))
        else:
            msg=first["choices"][0]["message"]; calls=msg.get("tool_calls",[])
            if not calls: raise ValueError("no_tool_call")
            call=calls[0]
            if call["function"]["name"]!="runtime_probe" or json.loads(call["function"]["arguments"]).get("count")!=7: raise ValueError("invalid_tool_arguments")
            messages.append(msg); messages.append({"role":"tool","tool_call_id":call["id"],"content":'{"count":7}'})
            final=send();text=final["choices"][0]["message"].get("content","") or ""
        report=dict(provider=name,status="passed" if "OK7" in text else "unexpected_reply",seconds=round(time.monotonic()-started,3))
    except urllib.error.HTTPError as error: report=dict(provider=name,status="http_error",code=error.code)
    except Exception as error: report=dict(provider=name,status="failed",error_type=type(error).__name__)
    reports.append(report);print(json.dumps(report),flush=True)
Path("build/agent-protocol-smoke.json").write_text(json.dumps(reports,indent=2),encoding="utf-8")
