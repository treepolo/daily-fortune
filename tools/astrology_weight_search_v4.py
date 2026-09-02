#!/usr/bin/env python3
"""Fine-grained astrology calibration v4.

Differences from v3:
- Overall remains a fixed arithmetic mean: 20% for each of the five domains.
- The continuous astrology adjustment channels remain planet×domain, house×domain, aspect-type×domain.
- DAI_JI and DAI_XIONG are fixed at 10% per displayed item.
- The middle grade probabilities are no longer hard-coded. For each displayed item the search chooses
  JI/XIONG rate g and XIAO_JI/XIAO_XIONG rate m; PING is the residual.
- Exact distribution symmetry is enforced, together with PING > m > g > 10%.
- Numeric score thresholds are empirical quantiles and may be different for every item.

No displayed item's middle-grade profile is fixed to 10/13/17/20/17/13/10.
"""

from __future__ import annotations

import argparse
import json
import math
from dataclasses import dataclass
from pathlib import Path

import numpy as np

import astrology_weight_search_v3 as base

DOMAIN_COUNT = 5
ITEM_COUNT = DOMAIN_COUNT + 1
OVERALL_INDEX = DOMAIN_COUNT
STAR_COUNT = base.PARAM_COUNT
STAR_MIN = -0.49
STAR_MAX = 4.00
EXTREME_RATE = 0.10
PROFILE_G_MIN = 0.101
PROFILE_G_MAX = 0.158
PROFILE_M_MIN = 0.103
PROFILE_M_MAX = 0.198
CORE_TARGETS = np.array([0.10, 0.10, 0.075, 0.075], dtype=np.float64)
CORE_SCALES = np.array([0.010, 0.010, 0.005, 0.005], dtype=np.float64)
STAR_REGULARITY = 0.003
# Search starting point only; all six displayed items, including overall, are free to move.
DEFAULT_PROFILE = np.tile(np.array([0.13, 0.17], dtype=np.float64), (ITEM_COUNT, 1))


@dataclass
class SearchState:
    stars: np.ndarray
    profile: np.ndarray
    scores: np.ndarray
    overall: np.ndarray
    codes: np.ndarray
    overall_codes: np.ndarray
    dai_ji_count: np.ndarray
    dai_xiong_count: np.ndarray
    negative_count: np.ndarray
    positive_count: np.ndarray
    ge_ping_count: np.ndarray
    le_ping_count: np.ndarray
    probs: np.ndarray
    sumsq: float
    objective: float


def valid_profile(g: float, m: float) -> bool:
    ping = 0.80 - 2.0 * (g + m)
    return EXTREME_RATE < g < m < ping


def profile_rates(g: float, m: float) -> np.ndarray:
    if not valid_profile(g, m):
        raise ValueError(f"invalid grade profile g={g} m={m}")
    ping = 0.80 - 2.0 * (g + m)
    return np.array([EXTREME_RATE, g, m, ping, m, g, EXTREME_RATE], dtype=np.float64)


def profile_quantiles(g: float, m: float) -> np.ndarray:
    # Low-to-high cumulative cut points for 大凶/凶/小凶/平/小吉/吉/大吉.
    s = EXTREME_RATE + g + m
    return np.array([
        EXTREME_RATE,
        EXTREME_RATE + g,
        s,
        1.0 - s,
        1.0 - (EXTREME_RATE + g),
        1.0 - EXTREME_RATE,
    ], dtype=np.float64)


def thresholds(values: np.ndarray, g: float, m: float) -> np.ndarray:
    return np.quantile(values, profile_quantiles(g, m))


def codes(values: np.ndarray, g: float, m: float) -> np.ndarray:
    return np.searchsorted(thresholds(values, g, m), values, side="right").astype(np.int8)


def core_probs(
    dj: np.ndarray,
    dx: np.ndarray,
    neg: np.ndarray,
    pos: np.ndarray,
    ge: np.ndarray,
    le: np.ndarray,
    overall_codes: np.ndarray,
) -> np.ndarray:
    a = (dj == 1) & (overall_codes < 6) & (neg >= 2)
    b = (dx == 1) & (overall_codes > 0) & (pos >= 2)
    c = ge == DOMAIN_COUNT
    d = le == DOMAIN_COUNT
    return np.array([a.mean(), b.mean(), c.mean(), d.mean()], dtype=np.float64)


