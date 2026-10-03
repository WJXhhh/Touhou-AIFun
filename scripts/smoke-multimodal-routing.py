"""Exercise six-face inputs after a complete tool-result batch using local credentials.

No screenshots, request bodies or credentials are logged or saved. Synthetic JPEGs remain
in memory. Legacy vision credentials are only a test fallback before the game migrates them.
Run with Python 3 + requests + Pillow. This tests provider protocols, not Minecraft capture.
"""
import argparse
import base64
import concurrent.futures
import io
import json
import re
from pathlib import Path
import time
import uuid

import requests
from PIL import Image

ROOT = Path(__file__).resolve().parents[1]
FACES = ("front", "right", "back", "left", "up", "down")
COLORS = ("red", "green", "blue", "yellow", "white", "black")
CASES = (
    ("stepfun", "step-3.7-flash", "chat"),
    ("qwen", "qwen3-vl-flash", "chat"),
    ("zhipu", "glm-4.6v-flash", "chat"),
    ("sensenova", "sensenova-6.7-flash-lite", "chat"),
    ("opencode_go", "qwen3.6-plus", "anthropic"),
    ("opencode_go", "mimo-v2.5", "chat"),
    ("opencode_go", "muse-spark-1.2-contributor", "responses"),
    ("chatgpt_subscription", "gpt-6.1-sol", "subscription"),
)


def images(protocol):
    blocks = [{"type": "input_text" if protocol in ("responses", "subscription") else "text",
               "text": "Describe the actual color in each of the six images. Return six entries using the face labels. "
                       "Ignore the earlier placeholder tool text. No other tools needed."}]
    for face, color in zip(FACES, COLORS):
        buffer = io.BytesIO()
        Image.new("RGB", (128, 128), color).save(buffer, format="JPEG")
        data = base64.b64encode(buffer.getvalue()).decode()
        blocks.append({"type": blocks[0]["type"], "text": "Cubemap face: " + face})
        if protocol == "anthropic":
            blocks.append({"type": "image", "source": {"type": "base64", "media_type": "image/jpeg", "data": data}})
        elif protocol == "chat":
            blocks.append({"type": "image_url", "image_url": {"url": "data:image/jpeg;base64," + data}})
        else:
            blocks.append({"type": "input_image", "image_url": "data:image/jpeg;base64," + data})
    return {"role": "user", "content": blocks}


def body(model, protocol):
    calls = [("capture", "observe_surroundings"), ("scan", "scan_surroundings")]
    schema = {"type": "object", "properties": {}}
    definitions = [{"name": name, "description": "Observe the game world", "parameters": schema} for _, name in calls]
    if protocol == "chat":
        history = [{"role": "user", "content": "Observe the environment."},
                   {"role": "assistant", "content": "", "tool_calls": [
                       {"id": id_, "type": "function", "function": {"name": name, "arguments": "{}"}}
                       for id_, name in calls]}]
        history += [{"role": "tool", "tool_call_id": id_, "content": "Observation complete; images follow."} for id_, _ in calls]
        return {"model": model, "messages": history + [images(protocol)], "stream": False,
                "tools": [{"type": "function", "function": definition} for definition in definitions]}
    if protocol == "anthropic":
        history = [{"role": "user", "content": "Observe the environment."}, {"role": "assistant", "content": [
            {"type": "tool_use", "id": id_, "name": name, "input": {}} for id_, name in calls]},
            {"role": "user", "content": [{"type": "tool_result", "tool_use_id": id_, "content": "Observation complete; images follow."} for id_, _ in calls]}]
        return {"model": model, "messages": history + [images(protocol)], "max_tokens": 512, "stream": False,
                "tools": [{"name": name, "description": "Observe the game world", "input_schema": schema} for _, name in calls]}
    history = [{"role": "user", "content": "Observe the environment."}]
    history += [{"type": "function_call", "call_id": id_, "name": name, "arguments": "{}",
                 **({"namespace": "maid"} if protocol == "subscription" else {})} for id_, name in calls]
    history += [{"type": "function_call_output", "call_id": id_, "output": "Observation complete; images follow."} for id_, _ in calls]
    tools = [{"type": "function", **definition, "strict": False} for definition in definitions]
    if protocol == "subscription": tools = [{"type": "namespace", "name": "maid", "description": "Minecraft maid tools", "tools": tools}]
    return {"model": model, "input": history + [images(protocol)], "store": False, "stream": protocol == "subscription", "tools": tools}


