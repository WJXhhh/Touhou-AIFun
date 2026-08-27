# -*- coding: utf-8 -*-
"""
分辨率阶梯测试：stepfun / qwen / zhipu × 256/512/1024/2048/4096 单图。
判定: 大文字 'MAID TEST {N}' 是否读出(相对大小一致, 测基础识别)；
      12px 小字 'tiny 12px line' 是否读出(绝对像素, 测真实细节分辨率)。
照抄模组请求体 + 各家特判字段；429 重试 2 次。
"""
import base64
import json
import os
import time

import requests

BASE = os.path.dirname(os.path.abspath(__file__))
CONFIG = os.path.join(BASE, "..", "run", "config", "touhou_little_maid", "sites", "vision.json")
RES_DIR = os.path.join(BASE, "res-test")
SIZES = [256, 512, 1024, 2048, 4096]
SITE_IDS = ["stepfun", "qwen", "zhipu"]


def load_sites():
    with open(CONFIG, "r", encoding="utf-8") as f:
        return {s["id"]: s for s in json.load(f)}


def build_body(site, data_url):
    messages = [{"role": "user", "content": [
        {"type": "text", "text": "Read ALL text in this image literally and report it verbatim. "
                                 "Then describe shapes and colors. Return concise JSON with keys "
                                 "scene_summary, visible_text."}]}]
    messages[0]["content"].append({"type": "image_url", "image_url": {"url": data_url}})
    body = {"model": site["model"], "messages": messages, "temperature": 0.1, "max_tokens": 800}
    provider = site.get("provider", site["id"]).lower()
    if provider == "qwen":
        body["enable_thinking"] = False
    elif provider == "zhipu":
        body["thinking"] = {"type": "disabled"}
    return body


def extract_text(root):
    choices = root.get("choices")
    if isinstance(choices, list) and choices:
        choice = choices[0]
        if isinstance(choice, dict) and "message" in choice:
            content = choice["message"].get("content")
            if isinstance(content, str):
                return content
            if isinstance(content, list):
                return "".join(x.get("text", "") for x in content if isinstance(x, dict))
    return ""


def once(site, body):
    try:
        r = requests.post(site["endpoint"],
                          headers={"Content-Type": "application/json",
                                   "Authorization": "Bearer " + site["api_key"]},
                          json=body, timeout=180)
    except Exception as e:
        return None, f"EXC {type(e).__name__}: {e}"
    if r.status_code != 200:
        return None, f"HTTP {r.status_code} {r.text[:180]}"
    return r.json(), None


def main():
    sites = load_sites()
    results = []
    for sid in SITE_IDS:
        site = sites.get(sid)
        if not site or not site.get("api_key"):
            print(f"### {sid}: 无 key，跳过")
            continue
        print(f"### {sid}  model={site['model']}")
        for size in SIZES:
            img_path = os.path.join(RES_DIR, f"test-{size}.jpg")
            with open(img_path, "rb") as f:
                data_url = "data:image/jpeg;base64," + base64.b64encode(f.read()).decode("ascii")
            body = build_body(site, data_url)
            body_kb = len(json.dumps(body)) // 1024
            text, err = None, None
            for attempt in range(1, 3):
                root, err = once(site, body)
                if err is None:
                    text = extract_text(root)
                    break
                if "429" not in err or attempt == 2:
                    break
                time.sleep(8)
            if err:
                print(f"  [{size:4d}px] {err}  (body≈{body_kb}KB)")
                results.append((sid, size, err, ""))
                continue
            big = f"MAID TEST {size}" in text
            tiny = "tiny 12px" in text or "tiny" in text.lower()
            print(f"  [{size:4d}px] OK {body_kb}KB 大文字={'Y' if big else 'N'} 小字={'Y' if tiny else 'N'}")
            print(f"           {text[:220]!r}")
            results.append((sid, size, "OK", f"big={big},tiny={tiny}"))
            time.sleep(6)
    print("\n== 汇总 ==")
    for sid, size, status, note in results:
        print(f"  {sid:8s} {size:5d}px  {status:8s} {note}")


if __name__ == "__main__":
    main()
