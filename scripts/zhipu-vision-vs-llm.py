# -*- coding: utf-8 -*-
"""
对照实验：glm-4.6v-flash 单图 vs 六图 的限流表现；成功后把视觉结果喂给 LLM(gml-5)。
"""
import base64
import json
import os
import sys
import time

import requests

BASE = os.path.dirname(os.path.abspath(__file__))
CONFIG = os.path.join(BASE, "..", "run", "config", "touhou_little_maid", "sites", "vision.json")
TEST_IMG = os.path.join(BASE, "vision-test.jpg")
CUBEMAP_FACES = ["front", "right", "back", "left", "up", "down"]


def load_zhipu_site():
    with open(CONFIG, "r", encoding="utf-8") as f:
        for site in json.load(f):
            if site.get("id") == "zhipu":
                return site
    raise SystemExit("no zhipu site")


def build_body(site, images):
    messages = [{"role": "user", "content": [
        {"type": "text", "text": "Describe what you see. Return concise JSON with keys "
                                 "scene_summary, answer_to_focus, visible_text, hazards."}]}]
    for face in CUBEMAP_FACES:
        if face not in images:
            continue
        messages[0]["content"].append({"type": "text", "text": "Cubemap face: " + face})
        messages[0]["content"].append({"type": "image_url", "image_url": {"url": images[face]}})
    return {"model": site["model"], "messages": messages,
            "temperature": 0.1, "max_tokens": 800, "thinking": {"type": "disabled"}}


def once(site, body):
    try:
        r = requests.post(site["endpoint"],
                          headers={"Content-Type": "application/json",
                                   "Authorization": "Bearer " + site["api_key"]},
                          json=body, timeout=120)
    except Exception as e:
        return f"EXC {type(e).__name__}: {e}"
    if r.status_code != 200:
        return f"HTTP {r.status_code} {r.text[:200]}"
    return r.json()


def main():
    site = load_zhipu_site()
    with open(TEST_IMG, "rb") as f:
        data_url = "data:image/jpeg;base64," + base64.b64encode(f.read()).decode("ascii")

    print("== 对照：1图 vs 6图，各 3 轮，间隔 15s ==")
    visual_text = None
    for round_no in range(1, 4):
        for label, images in (("1图", {"front": data_url}),
                              ("6图", {f: data_url for f in CUBEMAP_FACES})):
            body = build_body(site, images)
            body_bytes = len(json.dumps(body))
            t0 = time.time()
            result = once(site, body)
            dt = time.time() - t0
            if isinstance(result, dict):
                content = result["choices"][0]["message"]["content"]
                print(f"[轮{round_no}] {label} (body≈{body_bytes//1024}KB) OK {dt:.1f}s -> {content[:120]!r}")
                if label == "6图" and visual_text is None:
                    visual_text = content
            else:
                print(f"[轮{round_no}] {label} (body≈{body_bytes//1024}KB) FAIL {dt:.1f}s -> {result[:120]}")
            time.sleep(15)

    if not visual_text:
        print("\n六图始终失败，改用单图结果喂 LLM")
        body = build_body(site, {"front": data_url})
        result = once(site, body)
        if isinstance(result, dict):
            visual_text = result["choices"][0]["message"]["content"]
    if not visual_text:
        print("仍无视觉结果，退出")
        return

    print("\n== T4: 视觉结果 -> 智谱 LLM glm-5 ==")
    llm_url = "https://open.bigmodel.cn/api/paas/v4/chat/completions"
    llm_body = {
        "model": "glm-5",
        "messages": [
            {"role": "system", "content": "你是女仆的对话大脑。工具 observe_surroundings 返回的内容是你亲眼所见，必须基于它如实回答，不要说自己看不到。"},
            {"role": "user", "content": "你看看周围，告诉我你看到了什么？"},
            {"role": "assistant", "content": None,
             "tool_calls": [{"id": "call_v1", "type": "function",
                             "function": {"name": "observe_surroundings",
                                          "arguments": json.dumps({"focus": "describe what you see"})}}]},
            {"role": "tool", "tool_call_id": "call_v1", "content": visual_text[:3000]},
        ],
        "temperature": 0.6, "max_tokens": 300,
    }
    r = requests.post(llm_url, headers={"Content-Type": "application/json",
                                        "Authorization": "Bearer " + site["api_key"]},
                      json=llm_body, timeout=120)
    print(f"LLM HTTP {r.status_code}")
    if r.status_code == 200:
        print("LLM 回答:", r.json()["choices"][0]["message"]["content"])
    else:
        print("LLM 错误:", r.text[:600])


if __name__ == "__main__":
    main()