def run(case):
    site_id, model, protocol = case
    result = {"site": site_id, "model": model, "protocol": protocol}
    sites_dir = ROOT / "run/config/touhou_little_maid/sites"
    shared = json.loads((sites_dir / "llm.json").read_text(encoding="utf-8-sig"))
    if isinstance(shared, dict): shared = shared.get("sites", list(shared.values()))
    site = next((s for s in shared if s.get("id") == site_id), {})
    if not site.get("secret_key"):
        site = next((s for s in shared if s.get("id") == "vision_" + site_id), site)
    key, url, headers = site.get("secret_key", ""), site.get("url", ""), dict(site.get("headers", {}))
    if not key and protocol != "subscription" and (sites_dir / "vision.json").exists():
        legacy = json.loads((sites_dir / "vision.json").read_text(encoding="utf-8-sig"))
        old = next((s for s in legacy if s.get("id") == site_id), {})
        key, url, headers = old.get("api_key", ""), old.get("endpoint", ""), dict(old.get("headers", {}))
    if protocol == "subscription":
        saved = ROOT / "run/config/touhou_aifun/chatgpt/session.json"
        session = json.loads(saved.read_text()) if saved.exists() else {}
        key = session.get("access_token", "")
        if key and session.get("expires_at", 0) <= time.time() + 90 and session.get("refresh_token"):
            try:
                refreshed = requests.post("https://auth.openai.com/api/accounts/oauth/token", data={
                    "grant_type": "refresh_token", "client_id": session["client_id"], "refresh_token": session["refresh_token"],
                    "resource": "https://api.openai.com/v1"}, timeout=20)
                if refreshed.status_code == 200:
                    tokens = refreshed.json()
                    for field in ("access_token", "refresh_token", "id_token", "token_type"):
                        if tokens.get(field): session[field] = tokens[field]
                    session["expires_at"] = int(time.time()) + tokens["expires_in"]
                    if tokens.get("scope"): session["scopes"] = tokens["scope"].split()
                    # Preserve the existing file's ACL; refresh-token rotation must be persisted.
                    saved.write_text(json.dumps(session, indent=2), encoding="utf-8")
                    key = session["access_token"]
            except Exception: return result | {"status": "unverified_token_refresh_failed"}
        url = "https://api.openai.com/v1/responses"
    if not key:
        return result | {"status": "unverified_no_credentials"}
    if site_id == "opencode_go":
        headers.setdefault("x-opencode-session", str(uuid.uuid4()))
        headers.setdefault("User-Agent", "Touhou-AIFun/Minecraft-1.20.1")
        suffix = {"chat": "/v1/chat/completions", "anthropic": "/v1/messages", "responses": "/v1/responses"}[protocol]
        url = url.rstrip("/") + suffix
    payload = body(model, protocol)
    if site_id == "stepfun":
        payload.update(reasoning_effort="low", reasoning_format="deepseek-style")
    if site_id == "qwen": payload["enable_thinking"] = False
    if site_id == "zhipu": payload["thinking"] = {"type": "disabled"}
    if site_id == "sensenova": payload["reasoning_effort"] = "none"
    headers.update({"Content-Type": "application/json"})
    if protocol == "anthropic": headers.update({"x-api-key": key, "anthropic-version": "2023-06-01"})
    else: headers["Authorization"] = "Bearer " + key
    if protocol == "subscription": headers["Accept"] = "text/event-stream"
    started = time.monotonic()
    try:
        response = requests.post(url, headers=headers, json=payload, timeout=65)
        result.update(http=response.status_code, seconds=round(time.monotonic() - started, 1))
        if response.status_code != 200:
            try:
                fault = response.json().get("error", {})
                message = str(fault.get("message", "")) if isinstance(fault, dict) else str(fault)
                message = message.replace(key, "[credential omitted]")
                message = re.sub(r"[A-Za-z0-9_+/=-]{40,}", "[value omitted]", message)
            except Exception: message = "non-JSON error"
            return result | {"status": "provider_rejected", "reason": message[:180]}
        if protocol == "subscription":
            events = [json.loads(line[5:].strip()) for line in response.text.splitlines()
                      if line.startswith("data:") and line[5:].strip() not in ("", "[DONE]")]
            text = "".join(e.get("delta", "") for e in events if e.get("type") == "response.output_text.delta")
            if not any(e.get("type") == "response.completed" for e in events):
                return result | {"status": "incomplete_stream"}
        elif protocol == "chat": text = response.json()["choices"][0]["message"].get("content", "")
        elif protocol == "anthropic": text = "".join(b.get("text", "") for b in response.json().get("content", []) if b.get("type") == "text")
        else: text = "".join(b.get("text", "") for item in response.json().get("output", []) for b in item.get("content", []) if b.get("type") == "output_text")
        matched = all(face in text.lower() for face in FACES) and all(color in text.lower() for color in COLORS)
        return result | {"status": "ok" if matched else "answer_mismatch", "six_faces_and_colors": matched}
    except Exception as error:
        return result | {"status": "transport_or_parse_error", "error_type": type(error).__name__}


if __name__ == "__main__":
    parser = argparse.ArgumentParser()
    parser.add_argument("--site", action="append", help="Restrict to one or more site IDs")
    args = parser.parse_args()
    cases = [case for case in CASES if not args.site or case[0] in args.site]
    with concurrent.futures.ThreadPoolExecutor(max_workers=3) as executor:
        results = []
        for future in concurrent.futures.as_completed([executor.submit(run, case) for case in cases]):
            result = future.result(); results.append(result)
            print(json.dumps(result, ensure_ascii=False), flush=True)
    report = ROOT / "build/multimodal-smoke-results.json"
    report.parent.mkdir(parents=True, exist_ok=True)
    report.write_text(json.dumps(results, ensure_ascii=False, indent=2), encoding="utf-8")