def objective_value(probs: np.ndarray, sumsq: float) -> float:
    return float(np.sum(((probs - CORE_TARGETS) / CORE_SCALES) ** 2) + STAR_REGULARITY * sumsq / STAR_COUNT)


def build_state(x: np.ndarray, stars: np.ndarray, profile: np.ndarray) -> SearchState:
    scores = base.scores_from_params(x, stars)
    overall = scores.mean(axis=1)
    item_codes = np.column_stack([codes(scores[:, d], *profile[d]) for d in range(DOMAIN_COUNT)])
    overall_codes = codes(overall, *profile[OVERALL_INDEX])
    dj = (item_codes == 6).sum(axis=1).astype(np.int8)
    dx = (item_codes == 0).sum(axis=1).astype(np.int8)
    neg = (item_codes <= 2).sum(axis=1).astype(np.int8)
    pos = (item_codes >= 4).sum(axis=1).astype(np.int8)
    ge = (item_codes >= 3).sum(axis=1).astype(np.int8)
    le = (item_codes <= 3).sum(axis=1).astype(np.int8)
    probs = core_probs(dj, dx, neg, pos, ge, le, overall_codes)
    sumsq = float(np.dot(stars, stars))
    return SearchState(
        stars=stars.copy(), profile=profile.copy(), scores=scores, overall=overall,
        codes=item_codes, overall_codes=overall_codes,
        dai_ji_count=dj, dai_xiong_count=dx, negative_count=neg, positive_count=pos,
        ge_ping_count=ge, le_ping_count=le, probs=probs, sumsq=sumsq,
        objective=objective_value(probs, sumsq),
    )


def try_star(state: SearchState, x: np.ndarray, j: int, proposed: float):
    proposed = float(np.clip(proposed, STAR_MIN, STAR_MAX))
    old = float(state.stars[j])
    delta = proposed - old
    if abs(delta) < 1e-12:
        return None
    d = j % DOMAIN_COUNT
    feature = x[:, j]
    old_codes = state.codes[:, d]
    new_domain = state.scores[:, d] + feature * delta
    new_codes = codes(new_domain, *state.profile[d])
    new_overall = state.overall + feature * (delta / DOMAIN_COUNT)
    new_overall_codes = codes(new_overall, *state.profile[OVERALL_INDEX])

    dj = state.dai_ji_count + (new_codes == 6).astype(np.int8) - (old_codes == 6).astype(np.int8)
    dx = state.dai_xiong_count + (new_codes == 0).astype(np.int8) - (old_codes == 0).astype(np.int8)
    neg = state.negative_count + (new_codes <= 2).astype(np.int8) - (old_codes <= 2).astype(np.int8)
    pos = state.positive_count + (new_codes >= 4).astype(np.int8) - (old_codes >= 4).astype(np.int8)
    ge = state.ge_ping_count + (new_codes >= 3).astype(np.int8) - (old_codes >= 3).astype(np.int8)
    le = state.le_ping_count + (new_codes <= 3).astype(np.int8) - (old_codes <= 3).astype(np.int8)
    probs = core_probs(dj, dx, neg, pos, ge, le, new_overall_codes)
    sumsq = state.sumsq - old * old + proposed * proposed
    obj = objective_value(probs, sumsq)
    return (obj, proposed, d, new_domain, new_codes, new_overall, new_overall_codes, dj, dx, neg, pos, ge, le, probs, sumsq)


def accept_star(state: SearchState, j: int, candidate) -> None:
    (obj, proposed, d, new_domain, new_codes, new_overall, new_overall_codes,
     dj, dx, neg, pos, ge, le, probs, sumsq) = candidate
    state.stars[j] = proposed
    state.scores[:, d] = new_domain
    state.codes[:, d] = new_codes
    state.overall = new_overall
    state.overall_codes = new_overall_codes
    state.dai_ji_count = dj
    state.dai_xiong_count = dx
    state.negative_count = neg
    state.positive_count = pos
    state.ge_ping_count = ge
    state.le_ping_count = le
    state.probs = probs
    state.sumsq = float(sumsq)
    state.objective = float(obj)


