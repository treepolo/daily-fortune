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
INDICATORS = DOMAINS + ["總分"]
GRADES = ["大吉", "吉", "小吉", "平", "小凶", "凶", "大凶"]
BASE_COEFFICIENTS = np.array([8.0, 5.0, -3.0, 10.0], dtype=np.float64)
CORE_TARGETS = np.array([0.10, 0.10, 0.075, 0.075], dtype=np.float64)
EXTREME_TARGET = 0.10
MONOTONIC_MARGIN = 0.001
MULTIPLIER_MIN = 0.005
MULTIPLIER_MAX = 30.0


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
    # Five weights sum to 100%; each is >=5% and <80%, so 0%/100% are impossible.
    overall_weights = 0.05 + 0.75 * soft
    return multipliers, overall_weights


def adjusted_scores(x: np.ndarray, multipliers: np.ndarray) -> np.ndarray:
    return np.einsum("nfk,fk->nk", x, multipliers, optimize=True)


def core_events(scores: np.ndarray, overall: np.ndarray):
    dai_ji = scores >= 10.0
    dai_xiong = scores <= -10.0
    xiao_xiong_or_worse = scores <= -1.8
    xiao_ji_or_better = scores >= 1.8

    a = (dai_ji.sum(axis=1) == 1) & (overall < 10.0) & (xiao_xiong_or_worse.sum(axis=1) >= 2)
    b = (dai_xiong.sum(axis=1) == 1) & (overall > -10.0) & (xiao_ji_or_better.sum(axis=1) >= 2)
    # C/D explicitly use only the five domain grades. "平以上" means score > -1.8;
    # "平以下" means score < +1.8. Total score is intentionally excluded.
    c = np.all(scores > -1.8, axis=1)
    d = np.all(scores < 1.8, axis=1)
    return np.array([a.mean(), b.mean(), c.mean(), d.mean()], dtype=np.float64)


def grade_codes(values: np.ndarray) -> np.ndarray:
    out = np.full(values.shape, 6, dtype=np.int8)
    out[values > -10.0] = 5
    out[values > -5.5] = 4
    out[values > -1.8] = 3
    out[values >= 1.8] = 2
    out[values >= 5.5] = 1
    out[values >= 10.0] = 0
    return out


def grade_rate_matrix(scores: np.ndarray, overall: np.ndarray) -> np.ndarray:
    values = np.column_stack([scores, overall])
    codes = grade_codes(values)
    rates = np.empty((6, 7), dtype=np.float64)
    for g in range(7):
        rates[:, g] = np.mean(codes == g, axis=0)
    return rates


def monotonic_steps(rates: np.ndarray) -> np.ndarray:
    # Positive side: 平 > 小吉 > 吉 > 大吉.
    # Negative side: 平 > 小凶 > 凶 > 大凶.
    return np.column_stack([
        rates[:, 3] - rates[:, 2],
        rates[:, 2] - rates[:, 1],
        rates[:, 1] - rates[:, 0],
        rates[:, 3] - rates[:, 4],
        rates[:, 4] - rates[:, 5],
        rates[:, 5] - rates[:, 6],
    ])


def distribution_components(rates: np.ndarray):
    extreme_error = float(np.mean(((rates[:, [0, 6]] - EXTREME_TARGET) / 0.01) ** 2))
    symmetry_pairs = rates[:, :3] - rates[:, 6:3:-1]
    symmetry_error = float(np.mean((symmetry_pairs / 0.01) ** 2))
    steps = monotonic_steps(rates)
    violations = np.maximum(0.0, MONOTONIC_MARGIN - steps)
    monotonic_error = float(np.mean((violations / 0.0025) ** 2))
    return extreme_error, symmetry_error, monotonic_error


def objective(params: np.ndarray, x: np.ndarray) -> float:
    multipliers, weights = decode(params)
    scores = adjusted_scores(x, multipliers)
    overall = scores @ weights
    probs = core_events(scores, overall)
    rates = grade_rate_matrix(scores, overall)

    # Core event errors are measured in one-percentage-point units.
    core_error = float(np.mean(((probs - CORE_TARGETS) / 0.01) ** 2))
    extreme_error, symmetry_error, monotonic_error = distribution_components(rates)

    # Shape constraints are deliberately strong: a candidate cannot win by making one
    # domain permanently auspicious/inauspicious or by collapsing an extreme grade.
    error = (
        3.0 * core_error
        + 2.0 * extreme_error
        + 1.5 * symmetry_error
        + 8.0 * monotonic_error
    )
    # When candidates fit similarly, prefer less violent coefficient movement.
    regularity = 0.003 * float(np.mean(np.log(multipliers) ** 2))
    return error + regularity


