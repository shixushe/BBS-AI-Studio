# -*- coding: utf-8 -*-
"""计划 JSON 离线评分器：量化 LLM 直出计划的质量基线。

指标（对应提示词的硬性规则）：
- bone_hallucination  骨骼幻觉率：poseObject 里不在已知清单的键比例
- hinge_violation     关节限位违反：肘 X∉[-150,0]、膝 X∉[0,150] 的拍数
- jump_violation      相邻拍同骨骼角度差 >60°（snap/impact 除外）
- same_side_violation 同侧臂腿同向摆（步行相关拍）
- move_sync_error     步幅-位移同步：move 增量速度偏离 0.216 格/tick 的比例
- budget_violation    拍数>24 或 total_ticks>420
- intent_mechanical   首意图为 linear/hold 的拍比例（机械拍）
- phase_unknown       未知 phase 比例
"""
import json
import sys

GENERIC = {
    "head", "headwear", "body", "torso", "torso_lower",
    "left_arm", "right_arm", "left_elbow", "right_elbow",
    "left_leg", "right_leg", "left_knee", "right_knee",
    "left_eye", "right_eye", "left_eyebrow", "right_eyebrow",
}

HINGE = {"elbow": (-150.0, 0.0), "knee": (0.0, 150.0)}

MIRROR = {"left_": "right_", "right_": "left_"}


def mirror_name(bone):
    for pre, other in MIRROR.items():
        if bone.startswith(pre):
            return other + bone[len(pre):]
    return bone


def beat_rotations(beat):
    out = {}
    obj = beat.get("poseObject")
    if not isinstance(obj, dict):
        return out
    for bone, data in obj.items():
        if not isinstance(data, dict):
            continue
        r = data.get("r")
        if isinstance(r, list) and len(r) >= 3 and all(isinstance(v, (int, float)) for v in r[:3]):
            out[bone] = [float(r[0]), float(r[1]), float(r[2])]
    return out


def main(path):
    with open(path, encoding="utf-8") as f:
        battery = json.load(f)

    report = []
    totals = dict.fromkeys(
        ("bone_hallucination", "hinge_violation", "jump_violation",
         "same_side_violation", "move_sync_error", "intent_mechanical",
         "phase_unknown", "budget_violation"), 0)

    for entry in battery:
        script = entry["script"]
        plan = entry["dump"].get("plan")
        row = {"script": script, "ok": bool(plan and plan.get("beats"))}

        if not row["ok"]:
            row["error"] = "no plan parsed"
            report.append(row)
            continue

        beats = plan["beats"]
        row["beats"] = len(beats)
        row["total_ticks"] = plan.get("total_ticks")

        # 预算
        row["budget_violation"] = int(len(beats) > 24 or plan.get("total_ticks", 0) > 420)
        totals["budget_violation"] += row["budget_violation"]

        # 骨骼幻觉
        known = GENERIC | set()
        hallucinated = checked = 0
        all_bones = set()
        for beat in beats:
            rot = beat_rotations(beat)
            all_bones |= set(rot)
        known |= {b for b in all_bones if b in GENERIC}
        for beat in beats:
            rot = beat_rotations(beat)
            for bone in rot:
                checked += 1
                if bone not in GENERIC and not bone.startswith("@"):
                    hallucinated += 1
        row["bone_hallucination"] = round(hallucinated / checked, 3) if checked else 0.0
        totals["bone_hallucination"] += hallucinated

        # 关节限位
        hinge_bad = hinge_checked = 0
        for beat in beats:
            rot = beat_rotations(beat)
            for bone in rot:
                kind = "elbow" if "elbow" in bone else ("knee" if "knee" in bone else None)
                if kind is None:
                    continue
                lo, hi = HINGE[kind]
                hinge_checked += 1
                x = rot[bone][0]
                if x < lo - 1e-6 or x > hi + 1e-6:
                    hinge_bad += 1
        row["hinge_violation"] = hinge_bad
        totals["hinge_violation"] += hinge_bad

        # 相邻拍跳变
        jump_bad = jump_checked = 0
        for prev, cur in zip(beats, beats[1:]):
            a, b = beat_rotations(prev), beat_rotations(cur)
            intents = [i.lower() for i in cur.get("intents") or []]
            impact = any(i in ("snap", "impact") for i in intents)
            for bone in set(a) & set(b):
                dx = abs(a[bone][0] - b[bone][0])
                dy = abs(a[bone][1] - b[bone][1])
                dz = abs(a[bone][2] - b[bone][2])
                jump_checked += 1
                if not impact and max(dx, dy, dz) > 60:
                    jump_bad += 1
        row["jump_violation"] = jump_bad
        totals["jump_violation"] += jump_bad

        # 同侧臂腿同向（有 move 的拍才算步行）
        same_side = same_checked = 0
        for beat in beats:
            if not beat.get("move"):
                continue
            rot = beat_rotations(beat)
            for side in ("left", "right"):
                arm, leg = rot.get(side + "_arm"), rot.get(side + "_leg")
                if arm is None or leg is None:
                    continue
                if abs(arm[0]) >= 8 and abs(leg[0]) >= 8:
                    same_checked += 1
                    if arm[0] * leg[0] > 0:
                        same_side += 1
        row["same_side_violation"] = same_side
        totals["same_side_violation"] += same_side

        # move 同步：段速度
        sync_err = sync_checked = 0
        moves = [(b["tick"], b["move"]) for b in beats if b.get("move")]
        for (t0, m0), (t1, m1) in zip(moves, moves[1:]):
            dt = t1 - t0
            if dt <= 0:
                continue
            dist = ((m1[0] - m0[0]) ** 2 + (m1[2] - m0[2]) ** 2) ** 0.5
            if dist < 1e-6:
                continue
            sync_checked += 1
            speed = dist / dt
            if abs(speed - 0.216) > 0.12:
                sync_err += 1
        row["move_sync_error"] = sync_err
        totals["move_sync_error"] += sync_err

        # 机械拍（首意图 linear/hold）
        mech = 0
        for beat in beats:
            intents = [i.lower() for i in beat.get("intents") or []]
            if intents and intents[0] in ("linear", "hold"):
                mech += 1
        row["intent_mechanical"] = mech
        totals["intent_mechanical"] += mech

        # 未知 phase
        phases = {"contact", "down", "passing", "up", "anticipation", "hold", "follow_through"}
        unknown = sum(1 for b in beats if b.get("phase") not in phases)
        row["phase_unknown"] = unknown
        totals["phase_unknown"] += unknown

        # 骨骼覆盖广度（每拍平均动几根）
        if beats:
            row["avg_bones_per_beat"] = round(
                sum(len(beat_rotations(b)) for b in beats) / len(beats), 1)

        report.append(row)

    print(json.dumps(report, ensure_ascii=False, indent=1))
    print("\n===== 基线汇总（8 剧本合计）=====")
    for k, v in totals.items():
        print("%-20s %s" % (k, v))


if __name__ == "__main__":
    main(sys.argv[1] if len(sys.argv) > 1 else "build/gen_battery.json")