def try_profile(state: SearchState, d: int, component: int, proposed: float):
    new_pair = state.profile[d].copy()
    if component == 0:
        proposed = float(np.clip(proposed, PROFILE_G_MIN, PROFILE_G_MAX))
    else:
        proposed = float(np.clip(proposed, PROFILE_M_MIN, PROFILE_M_MAX))
    new_pair[component] = proposed
    if not valid_profile(*new_pair):
        return None

    if d == OVERALL_INDEX:
        new_overall_codes = codes(state.overall, *new_pair)
        probs = core_probs(
            state.dai_ji_count,
            state.dai_xiong_count,
            state.negative_count,
            state.positive_count,
            state.ge_ping_count,
            state.le_ping_count,
            new_overall_codes,
        )
        obj = objective_value(probs, state.sumsq)
        return (obj, new_pair, new_overall_codes, probs)

    old_codes = state.codes[:, d]
    new_codes = codes(state.scores[:, d], *new_pair)
    dj = state.dai_ji_count + (new_codes == 6).astype(np.int8) - (old_codes == 6).astype(np.int8)
    dx = state.dai_xiong_count + (new_codes == 0).astype(np.int8) - (old_codes == 0).astype(np.int8)
    neg = state.negative_count + (new_codes <= 2).astype(np.int8) - (old_codes <= 2).astype(np.int8)
    pos = state.positive_count + (new_codes >= 4).astype(np.int8) - (old_codes >= 4).astype(np.int8)
    ge = state.ge_ping_count + (new_codes >= 3).astype(np.int8) - (old_codes >= 3).astype(np.int8)
    le = state.le_ping_count + (new_codes <= 3).astype(np.int8) - (old_codes <= 3).astype(np.int8)
    probs = core_probs(dj, dx, neg, pos, ge, le, state.overall_codes)
    obj = objective_value(probs, state.sumsq)
    return (obj, new_pair, new_codes, dj, dx, neg, pos, ge, le, probs)


def accept_profile(state: SearchState, d: int, candidate) -> None:
    if d == OVERALL_INDEX:
        obj, new_pair, new_overall_codes, probs = candidate
        state.profile[d] = new_pair
        state.overall_codes = new_overall_codes
        state.probs = probs
        state.objective = float(obj)
        return

    obj, new_pair, new_codes, dj, dx, neg, pos, ge, le, probs = candidate
    state.profile[d] = new_pair
    state.codes[:, d] = new_codes
    state.dai_ji_count = dj
    state.dai_xiong_count = dx
    state.negative_count = neg
    state.positive_count = pos
    state.ge_ping_count = ge
    state.le_ping_count = le
    state.probs = probs
    state.objective = float(obj)