def rate(mask: np.ndarray) -> float:
    return float(np.mean(mask))


def summarize(name: str, x: np.ndarray, multipliers: np.ndarray, weights: np.ndarray):
    scores = adjusted_scores(x, multipliers)
    overall = scores @ weights
    p = core_events(scores, overall)
    rates = grade_rate_matrix(scores, overall)
    steps = monotonic_steps(rates)

    grade_rates = {
        label: {GRADES[g]: float(rates[i, g]) for g in range(7)}
        for i, label in enumerate(INDICATORS)
    }
    symmetry_gap = np.abs(rates[:, :3] - rates[:, 6:3:-1])
    extreme_gap = np.abs(rates[:, [0, 6]] - EXTREME_TARGET)

    dai_ji = scores >= 10.0
    dai_xiong = scores <= -10.0
    positive = scores >= 1.8
    negative = scores <= -1.8
    important = {
        "A_恰一細項大吉_總分非大吉_至少兩細項小凶或更差": float(p[0]),
        "B_恰一細項大凶_總分非大凶_至少兩細項小吉或更好": float(p[1]),
        "C_五細項全部平以上_不含總分": float(p[2]),
        "D_五細項全部平以下_不含總分": float(p[3]),
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

    core_abs_pp = np.abs(p - CORE_TARGETS) * 100.0
    constraint_score = objective_from_summary(p, rates, multipliers)
    return {
        "name": name,
        "constraint_score": float(constraint_score),
        "target_error_sum_abs_pp": float(core_abs_pp.sum()),
        "target_max_abs_error_pp": float(core_abs_pp.max()),
        "max_extreme_error_pp": float(extreme_gap.max() * 100.0),
        "max_symmetry_gap_pp": float(symmetry_gap.max() * 100.0),
        "min_monotonic_step_pp": float(steps.min() * 100.0),
        "all_monotonic": bool(np.all(steps > 0.0)),
        "multipliers": {FAMILIES[f]: {DOMAINS[d]: float(multipliers[f, d]) for d in range(5)} for f in range(4)},
        "effective_coefficients": {FAMILIES[f]: {DOMAINS[d]: float(BASE_COEFFICIENTS[f] * multipliers[f, d]) for d in range(5)} for f in range(4)},
        "overall_weights": {DOMAINS[d]: float(weights[d]) for d in range(5)},
        "core_events": {"A": float(p[0]), "B": float(p[1]), "C": float(p[2]), "D": float(p[3])},
        "grade_rates": grade_rates,
        "important_combinations": important,
    }


def objective_from_summary(probs: np.ndarray, rates: np.ndarray, multipliers: np.ndarray) -> float:
    core_error = float(np.mean(((probs - CORE_TARGETS) / 0.01) ** 2))
    extreme_error, symmetry_error, monotonic_error = distribution_components(rates)
    regularity = 0.003 * float(np.mean(np.log(multipliers) ** 2))
    return 3.0 * core_error + 2.0 * extreme_error + 1.5 * symmetry_error + 8.0 * monotonic_error + regularity


def markdown_report(result: dict) -> str:
    def pct(value):
        return f"{value * 100:.4f}%"

    lines = [
        "# 占星係數／權重多重約束校準（1900–2100 全量驗證）",
        "",
        f"完整資料筆數：{result['rows']:,}（1900–2100 每日 × 12 星座）。",
        "",
        "## 約束",
        "",
        "- A：約 10%：恰好 1 個細項大吉、總分不是大吉、且至少 2 個細項為小凶／凶／大凶。",
        "- B：約 10%：恰好 1 個細項大凶、總分不是大凶、且至少 2 個細項為小吉／吉／大吉。",
        "- C：約 7.5%：五個細項全部為平或更好；不含總分。",
        "- D：約 7.5%：五個細項全部為平或更差；不含總分。",
        "- 五細項與總分各自的大吉、大凶都逼近 10%。",
        "- 每個項目的大吉↔大凶、吉↔凶、小吉↔小凶機率逼近對稱。",
        "- 每個項目兩側都必須維持：平 > 小吉／小凶 > 吉／凶 > 大吉／大凶。",
        "",
        "只調整既有四類天象貢獻在五領域的係數乘數與總分五領域權重；吉凶門檻、天文輸入、相位規則、結果後處理都不改。總分權重每項至少 5%，不允許 0% 或 100%。係數乘數研究範圍放寬，但仍嚴格大於 0。",
        "",
        "## 基準版",
        "",
    ]

    base = result["baseline"]
    lines += [
        "| A | B | C | D | 最大極端偏差 | 最大對稱差 | 最小單調階差 |",
        "|---:|---:|---:|---:|---:|---:|---:|",
        f"| {pct(base['core_events']['A'])} | {pct(base['core_events']['B'])} | {pct(base['core_events']['C'])} | {pct(base['core_events']['D'])} | {base['max_extreme_error_pp']:.3f}pp | {base['max_symmetry_gap_pp']:.3f}pp | {base['min_monotonic_step_pp']:.3f}pp |",
        "",
    ]

    for scheme in result["schemes"]:
        lines += [
            f"## {scheme['name']}",
            "",
            f"綜合約束分數：{scheme['constraint_score']:.4f}；核心事件最大偏差 {scheme['target_max_abs_error_pp']:.3f}pp；極端等級最大偏差 {scheme['max_extreme_error_pp']:.3f}pp；最大左右對稱差 {scheme['max_symmetry_gap_pp']:.3f}pp；最小單調階差 {scheme['min_monotonic_step_pp']:.3f}pp。",
            "",
            "### 四個核心事件",
            "",
            "| A | B | C | D |",
            "|---:|---:|---:|---:|",
            f"| {pct(scheme['core_events']['A'])} | {pct(scheme['core_events']['B'])} | {pct(scheme['core_events']['C'])} | {pct(scheme['core_events']['D'])} |",
            "",
            "### 各細項與總分七級機率",
            "",
            "| 指標 | 大吉 | 吉 | 小吉 | 平 | 小凶 | 凶 | 大凶 |",
            "|---|---:|---:|---:|---:|---:|---:|---:|",
        ]
        for label in INDICATORS:
            g = scheme["grade_rates"][label]
            lines.append("| " + label + " | " + " | ".join(pct(g[x]) for x in GRADES) + " |")

        lines += [
            "",
            "### 有效係數",
            "",
            "| 類型 | 財運 | 戀愛 | 工作／學業 | 人際 | 健康 |",
            "|---|---:|---:|---:|---:|---:|",
        ]
        for fam in FAMILIES:
            e = scheme["effective_coefficients"][fam]
            lines.append(f"| {fam} | {e['財運']:.3f} | {e['戀愛']:.3f} | {e['工作／學業']:.3f} | {e['人際']:.3f} | {e['健康']:.3f} |")

        w = scheme["overall_weights"]
        lines += [
            "",
            "### 總分權重",
            "",
            "| 財運 | 戀愛 | 工作／學業 | 人際 | 健康 |",
            "|---:|---:|---:|---:|---:|",
            f"| {pct(w['財運'])} | {pct(w['戀愛'])} | {pct(w['工作／學業'])} | {pct(w['人際'])} | {pct(w['健康'])} |",
            "",
            "### 重要組合",
            "",
            "| 組合 | 機率 |",
            "|---|---:|",
        ]
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
    rng = np.random.default_rng(20260903)
    search_n = min(24000, n)
    refine_n = min(180000, n)
    search_idx = rng.choice(n, size=search_n, replace=False)
    refine_idx = rng.choice(n, size=refine_n, replace=False)
    xs = x[search_idx].astype(np.float64, copy=False)
    xr = x[refine_idx].astype(np.float64, copy=False)

    log_lo, log_hi = math.log(MULTIPLIER_MIN), math.log(MULTIPLIER_MAX)
    bounds = [(log_lo, log_hi)] * 20 + [(-3.5, 3.5)] * 5
    pool = []

    for seed in [7, 29, 83, 131]:
        res = differential_evolution(
            lambda p: objective(p, xs),
            bounds=bounds,
            seed=seed,
            popsize=7,
            maxiter=42,
            tol=0.0015,
            mutation=(0.40, 1.10),
            recombination=0.88,
            polish=False,
            updating="immediate",
            workers=1,
        )
        pool.append(res.x.copy())
        order = np.argsort(res.population_energies)
        for i in order[:20]:
            pool.append(res.population[i].copy())

    ranked_search = sorted(pool, key=lambda p: objective(p, xs))[:24]
    for center in ranked_search[:12]:
        for _ in range(70):
            candidate = center.copy()
            candidate[:20] += rng.normal(0.0, 0.12, size=20)
            candidate[:20] = np.clip(candidate[:20], log_lo, log_hi)
            candidate[20:] += rng.normal(0.0, 0.16, size=5)
            candidate[20:] = np.clip(candidate[20:], -3.5, 3.5)
            pool.append(candidate)

    unique = {}
    for p in pool:
        unique[tuple(np.round(p, 4))] = p
    refine_ranked = sorted(unique.values(), key=lambda p: objective(p, xr))[:50]

    full_summaries = []
    x64 = x.astype(np.float64, copy=False)
    for i, p in enumerate(refine_ranked):
        multipliers, weights = decode(p)
        s = summarize(f"候選 {i + 1}", x64, multipliers, weights)
        s["_params"] = p.tolist()
        s["_change"] = float(np.mean(np.abs(np.log(multipliers))) + np.mean(np.abs(weights - 0.2)))
        full_summaries.append(s)

    full_summaries.sort(key=lambda s: (s["constraint_score"], s["target_max_abs_error_pp"]))
    chosen = [full_summaries[0]]

    close = [s for s in full_summaries[1:] if s["constraint_score"] <= full_summaries[0]["constraint_score"] * 1.20 + 2.0]
    if close:
        chosen.append(min(close, key=lambda s: s["_change"]))
    if len(chosen) < 2:
        chosen.append(full_summaries[1])

    def vector(summary):
        p = np.array(summary["_params"])
        multipliers, weights = decode(p)
        return np.concatenate([np.log(multipliers).ravel(), weights * 3.0])

    remaining = [s for s in full_summaries if s not in chosen and s["constraint_score"] <= full_summaries[0]["constraint_score"] * 1.35 + 4.0]
    if remaining:
        chosen.append(max(remaining, key=lambda s: min(np.linalg.norm(vector(s) - vector(c)) for c in chosen)))
    while len(chosen) < 3:
        chosen.append(next(s for s in full_summaries if s not in chosen))

    for idx, s in enumerate(chosen, 1):
        s["name"] = ["方案一：多重約束最佳", "方案二：較少改動", "方案三：不同係數結構"][idx - 1]
        s.pop("_params", None)
        s.pop("_change", None)

    base_m = np.ones((4, 5), dtype=np.float64)
    base_w = np.full(5, 0.2, dtype=np.float64)
    baseline = summarize("現行基準", x64, base_m, base_w)

    result = {
        "rows": int(n),
        "targets": {
            "A": 0.10,
            "B": 0.10,
            "C": 0.075,
            "D": 0.075,
            "per_indicator_dai_ji": EXTREME_TARGET,
            "per_indicator_dai_xiong": EXTREME_TARGET,
            "symmetry": ["大吉≈大凶", "吉≈凶", "小吉≈小凶"],
            "ordering": "平 > 小吉/小凶 > 吉/凶 > 大吉/大凶",
        },
        "parameterization": {
            "family_domain_multiplier_range": [MULTIPLIER_MIN, MULTIPLIER_MAX],
            "overall_weight_min": 0.05,
            "overall_weight_max_exclusive": 0.80,
            "note": "No randomness, quotas, grade-threshold changes, or outcome filtering. Only existing contribution-family coefficients per domain and overall domain weights are adjusted.",
        },
        "baseline": baseline,
        "schemes": chosen,
        "top_full_candidates": [
            {k: v for k, v in s.items() if k not in {"_params", "_change", "multipliers", "effective_coefficients", "overall_weights", "important_combinations"}}
            for s in full_summaries[:10]
        ],
    }

    Path(args.json).write_text(json.dumps(result, ensure_ascii=False, indent=2), encoding="utf-8")
    Path(args.markdown).write_text(markdown_report(result), encoding="utf-8")
    print(json.dumps({
        "rows": n,
        "baseline": baseline["core_events"],
        "schemes": [
            {
                "name": s["name"],
                "core": s["core_events"],
                "max_core_error_pp": s["target_max_abs_error_pp"],
                "max_extreme_error_pp": s["max_extreme_error_pp"],
                "max_symmetry_gap_pp": s["max_symmetry_gap_pp"],
                "min_monotonic_step_pp": s["min_monotonic_step_pp"],
                "all_monotonic": s["all_monotonic"],
                "constraint_score": s["constraint_score"],
            }
            for s in chosen
        ],
    }, ensure_ascii=False, indent=2))


if __name__ == "__main__":
    main()
