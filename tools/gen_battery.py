# -*- coding: utf-8 -*-
"""生成质量基线电池：驱动游戏内真实 LLM 生成 8 个覆盖性剧本，采集解析后的计划 JSON。"""
import json
import time
import urllib.request

BASE = "http://127.0.0.1:17878"

SCRIPTS = [
    "角色向前走路",
    "角色垂头丧气地慢慢走",
    "角色向前奔跑",
    "角色站着原地呼吸休息",
    "角色开心地挥手打招呼",
    "角色用力打出一拳",
    "角色蹲下然后站起来",
    "角色先走路然后挥手中途停下鞠躬",
]


def post(op, **kw):
    body = dict(op=op, **kw)
    data = json.dumps(body, ensure_ascii=False).encode("utf-8")
    req = urllib.request.Request(BASE + "/debug", data=data,
                                 headers={"Content-Type": "application/json; charset=utf-8"})
    with urllib.request.urlopen(req, timeout=60) as r:
        outer = json.loads(r.read().decode("utf-8"))

    # /debug 把负载包在 {"result": "<json 字符串>"} 里——解包
    inner = outer.get("result")

    if isinstance(inner, str):
        try:
            return json.loads(inner)
        except Exception:
            return {"raw": inner}

    return outer


def fingerprint(plan):
    if not plan or not plan.get("beats"):
        return ""
    return json.dumps(plan["beats"], ensure_ascii=False, sort_keys=True)


def main():
    out = []
    prev_fp = ""

    for idx, script in enumerate(SCRIPTS):
        post("aiDump", script=script)

        dump = None
        waited = 0

        while waited < 150:
            time.sleep(5)
            waited += 5

            dump = post("aiDump")
            plan = dump.get("plan")

            if plan and plan.get("beats") and fingerprint(plan) != prev_fp:
                break
        else:
            print("[%d] TIMEOUT waiting for plan: %s" % (idx, script))

        plan = (dump or {}).get("plan")
        fp = fingerprint(plan)
        prev_fp = fp

        beats = plan.get("beats") if plan else []
        print("[%d] %s -> %s beats, %s ticks (%ds)" % (
            idx, script, len(beats) if beats else 0,
            plan.get("total_ticks") if plan else "?", waited))

        out.append({"script": script, "dump": dump})

    with open("build/gen_battery.json", "w", encoding="utf-8") as f:
        json.dump(out, f, ensure_ascii=False, indent=1)

    print("saved build/gen_battery.json")


if __name__ == "__main__":
    main()
