#!/usr/bin/env python3
import argparse
import glob
import json
import math
from pathlib import Path

import numpy as np
from scipy.optimize import differential_evolution

FAMILIES = ["落宮", "太陽星座級關係", "逆行", "行星相位"]
DOMAINS = ["財運", "戀愛", "工作／學業", "人際", "健康"]
GRADES = ["大吉", "吉", "小吉", "平", "小凶", "凶", "大凶"]
BASE_COEFFICIENTS = np.array([8.0, 5.0, -3.0, 10.0], dtype=np.float64)
TARGET = 0.10


def load_features(pattern: str) -> np.ndarray:
    files = sorted(glob.glob(pattern))
    if not files:
        raise SystemExit(f"no feature files match {pattern}")
    arrays = []
    for path in files:
        raw = np.fromfile(path, dtype=np.float32)
        if raw.size % 20:
            raise ValueError(f"{path}: float count {raw.size} not divisible by 20")
        arrays.append(raw.reshape(-1, 4, 5))
    return np.concatenate(arrays, axis=0)


def decode(params: np.ndarray):
    multipliers = np.exp(params[:20]).reshape(4, 5)
    logits = params[20:25]
    logits = logits - np.max(logits)
    soft = np.exp(logits)
    soft /= soft.sum()
    # Every overall-domain weight is guaranteed >=5% and <80%; no zero/100% weight is possible.
    overall_weights = 0.05 + 0.75 * soft
    return multipliers, overall_weights


def adjusted_scores(x: np.ndarray, multipliers: np.ndarray) -> np.ndarray:
    return np.einsum("nfk,fk->nk", x, multipliers, optimize=True)


def core_events(scores: np.ndarray, overall: np.ndarray):
    dai_ji = scores >= 10.0
    dai_xiong = scores <= -10.0
    worse_than_ping = scores <= -1.8
    better_than_ping = scores >= 1.8
    a = (dai_ji.sum(axis=1) == 1) & (overall < 10.0) & (worse_than_ping.sum(axis=1) >= 2)
    b = (dai_xiong.sum(axis=1) == 1) & (overall > -10.0) & (better_than_ping.sum(axis=1) >= 2)
    c = np.all(scores >= 5.5, axis=1)
    d = np.all(scores <= -5.5, axis=1)
    return np.array([a.mean(), b.mean(), c.mean(), d.mean()], dtype=np.float64)


def objective(params: np.ndarray, x: np.ndarray) -> float:
    multipliers, weights = decode(params)
    scores = adjusted_scores(x, multipliers)
    overall = scores @ weights
    probs = core_events(scores, overall)
    # One percentage point error on a target contributes 1.0.
    error = float(np.sum(((probs - TARGET) / 0.01) ** 2))
    # Prefer less violent coefficient movement when two candidates fit similarly.
    regularity = 0.015 * float(np.mean(np.log(multipliers) ** 2))
    return error + regularity


def grade_codes(values: np.ndarray) -> np.ndarray:
    out = np.full(values.shape, 6, dtype=np.int8)
    out[values > -10.0] = 5
    out[values > -5.5] = 4
    out[values > -1.8] = 3
    out[values >= 1.8] = 2
    out[values >= 5.5] = 1
    out[values >= 10.0] = 0
    return out


def rate(mask: np.ndarray) -> float:
    return float(np.mean(mask))


def summarize(name: str, x: np.ndarray, multipliers: np.ndarray, weights: np.ndarray):
    scores = adjusted_scores(x, multipliers)
    overall = scores @ weights
    p = core_events(scores, overall)
    all_values = np.column_stack([scores, overall])
    grade_rates = {}
    for idx, label in enumerate(DOMAINS + ["總分"]):
        codes = grade_codes(all_values[:, idx])
        grade_rates[label] = {GRADES[g]: rate(codes == g) for g in range(7)}

    dai_ji = scores >= 10.0
    dai_xiong = scores <= -10.0
    positive = scores >= 1.8
    negative = scores <= -1.8
    important = {
        "A_恰一細項大吉_總分非大吉_至少兩細項小凶或更差": float(p[0]),
        "B_恰一細項大凶_總分非大凶_至少兩細項小吉或更好": float(p[1]),
        "C_五細項全部至少吉": float(p[2]),
        "D_五細項全部凶或大凶": float(p[3]),
        "恰一細項大吉": rate(dai_ji.sum(axis=1) == 1),
        "恰一細項大凶": rate(dai_xiong.sum(axis=1) == 1),
        "至少一細項大吉": rate(dai_ji.any(axis=1)),
        "至少一細項大凶": rate(dai_xiong.any(axis=1)),
        "細項同時存在大吉與大凶": rate(dai_ji.any(axis=1) & dai_xiong.any(axis=1)),
        "至少一細項小吉或更好且至少一細項小凶或更差": rate(positive.any(axis=1) & negative.any(axis=1)),
        "五細項全部至少小吉": rate(np.all(scores >= 1.8, axis=1)),
        "五細項全部小凶或更差": rate(np.all(scores <= -1.8, axis=1)),
        "總分大吉且至少一細項大凶": rate((overall >= 10.0) & dai_xiong.any(axis=1)),
        "總分大凶且至少一細項大吉": rate((overall <= -10.0) & dai_ji.any(axis=1)),
    }
    return {
        "name": name,
        "target_error_sum_abs_pp": float(np.sum(np.abs(p - TARGET)) * 100),
        "target_max_abs_error_pp": float(np.max(np.abs(p - TARGET)) * 100),
        "multipliers": {FAMILIES[f]: {DOMAINS[d]: float(multipliers[f, d]) for d in range(5)} for f in range(4)},
        "effective_coefficients": {FAMILIES[f]: {DOMAINS[d]: float(BASE_COEFFICIENTS[f] * multipliers[f, d]) for d in range(5)} for f in range(4)},
        "overall_weights": {DOMAINS[d]: float(weights[d]) for d in range(5)},
        "core_events": {"A": float(p[0]), "B": float(p[1]), "C": float(p[2]), "D": float(p[3])},
        "grade_rates": grade_rates,
        "important_combinations": important,
    }


