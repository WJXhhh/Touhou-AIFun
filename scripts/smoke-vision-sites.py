# -*- coding: utf-8 -*-
"""
三家视觉站点对照冒烟：zhipu / stepfun / qwen。
照抄 OpenAICompatibleVisionClient 的请求体与解析逻辑：
- qwen 特判: body 根加 enable_thinking=false
- zhipu 特判: body 加 thinking={"type":"disabled"}
- stepfun 无特判
每站点: 单图 + 六图，429 自动重试(最多3次, 间隔5s/10s/15s)。
"""
import base64
import json
import os
import time

import requests

BASE = os.path.dirname(os.path.abspath(__file__))
CONFIG = os.path.join(BASE, "..", "run", "config", "touhou_little_maid", "sites", "vision.json")
TEST_IMG = os.path.join(BASE, "vision-test.jpg")
CUBEMAP_FACES = ["front", "right", "back", "left", "up", "down"]
SITE_IDS = ["stepfun", "qwen", "zhipu"]


def load_sites():
    with open(CONFIG, "r", encoding="utf-8") as f:
        return {s["id"]: s for s in json.load(f)}


def content_text(element):
    if element is None:
        return ""
    if isinstance(element, str):
        return element
    if isinstance(element, list):
        out = []
        for item in element:
            if isinstance(item, str):
                out.append(item)
            elif isinstance(item, dict) and "text" in item:
                out.append(item["text"])
        return "".join(out)
    return json.dumps(element, ensure_ascii=False)


def extract_text(root):
    choices = root.get("choices")
    if isinstance(choices, list) and choices:
        choice = choices[0]
        if isinstance(choice, dict) and "message" in choice:
            return content_text(choice["message"].get("content"))
        if isinstance(choice, dict) and "text" in choice:
            return content_text(choice.get("text"))
    for key in ("output", "data"):
        if key in root:
            return content_text(root.get(key))
    return ""


def strip_json_fence(value):
    text = (value or "").strip()
    if text.startswith("```") and text.endswith("```"):
        nl = text.find("\n")
        if nl >= 0:
            text = text[nl + 1:-3].strip()
    return text


def observation_summary(text):
    text = (text or "").strip()
    if not text:
        return "<空>"
    try:
        structured = json.loads(strip_json_fence(text))
        if isinstance(structured, dict):
            scene = structured.get("scene_summary", "")
            return (scene or "<无scene_summary>")[:150]
    except Exception:
        pass
    return text[:150]


def build_body(site, images):
    messages = [{"role": "user", "content": [
        {"type": "text", "text": "You are the maid's visual grounding model. Return concise JSON with keys "
                                 "scene_summary, answer_to_focus, grounded_matches, visible_text, hazards, uncertainties. "
                                 "The six images are cubemap faces named front/right/back/left/up/down. "
                                 "Focus: describe what you see. "
                                 "No server scan is available. Do not claim an exact registry id from texture alone."}]}]
    for face in CUBEMAP_FACES:
        if face not in images:
            continue
        messages[0]["content"].append({"type": "text", "text": "Cubemap face: " + face})
        messages[0]["content"].append({"type": "image_url", "image_url": {"url": images[face]}})
    body = {"model": site["model"], "messages": messages, "temperature": 0.1, "max_tokens": 800}
    provider = site.get("provider", site["id"]).lower()
    if provider == "qwen":
        body["enable_thinking"] = False
    elif provider == "zhipu":
        body["thinking"] = {"type": "disabled"}
    return body


def once(site, body, label):
    try:
        r = requests.post(site["endpoint"],
                          headers={"Content-Type": "application/json",
                                   "Authorization": "Bearer " + site["api_key"]},
                          json=body, timeout=120)
    except Exception as e:
        return f"EXC {type(e).__name__}: {e}"
    return r


def run_case(site, images, label):
    body = build_body(site, images)
    body_kb = len(json.dumps(body)) // 1024
    for attempt in range(1, 4):
        r = once(site, body, label)
        dt = r.elapsed.total_seconds() if hasattr(r, "elapsed") else 0
        if r.status_code == 200:
            try:
                root = r.json()
            except Exception:
                print(f"[{label}] 200 但非JSON: {r.text[:200]}")
                return
            text = extract_text(root)
            print(f"[{label}] 200 ({dt:.1f}s, body≈{body_kb}KB) -> {observation_summary(text)}")
            print(f"         原文前240: {text[:240]!r}")
            return
        print(f"[{label}] HTTP {r.status_code} ({dt:.1f}s, body≈{body_kb}KB) 尝试{attempt}/3: {r.text[:220]}")
        if r.status_code != 429:
            return
        time.sleep(5 * attempt)
    print(f"[{label}] 三次重试后仍失败")


def main():
    sites = load_sites()
    with open(TEST_IMG, "rb") as f:
        data_url = "data:image/jpeg;base64," + base64.b64encode(f.read()).decode("ascii")
    print(f"测试图: {os.path.getsize(TEST_IMG)} bytes")
    for sid in SITE_IDS:
        site = sites.get(sid)
        if not site:
            print(f"\n### {sid}: config 里不存在")
            continue
        print(f"\n### {sid}  provider={site.get('provider')}  model={site['model']}")
        print(f"    endpoint={site['endpoint']}")
        print(f"    api_key={site['api_key'][:10]}... (len={len(site['api_key'])})")
        if not site.get("api_key"):
            print("    !! api_key 为空 —— 模组代码会直接返回 'visual site has no API key'")
            continue
        run_case(site, {"front": data_url}, f"{sid} 单图")
        time.sleep(8)
        run_case(site, {f: data_url for f in CUBEMAP_FACES}, f"{sid} 六图")
        time.sleep(8)


if __name__ == "__main__":
    main()