def search(
    x: np.ndarray,
    seed: int,
    iterations: int,
    init_stars: np.ndarray | None = None,
    init_profile: np.ndarray | None = None,
) -> tuple[np.ndarray, np.ndarray, float, np.ndarray]:
    rng = np.random.default_rng(seed)
    stars = np.zeros(STAR_COUNT, dtype=np.float64) if init_stars is None else np.clip(init_stars, STAR_MIN, STAR_MAX).astype(np.float64, copy=True)
    profile = DEFAULT_PROFILE.copy() if init_profile is None else init_profile.copy()
    state = build_state(x, stars, profile)
    best_obj = state.objective
    best_stars = state.stars.copy()
    best_profile = state.profile.copy()
    best_probs = state.probs.copy()

    phases = [
        (0.60, 0.018, 0.25),
        (0.30, 0.010, 0.09),
        (0.12, 0.005, 0.025),
        (0.050, 0.0025, 0.006),
    ]
    per_phase = max(1, iterations // len(phases))
    for star_sigma, profile_sigma, temperature in phases:
        for _ in range(per_phase):
            if rng.random() < 0.82:
                j = int(rng.integers(STAR_COUNT))
                candidate = try_star(state, x, j, state.stars[j] + rng.normal(0.0, star_sigma))
                if candidate is not None:
                    delta_obj = float(candidate[0] - state.objective)
                    if delta_obj <= 0 or rng.random() < math.exp(-delta_obj / max(temperature, 1e-9)):
                        accept_star(state, j, candidate)
            else:
                d = int(rng.integers(ITEM_COUNT))
                component = int(rng.integers(2))
                candidate = try_profile(state, d, component, state.profile[d, component] + rng.normal(0.0, profile_sigma))
                if candidate is not None:
                    delta_obj = float(candidate[0] - state.objective)
                    if delta_obj <= 0 or rng.random() < math.exp(-delta_obj / max(temperature, 1e-9)):
                        accept_profile(state, d, candidate)
            if state.objective < best_obj:
                best_obj = state.objective
                best_stars = state.stars.copy()
                best_profile = state.profile.copy()
                best_probs = state.probs.copy()

    # Greedy polish from the best state.
    state = build_state(x, best_stars, best_profile)
    for star_step, profile_step in ((0.040, 0.0030), (0.020, 0.0015), (0.010, 0.0008)):
        for j in rng.permutation(STAR_COUNT):
            candidate_best = None
            for direction in (-1.0, 1.0):
                c = try_star(state, x, int(j), state.stars[j] + direction * star_step)
                if c is not None and c[0] < state.objective and (candidate_best is None or c[0] < candidate_best[0]):
                    candidate_best = c
            if candidate_best is not None:
                accept_star(state, int(j), candidate_best)
        for d in rng.permutation(ITEM_COUNT):
            for component in (0, 1):
                candidate_best = None
                for direction in (-1.0, 1.0):
                    c = try_profile(state, int(d), component, state.profile[d, component] + direction * profile_step)
                    if c is not None and c[0] < state.objective and (candidate_best is None or c[0] < candidate_best[0]):
                        candidate_best = c
                if candidate_best is not None:
                    accept_profile(state, int(d), candidate_best)
    return state.stars.copy(), state.profile.copy(), state.objective, state.probs.copy()


def read_seed_stars(path: str) -> list[np.ndarray]:
    p = Path(path)
    if not p.exists():
        return []
    raw = json.loads(p.read_text(encoding="utf-8"))
    seeds = []
    for entry in raw.get("seeds", []):
        values = np.array(entry.get("params", []), dtype=np.float64)
        if values.shape == (STAR_COUNT,):
            seeds.append(values)
    return seeds


def evaluate(
    x: np.ndarray,
    stars: np.ndarray,
    profile: np.ndarray,
    name: str,
    production_thresholds: bool = False,
) -> dict:
    scores = base.scores_from_params(x, stars)
    overall = scores.mean(axis=1)
    if production_thresholds:
        item_codes = np.column_stack([base.current_grade_codes(scores[:, d]) for d in range(DOMAIN_COUNT)])
        overall_codes = base.current_grade_codes(overall)
        domain_thresholds = np.tile(np.array([-10.0, -5.5, -1.8, 1.8, 5.5, 10.0]), (DOMAIN_COUNT, 1))
        overall_threshold_values = np.array([-10.0, -5.5, -1.8, 1.8, 5.5, 10.0])
        rates_by_domain = None
    else:
        item_codes = np.column_stack([codes(scores[:, d], *profile[d]) for d in range(DOMAIN_COUNT)])
        overall_codes = codes(overall, *profile[OVERALL_INDEX])
        domain_thresholds = np.vstack([thresholds(scores[:, d], *profile[d]) for d in range(DOMAIN_COUNT)])
        overall_threshold_values = thresholds(overall, *profile[OVERALL_INDEX])
        rates_by_domain = [profile_rates(*profile[d]) for d in range(DOMAIN_COUNT)]

    dj = (item_codes == 6).sum(axis=1)
    dx = (item_codes == 0).sum(axis=1)
    neg = (item_codes <= 2).sum(axis=1)
    pos = (item_codes >= 4).sum(axis=1)
    ge = (item_codes >= 3).sum(axis=1)
    le = (item_codes <= 3).sum(axis=1)
    probs = core_probs(dj, dx, neg, pos, ge, le, overall_codes)
    all_codes = np.column_stack([item_codes, overall_codes])
    grade_rates = {
        item: {grade: base.rate(all_codes[:, i] == code) for code, grade in enumerate(base.GRADES_LOW_TO_HIGH)}
        for i, item in enumerate(base.ITEMS)
    }
    ping = item_codes == 3
    dai_ji = item_codes == 6
    dai_xiong = item_codes == 0
    positive = item_codes >= 4
    negative = item_codes <= 2
    important = {
        "A_恰一細項大吉_總分非大吉_至少兩細項小凶或更差": float(probs[0]),
        "B_恰一細項大凶_總分非大凶_至少兩細項小吉或更好": float(probs[1]),
        "C_五細項全部至少平": float(probs[2]),
        "D_五細項全部至多平": float(probs[3]),
        "C與D重疊_五細項全部為平": base.rate(np.all(ping, axis=1)),
        "恰一細項大吉": base.rate(dai_ji.sum(axis=1) == 1),
        "恰一細項大凶": base.rate(dai_xiong.sum(axis=1) == 1),
        "至少一細項大吉": base.rate(dai_ji.any(axis=1)),
        "至少一細項大凶": base.rate(dai_xiong.any(axis=1)),
        "細項同時存在大吉與大凶": base.rate(dai_ji.any(axis=1) & dai_xiong.any(axis=1)),
        "至少一細項小吉或更好且至少一細項小凶或更差": base.rate(positive.any(axis=1) & negative.any(axis=1)),
        "五細項全部至少小吉": base.rate(np.all(positive, axis=1)),
        "五細項全部小凶或更差": base.rate(np.all(negative, axis=1)),
        "總分大吉且至少一細項大凶": base.rate((overall_codes == 6) & dai_xiong.any(axis=1)),
        "總分大凶且至少一細項大吉": base.rate((overall_codes == 0) & dai_ji.any(axis=1)),
    }
    corr = np.corrcoef(scores, rowvar=False)
    offdiag = corr[np.triu_indices(DOMAIN_COUNT, 1)]
    threshold_map = {base.DOMAINS[d]: [float(v) for v in domain_thresholds[d]] for d in range(DOMAIN_COUNT)}
    threshold_map["總分"] = [float(v) for v in overall_threshold_values]

    body_delta = stars[:base.BODY_COLUMNS].reshape(base.BODY_COUNT, DOMAIN_COUNT)
    house_delta = stars[base.BODY_COLUMNS:base.BODY_COLUMNS + base.HOUSE_COLUMNS].reshape(base.HOUSE_COUNT, DOMAIN_COUNT)
    aspect_delta = stars[base.BODY_COLUMNS + base.HOUSE_COLUMNS:].reshape(base.ASPECT_COUNT, DOMAIN_COUNT)
    profile_map = {}
    if rates_by_domain is not None:
        for d, item in enumerate(base.DOMAINS):
            profile_map[item] = {grade: float(rates_by_domain[d][code]) for code, grade in enumerate(base.GRADES_LOW_TO_HIGH)}
        overall_rates = profile_rates(*profile[OVERALL_INDEX])
        profile_map["總分"] = {grade: float(overall_rates[code]) for code, grade in enumerate(base.GRADES_LOW_TO_HIGH)}

    return {
        "name": name,
        "core_events": {"A": float(probs[0]), "B": float(probs[1]), "C": float(probs[2]), "D": float(probs[3])},
        "core_sum_abs_error_pp": float(np.sum(np.abs(probs - CORE_TARGETS)) * 100),
        "core_max_abs_error_pp": float(np.max(np.abs(probs - CORE_TARGETS)) * 100),
        "grade_rates": grade_rates,
        "target_grade_profile": profile_map,
        "thresholds_low_to_high": threshold_map,
        "important_combinations": important,
        "continuous_score_correlation": {
            "matrix": [[float(v) for v in row] for row in corr],
            "mean_pairwise": float(np.mean(offdiag)),
            "mean_abs_pairwise": float(np.mean(np.abs(offdiag))),
        },
        "parameter_adjustments": {
            "body_domain_multiplier": {
                base.BODIES[b]: {base.DOMAINS[d]: float(1.0 + body_delta[b, d]) for d in range(DOMAIN_COUNT)}
                for b in range(base.BODY_COUNT)
            },
            "house_domain_additive_delta": {
                str(h + 1): {base.DOMAINS[d]: float(house_delta[h, d]) for d in range(DOMAIN_COUNT)}
                for h in range(base.HOUSE_COUNT)
            },
            "aspect_domain_additive_delta": {
                base.ASPECTS[a]: {base.DOMAINS[d]: float(aspect_delta[a, d]) for d in range(DOMAIN_COUNT)}
                for a in range(base.ASPECT_COUNT)
            },
        },
        "parameter_abs_mean": float(np.mean(np.abs(stars))),
        "parameter_min": float(np.min(stars)),
        "parameter_max": float(np.max(stars)),
    }


def markdown(result: dict) -> str:
    pct = base.pct
    lines = [
        "# 占星細粒度參數搜尋 v4（六項皆可變中間等級分布）",
        "",
        f"完整資料：{result['rows']:,} 筆（1900–2100 每日 × 12 星座）。",
        "",
        "- 總分計算固定五細項各 20%，不可搜尋。",
        "- 大吉與大凶固定各約 10%。",
        "- 五個細項與總分的吉/凶、小吉/小凶、平比例都可獨立搜尋；強制左右對稱且 `平 > 小吉/小凶 > 吉/凶 > 大吉/大凶`。",
        "- 六個實際分數切點依各項完整分數分布的分位數產生，因此不同項目可以有完全不同數值尺度。",
        "- 星象連續分數搜尋通道：行星×領域、宮位×領域、相位種類×領域；原始因子方向不翻轉。",
        "",
    ]
    for key in ("production_baseline", "threshold_only_baseline"):
        s = result[key]
        lines += [f"## {s['name']}", "", "| A | B | C | D | 最大偏差 |", "|---:|---:|---:|---:|---:|",
                  f"| {pct(s['core_events']['A'])} | {pct(s['core_events']['B'])} | {pct(s['core_events']['C'])} | {pct(s['core_events']['D'])} | {s['core_max_abs_error_pp']:.3f} pp |", ""]
    for s in result["schemes"]:
        lines += [f"## {s['name']}", "", f"最大核心偏差 **{s['core_max_abs_error_pp']:.3f} pp**；四目標總絕對偏差 {s['core_sum_abs_error_pp']:.3f} pp；平均兩兩連續分數相關 {s['continuous_score_correlation']['mean_pairwise']:.4f}。", "",
                  "### 核心事件", "", "| A | B | C | D |", "|---:|---:|---:|---:|",
                  f"| {pct(s['core_events']['A'])} | {pct(s['core_events']['B'])} | {pct(s['core_events']['C'])} | {pct(s['core_events']['D'])} |", "",
                  "### 七級機率", "", "| 項目 | 大吉 | 吉 | 小吉 | 平 | 小凶 | 凶 | 大凶 |", "|---|---:|---:|---:|---:|---:|---:|---:|"]
        for item in base.ITEMS:
            g = s["grade_rates"][item]
            lines.append("| " + item + " | " + " | ".join(pct(g[x]) for x in base.GRADES_HIGH_TO_LOW) + " |")
        lines += ["", "### 六個分數切點", "", "| 項目 | 大凶/凶 | 凶/小凶 | 小凶/平 | 平/小吉 | 小吉/吉 | 吉/大吉 |", "|---|---:|---:|---:|---:|---:|---:|"]
        for item in base.ITEMS:
            t = s["thresholds_low_to_high"][item]
            lines.append(f"| {item} | " + " | ".join(f"{v:.4f}" for v in t) + " |")
        lines += ["", "### 重要組合", "", "| 組合 | 機率 |", "|---|---:|"]
        for k, v in s["important_combinations"].items():
            lines.append(f"| {k} | {pct(v)} |")
        lines += ["", "### 五細項連續分數相關矩陣", "", "| | 財運 | 戀愛 | 工作／學業 | 人際 | 健康 |", "|---|---:|---:|---:|---:|---:|"]
        matrix = s["continuous_score_correlation"]["matrix"]
        for i, label in enumerate(base.DOMAINS):
            lines.append(f"| {label} | " + " | ".join(f"{matrix[i][j]:.3f}" for j in range(DOMAIN_COUNT)) + " |")
        lines.append("")
    return "\n".join(lines)


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--pattern", default="weight-v3-features-*.bin")
    ap.add_argument("--seed-file", default="tools/astrology_weight_v3_seeds.json")
    ap.add_argument("--json", default="weight-v4-search-report.json")
    ap.add_argument("--markdown", default="weight-v4-search-report.md")
    ap.add_argument("--coarse-n", type=int, default=50000)
    ap.add_argument("--refine-n", type=int, default=200000)
    ap.add_argument("--iterations", type=int, default=9000)
    args = ap.parse_args()

    x = base.load_features(args.pattern)
    n = len(x)
    rng = np.random.default_rng(20260903)
    xc = x[rng.choice(n, size=min(args.coarse_n, n), replace=False)]
    xr = x[rng.choice(n, size=min(args.refine_n, n), replace=False)]

    seed_stars = read_seed_stars(args.seed_file)
    starts = [np.zeros(STAR_COUNT, dtype=np.float64)] + seed_stars[:3]
    coarse = []
    for i, init in enumerate(starts):
        stars, profile, obj, probs = search(xc, 71 + i * 37, args.iterations, init_stars=init)
        coarse.append((stars, profile, obj, probs))
        print(json.dumps({"stage": "coarse", "start": i, "objective": obj, "core": probs.tolist()}, ensure_ascii=False))

    ranked = []
    for stars, profile, _, _ in coarse:
        state = build_state(xr, stars, profile)
        ranked.append((state.objective, stars, profile, state.probs))
    ranked.sort(key=lambda z: z[0])

    refined = []
    for i, (_, stars, profile, _) in enumerate(ranked[:3]):
        s2, p2, obj2, probs2 = search(xr, 1201 + i * 31, 2600, init_stars=stars, init_profile=profile)
        refined.append((s2, p2, obj2, probs2))
        print(json.dumps({"stage": "refine", "rank": i + 1, "objective": obj2, "core": probs2.tolist()}, ensure_ascii=False))

    candidates = refined + [(z[1], z[2], z[0], z[3]) for z in ranked]
    unique = {}
    for stars, profile, _, _ in candidates:
        key = (tuple(np.round(stars, 5)), tuple(np.round(profile.ravel(), 5)))
        unique[key] = (stars, profile)

    full = []
    for stars, profile in unique.values():
        summary = evaluate(x, stars, profile, "候選")
        full.append((summary["core_max_abs_error_pp"], summary["core_sum_abs_error_pp"], summary["parameter_abs_mean"], stars, profile, summary))
    full.sort(key=lambda z: (z[0], z[1], z[2]))

    chosen = [full[0]]
    close = [z for z in full[1:] if z[0] <= full[0][0] + 0.75]
    if close:
        chosen.append(min(close, key=lambda z: z[2]))
    elif len(full) > 1:
        chosen.append(full[1])
    remain = [z for z in full if z not in chosen]
    if remain:
        pool = [z for z in remain if z[0] <= full[0][0] + 1.25] or remain
        def vec(z):
            return np.concatenate([z[3], z[4].ravel() * 8.0])
        chosen.append(max(pool, key=lambda z: min(float(np.linalg.norm(vec(z) - vec(c))) for c in chosen)))
    chosen = chosen[:3]
    names = ["方案一：核心事件最貼近", "方案二：較少調整", "方案三：結構不同候選"]
    schemes = []
    for i, z in enumerate(chosen):
        s = z[5]
        s["name"] = names[i]
        schemes.append(s)

    zero_stars = np.zeros(STAR_COUNT, dtype=np.float64)
    production = evaluate(x, zero_stars, DEFAULT_PROFILE, "現行正式引擎（原門檻）", production_thresholds=True)
    threshold_only = evaluate(x, zero_stars, DEFAULT_PROFILE, "只校準門檻、不改星象權重（搜尋起始配置）")
    result = {
        "rows": int(n),
        "targets": {"A": 0.10, "B": 0.10, "C": 0.075, "D": 0.075},
        "fixed_overall_weights": {d: 0.20 for d in base.DOMAINS},
        "constraints": {
            "extreme_rate_each_side": EXTREME_RATE,
            "grade_symmetry": True,
            "ordering": "PING > XIAO_JI/XIAO_XIONG > JI/XIONG > DAI_JI/DAI_XIONG",
            "middle_grade_profile_searchable_for_all_six_items": True,
            "star_bounds": [STAR_MIN, STAR_MAX],
            "factor_sign_reversal": False,
        },
        "production_baseline": production,
        "threshold_only_baseline": threshold_only,
        "schemes": schemes,
        "top_full_candidates": [
            {"core_max_abs_error_pp": float(z[0]), "core_sum_abs_error_pp": float(z[1]), "parameter_abs_mean": float(z[2]), "core_events": z[5]["core_events"], "mean_pairwise_score_correlation": z[5]["continuous_score_correlation"]["mean_pairwise"]}
            for z in full[:10]
        ],
    }
    Path(args.json).write_text(json.dumps(result, ensure_ascii=False, indent=2), encoding="utf-8")
    Path(args.markdown).write_text(markdown(result), encoding="utf-8")
    print(json.dumps({"rows": n, "schemes": [{"name": s["name"], "core": s["core_events"], "max_error_pp": s["core_max_abs_error_pp"]} for s in schemes]}, ensure_ascii=False, indent=2))


if __name__ == "__main__":
    main()