def markdown_report(result: dict) -> str:
    def pct(x): return f"{x * 100:.4f}%"
    lines = [
        "# 占星係數／權重目標校準試算（1900–2100 全量驗證）",
        "",
        f"完整資料筆數：{result['rows']:,}（1900–2100 每日 × 12 星座）。",
        "",
        "目標事件：",
        "- A：恰好 1 個細項大吉、總分不是大吉、且至少 2 個細項為小凶／凶／大凶（平不算）。",
        "- B：恰好 1 個細項大凶、總分不是大凶、且至少 2 個細項為小吉／吉／大吉（平不算）。",
        "- C：五細項全部至少吉（吉或大吉）。",
        "- D：五細項全部為凶或大凶。",
        "",
        "所有候選只縮放既有四類天象貢獻（落宮、太陽星座級關係、逆行、行星相位）在各領域的係數，並調整總分五領域權重；吉凶門檻、天文輸入、相位判定與結果後處理均不改。總分權重強制每項至少 5%，不允許 0% 或 100%。",
        "",
        "## 基準版",
        "",
    ]
    base = result["baseline"]
    lines += ["| A | B | C | D |", "|---:|---:|---:|---:|", f"| {pct(base['core_events']['A'])} | {pct(base['core_events']['B'])} | {pct(base['core_events']['C'])} | {pct(base['core_events']['D'])} |", ""]
    for scheme in result["schemes"]:
        lines += [f"## {scheme['name']}", "", f"最大目標偏差：{scheme['target_max_abs_error_pp']:.3f} 個百分點；四目標總絕對偏差：{scheme['target_error_sum_abs_pp']:.3f} 個百分點。", "", "### 四個核心事件", "", "| A | B | C | D |", "|---:|---:|---:|---:|", f"| {pct(scheme['core_events']['A'])} | {pct(scheme['core_events']['B'])} | {pct(scheme['core_events']['C'])} | {pct(scheme['core_events']['D'])} |", "", "### 有效係數", "", "表內數字是原本 `落宮=8、太陽星座級關係=5、逆行=-3、行星相位=10` 經領域乘數調整後的實際係數。", "", "| 類型 | 財運 | 戀愛 | 工作／學業 | 人際 | 健康 |", "|---|---:|---:|---:|---:|---:|"]
        for fam in FAMILIES:
            e = scheme["effective_coefficients"][fam]
            lines.append(f"| {fam} | {e['財運']:.3f} | {e['戀愛']:.3f} | {e['工作／學業']:.3f} | {e['人際']:.3f} | {e['健康']:.3f} |")
        w = scheme["overall_weights"]
        lines += ["", "### 總分權重", "", "| 財運 | 戀愛 | 工作／學業 | 人際 | 健康 |", "|---:|---:|---:|---:|---:|", f"| {pct(w['財運'])} | {pct(w['戀愛'])} | {pct(w['工作／學業'])} | {pct(w['人際'])} | {pct(w['健康'])} |", "", "### 各細項與總分七級機率", "", "| 指標 | 大吉 | 吉 | 小吉 | 平 | 小凶 | 凶 | 大凶 |", "|---|---:|---:|---:|---:|---:|---:|---:|"]
        for label in DOMAINS + ["總分"]:
            g = scheme["grade_rates"][label]
            lines.append("| " + label + " | " + " | ".join(pct(g[x]) for x in GRADES) + " |")
        lines += ["", "### 重要組合", "", "| 組合 | 機率 |", "|---|---:|"]
        for key, value in scheme["important_combinations"].items():
            lines.append(f"| {key} | {pct(value)} |")
        lines.append("")
    return "\n".join(lines)


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--pattern", default="weight-features-*.bin")
    ap.add_argument("--json", default="weight-search-report.json")
    ap.add_argument("--markdown", default="weight-search-report.md")
    args = ap.parse_args()

    x = load_features(args.pattern)
    n = len(x)
    rng = np.random.default_rng(20260902)
    search_n = min(30000, n)
    refine_n = min(180000, n)
    search_idx = rng.choice(n, size=search_n, replace=False)
    refine_idx = rng.choice(n, size=refine_n, replace=False)
    xs = x[search_idx].astype(np.float64, copy=False)
    xr = x[refine_idx].astype(np.float64, copy=False)

    log_lo, log_hi = math.log(0.25), math.log(3.0)
    bounds = [(log_lo, log_hi)] * 20 + [(-2.2, 2.2)] * 5
    pool = []
    for seed in [7, 29, 83]:
        res = differential_evolution(
            lambda p: objective(p, xs),
            bounds=bounds,
            seed=seed,
            popsize=5,
            maxiter=22,
            tol=0.002,
            mutation=(0.45, 1.0),
            recombination=0.82,
            polish=False,
            updating="immediate",
            workers=1,
        )
        pool.append(res.x.copy())
        order = np.argsort(res.population_energies)
        for i in order[:12]:
            pool.append(res.population[i].copy())

    # Add local perturbations around the best search-space solutions to get several distinct fits.
    ranked_search = sorted(pool, key=lambda p: objective(p, xs))[:12]
    for center in ranked_search[:6]:
        for _ in range(50):
            candidate = center.copy()
            candidate[:20] += rng.normal(0.0, 0.06, size=20)
            candidate[:20] = np.clip(candidate[:20], log_lo, log_hi)
            candidate[20:] += rng.normal(0.0, 0.10, size=5)
            candidate[20:] = np.clip(candidate[20:], -2.2, 2.2)
            pool.append(candidate)

    # Deduplicate roughly and rank on a much larger sample.
    unique = {}
    for p in pool:
        key = tuple(np.round(p, 4))
        unique[key] = p
    refine_ranked = sorted(unique.values(), key=lambda p: objective(p, xr))[:30]

    full_summaries = []
    x64 = x.astype(np.float64, copy=False)
    for i, p in enumerate(refine_ranked):
        m, w = decode(p)
        s = summarize(f"候選 {i+1}", x64, m, w)
        s["_params"] = p.tolist()
        s["_change"] = float(np.mean(np.abs(np.log(m))) + np.mean(np.abs(w - 0.2)))
        full_summaries.append(s)
    full_summaries.sort(key=lambda s: (s["target_max_abs_error_pp"], s["target_error_sum_abs_pp"]))

    # Pick three useful proposals: absolute best, least invasive close fit, and a structurally different close fit.
    chosen = [full_summaries[0]]
    best_max = full_summaries[0]["target_max_abs_error_pp"]
    close = [s for s in full_summaries[1:] if s["target_max_abs_error_pp"] <= best_max + 0.60]
    if close:
        least = min(close, key=lambda s: s["_change"])
        chosen.append(least)
    if len(chosen) < 2:
        chosen.append(full_summaries[1])

    def vector(s):
        p = np.array(s["_params"])
        m, w = decode(p)
        return np.concatenate([np.log(m).ravel(), w * 3])
    remaining = [s for s in full_summaries if s not in chosen and s["target_max_abs_error_pp"] <= best_max + 0.90]
    if remaining:
        third = max(remaining, key=lambda s: min(np.linalg.norm(vector(s) - vector(c)) for c in chosen))
        chosen.append(third)
    while len(chosen) < 3:
        chosen.append(next(s for s in full_summaries if s not in chosen))

    for idx, s in enumerate(chosen, 1):
        s["name"] = ["方案一：最貼近四目標", "方案二：較少改動", "方案三：不同權重結構"][idx - 1]
        s.pop("_params", None)
        s.pop("_change", None)

    base_m = np.ones((4, 5), dtype=np.float64)
    base_w = np.full(5, 0.2, dtype=np.float64)
    baseline = summarize("現行基準", x64, base_m, base_w)
    result = {
        "rows": int(n),
        "targets": {"A": TARGET, "B": TARGET, "C": TARGET, "D": TARGET},
        "parameterization": {
            "family_domain_multiplier_range": [0.25, 3.0],
            "overall_weight_min": 0.05,
            "note": "No randomness, quotas, grade-threshold changes, or outcome filtering. Only existing contribution-family coefficients per domain and overall domain weights are adjusted.",
        },
        "baseline": baseline,
        "schemes": chosen,
        "top_full_candidates": [
            {k: v for k, v in s.items() if k not in {"_params", "_change", "grade_rates", "important_combinations", "multipliers", "effective_coefficients", "overall_weights"}}
            for s in full_summaries[:10]
        ],
    }
    Path(args.json).write_text(json.dumps(result, ensure_ascii=False, indent=2), encoding="utf-8")
    Path(args.markdown).write_text(markdown_report(result), encoding="utf-8")
    print(json.dumps({"rows": n, "baseline": baseline["core_events"], "schemes": [{"name": s["name"], "core": s["core_events"], "max_error_pp": s["target_max_abs_error_pp"]} for s in chosen]}, ensure_ascii=False, indent=2))


if __name__ == "__main__":
    main()
