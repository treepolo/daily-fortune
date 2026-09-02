#!/usr/bin/env python3
"""Fine-grained astrology calibration search.

The continuous five-domain score keeps a fixed 20%/20%/20%/20%/20% overall average.
The search may only alter existing astrology contribution strengths through overlapping channels:
  * planet x domain
  * house x domain (house factors only)
  * aspect type x domain (aspect factors only)

Each factor always keeps its original sign. Parameter bounds guarantee every effective multiplier stays > 0.
Grade thresholds are independently calibrated for each of the five domains and overall score from that
candidate's empirical distribution. The target marginal grade profile is symmetric:
  10% / 13% / 17% / 20% / 17% / 13% / 10%
for DAI_XIONG .. DAI_JI (equivalently the reverse display order).

The joint targets are:
A ~= 10%: exactly one domain DAI_JI, overall not DAI_JI, >=2 domains XIAO_XIONG or worse.
B ~= 10%: exactly one domain DAI_XIONG, overall not DAI_XIONG, >=2 domains XIAO_JI or better.
C ~= 7.5%: all five domains PING or better.
D ~= 7.5%: all five domains PING or worse.
"""

from __future__ import annotations

import argparse
import glob
import json
import math
from dataclasses import dataclass
from pathlib import Path

import numpy as np

BODIES = ["太陽", "月亮", "水星", "金星", "火星", "木星", "土星", "天王星", "海王星", "冥王星"]
DOMAINS = ["財運", "戀愛", "工作／學業", "人際", "健康"]
ASPECTS = ["合相", "六合", "刑相", "拱相", "對分"]
ITEMS = DOMAINS + ["總分"]
GRADES_LOW_TO_HIGH = ["大凶", "凶", "小凶", "平", "小吉", "吉", "大吉"]
GRADES_HIGH_TO_LOW = list(reversed(GRADES_LOW_TO_HIGH))

BODY_COUNT = 10
HOUSE_COUNT = 12
ASPECT_COUNT = 5
DOMAIN_COUNT = 5
BODY_COLUMNS = BODY_COUNT * DOMAIN_COUNT
HOUSE_COLUMNS = HOUSE_COUNT * DOMAIN_COUNT
ASPECT_COLUMNS = ASPECT_COUNT * DOMAIN_COUNT
COLUMNS = BODY_COLUMNS + HOUSE_COLUMNS + ASPECT_COLUMNS
PARAM_COUNT = COLUMNS

# Low-to-high cumulative boundaries for 10/13/17/20/17/13/10.
GRADE_QUANTILES = np.array([0.10, 0.23, 0.40, 0.60, 0.77, 0.90], dtype=np.float64)
TARGET_GRADE_RATES_LOW_TO_HIGH = np.array([0.10, 0.13, 0.17, 0.20, 0.17, 0.13, 0.10], dtype=np.float64)
CORE_TARGETS = np.array([0.10, 0.10, 0.075, 0.075], dtype=np.float64)
CORE_SCALES = np.array([0.010, 0.010, 0.005, 0.005], dtype=np.float64)
PARAM_MIN = -0.40
PARAM_MAX = 1.50
REGULARITY = 0.010


@dataclass
class SearchState:
    params: np.ndarray
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


def load_features(pattern: str) -> np.ndarray:
    files = sorted(glob.glob(pattern))
    if not files:
        raise SystemExit(f"no feature files match {pattern}")
    arrays: list[np.ndarray] = []
    for path in files:
        raw = np.fromfile(path, dtype=np.float32)
        if raw.size % COLUMNS:
            raise ValueError(f"{path}: float count {raw.size} not divisible by {COLUMNS}")
        arrays.append(raw.reshape(-1, COLUMNS))
    return np.concatenate(arrays, axis=0)


def base_scores(x: np.ndarray) -> np.ndarray:
    # Every original factor is represented exactly once across the 10 body channels;
    # aspects are split 50/50 between their two bodies.
    return x[:, :BODY_COLUMNS].reshape(-1, BODY_COUNT, DOMAIN_COUNT).sum(axis=1, dtype=np.float64)


def scores_from_params(x: np.ndarray, params: np.ndarray) -> np.ndarray:
    scores = base_scores(x)
    for j, value in enumerate(params):
        if value != 0.0:
            scores[:, j % DOMAIN_COUNT] += x[:, j] * value
    return scores


def calibrated_thresholds(values: np.ndarray) -> np.ndarray:
    return np.quantile(values, GRADE_QUANTILES)


def grade_codes(values: np.ndarray, thresholds: np.ndarray) -> np.ndarray:
    # 0=大凶 ... 6=大吉.
    return np.searchsorted(thresholds, values, side="right").astype(np.int8)


def current_grade_codes(values: np.ndarray) -> np.ndarray:
    # Existing production thresholds, expressed low-to-high.
    return np.searchsorted(np.array([-10.0, -5.5, -1.8, 1.8, 5.5, 10.0]), values, side="right").astype(np.int8)


def core_events_from_counts(
    dai_ji_count: np.ndarray,
    dai_xiong_count: np.ndarray,
    negative_count: np.ndarray,
    positive_count: np.ndarray,
    ge_ping_count: np.ndarray,
    le_ping_count: np.ndarray,
    overall_codes: np.ndarray,
) -> np.ndarray:
    a = (dai_ji_count == 1) & (overall_codes < 6) & (negative_count >= 2)
    b = (dai_xiong_count == 1) & (overall_codes > 0) & (positive_count >= 2)
    c = ge_ping_count == DOMAIN_COUNT
    d = le_ping_count == DOMAIN_COUNT
    return np.array([a.mean(), b.mean(), c.mean(), d.mean()], dtype=np.float64)


def objective_value(probs: np.ndarray, sumsq: float) -> float:
    target_error = float(np.sum(((probs - CORE_TARGETS) / CORE_SCALES) ** 2))
    regularity = REGULARITY * (sumsq / PARAM_COUNT)
    return target_error + regularity


def build_state(x: np.ndarray, params: np.ndarray) -> SearchState:
    scores = scores_from_params(x, params)
    codes = np.empty(scores.shape, dtype=np.int8)
    for d in range(DOMAIN_COUNT):
        codes[:, d] = grade_codes(scores[:, d], calibrated_thresholds(scores[:, d]))
    overall = scores.mean(axis=1)
    overall_codes = grade_codes(overall, calibrated_thresholds(overall))
    dai_ji_count = (codes == 6).sum(axis=1).astype(np.int8)
    dai_xiong_count = (codes == 0).sum(axis=1).astype(np.int8)
    negative_count = (codes <= 2).sum(axis=1).astype(np.int8)
    positive_count = (codes >= 4).sum(axis=1).astype(np.int8)
    ge_ping_count = (codes >= 3).sum(axis=1).astype(np.int8)
    le_ping_count = (codes <= 3).sum(axis=1).astype(np.int8)
    probs = core_events_from_counts(
        dai_ji_count, dai_xiong_count, negative_count, positive_count,
        ge_ping_count, le_ping_count, overall_codes,
    )
    sumsq = float(np.dot(params, params))
    return SearchState(
        params=params.copy(), scores=scores, overall=overall, codes=codes, overall_codes=overall_codes,
        dai_ji_count=dai_ji_count, dai_xiong_count=dai_xiong_count,
        negative_count=negative_count, positive_count=positive_count,
        ge_ping_count=ge_ping_count, le_ping_count=le_ping_count,
        probs=probs, sumsq=sumsq, objective=objective_value(probs, sumsq),
    )


def try_coordinate(state: SearchState, x: np.ndarray, j: int, proposed: float):
    old_param = float(state.params[j])
    proposed = float(np.clip(proposed, PARAM_MIN, PARAM_MAX))
    delta = proposed - old_param
    if abs(delta) < 1e-12:
        return None
    d = j % DOMAIN_COUNT
    feature = x[:, j]
    old_codes = state.codes[:, d]
    new_domain = state.scores[:, d] + feature * delta
    new_codes = grade_codes(new_domain, calibrated_thresholds(new_domain))
    new_overall = state.overall + feature * (delta / DOMAIN_COUNT)
    new_overall_codes = grade_codes(new_overall, calibrated_thresholds(new_overall))

    dj = state.dai_ji_count + (new_codes == 6).astype(np.int8) - (old_codes == 6).astype(np.int8)
    dx = state.dai_xiong_count + (new_codes == 0).astype(np.int8) - (old_codes == 0).astype(np.int8)
    neg = state.negative_count + (new_codes <= 2).astype(np.int8) - (old_codes <= 2).astype(np.int8)
    pos = state.positive_count + (new_codes >= 4).astype(np.int8) - (old_codes >= 4).astype(np.int8)
    ge = state.ge_ping_count + (new_codes >= 3).astype(np.int8) - (old_codes >= 3).astype(np.int8)
    le = state.le_ping_count + (new_codes <= 3).astype(np.int8) - (old_codes <= 3).astype(np.int8)
    probs = core_events_from_counts(dj, dx, neg, pos, ge, le, new_overall_codes)
    sumsq = state.sumsq - old_param * old_param + proposed * proposed
    obj = objective_value(probs, sumsq)
    return (obj, probs, sumsq, d, proposed, new_domain, new_codes, new_overall, new_overall_codes, dj, dx, neg, pos, ge, le)


def accept_coordinate(state: SearchState, candidate) -> None:
    (obj, probs, sumsq, d, proposed, new_domain, new_codes, new_overall, new_overall_codes,
     dj, dx, neg, pos, ge, le) = candidate
    # j is recovered by caller before this function and parameter is set there.
    state.objective = float(obj)
    state.probs = probs
    state.sumsq = float(sumsq)
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


def stochastic_search(x: np.ndarray, seed: int, iterations: int, init: np.ndarray | None = None) -> tuple[np.ndarray, float, np.ndarray]:
    rng = np.random.default_rng(seed)
    if init is None:
        params = np.zeros(PARAM_COUNT, dtype=np.float64)
        if seed != 7:
            params += rng.normal(0.0, 0.06, size=PARAM_COUNT)
            np.clip(params, PARAM_MIN, PARAM_MAX, out=params)
    else:
        params = init.copy()
    state = build_state(x, params)
    best_p = state.params.copy()
    best_obj = state.objective
    best_probs = state.probs.copy()

    phases = [
        (0.30, 0.20),
        (0.16, 0.08),
        (0.075, 0.025),
        (0.035, 0.006),
    ]
    per_phase = max(1, iterations // len(phases))
    for sigma, temperature in phases:
        for _ in range(per_phase):
            j = int(rng.integers(PARAM_COUNT))
            proposed = state.params[j] + rng.normal(0.0, sigma)
            candidate = try_coordinate(state, x, j, proposed)
            if candidate is None:
                continue
            delta_obj = float(candidate[0] - state.objective)
            if delta_obj <= 0 or rng.random() < math.exp(-delta_obj / max(temperature, 1e-9)):
                state.params[j] = float(candidate[4])
                accept_coordinate(state, candidate)
                if state.objective < best_obj:
                    best_obj = state.objective
                    best_p = state.params.copy()
                    best_probs = state.probs.copy()

    # Deterministic coordinate polish from the best annealed state.
    state = build_state(x, best_p)
    for step in (0.030, 0.015, 0.0075):
        order = rng.permutation(PARAM_COUNT)
        for j in order:
            best_candidate = None
            for direction in (-1.0, 1.0):
                candidate = try_coordinate(state, x, int(j), state.params[j] + direction * step)
                if candidate is not None and candidate[0] < state.objective and (best_candidate is None or candidate[0] < best_candidate[0]):
                    best_candidate = candidate
            if best_candidate is not None:
                state.params[j] = float(best_candidate[4])
                accept_coordinate(state, best_candidate)
    return state.params.copy(), state.objective, state.probs.copy()


def evaluate_core(scores: np.ndarray, calibrated: bool = True):
    if calibrated:
        thresholds = np.vstack([calibrated_thresholds(scores[:, d]) for d in range(DOMAIN_COUNT)])
        codes = np.column_stack([grade_codes(scores[:, d], thresholds[d]) for d in range(DOMAIN_COUNT)])
        overall = scores.mean(axis=1)
        overall_thresholds = calibrated_thresholds(overall)
        overall_codes = grade_codes(overall, overall_thresholds)
    else:
        thresholds = np.tile(np.array([-10.0, -5.5, -1.8, 1.8, 5.5, 10.0]), (DOMAIN_COUNT, 1))
        codes = np.column_stack([current_grade_codes(scores[:, d]) for d in range(DOMAIN_COUNT)])
        overall = scores.mean(axis=1)
        overall_thresholds = np.array([-10.0, -5.5, -1.8, 1.8, 5.5, 10.0])
        overall_codes = current_grade_codes(overall)
    dj = (codes == 6).sum(axis=1)
    dx = (codes == 0).sum(axis=1)
    neg = (codes <= 2).sum(axis=1)
    pos = (codes >= 4).sum(axis=1)
    ge = (codes >= 3).sum(axis=1)
    le = (codes <= 3).sum(axis=1)
    probs = core_events_from_counts(dj, dx, neg, pos, ge, le, overall_codes)
    return probs, codes, overall, overall_codes, thresholds, overall_thresholds


def rate(mask: np.ndarray) -> float:
    return float(np.mean(mask))


def summarize(name: str, x: np.ndarray, params: np.ndarray, calibrated: bool = True) -> dict:
    scores = scores_from_params(x, params)
    probs, codes, overall, overall_codes, thresholds, overall_thresholds = evaluate_core(scores, calibrated=calibrated)
    all_codes = np.column_stack([codes, overall_codes])
    grade_rates = {}
    for i, label in enumerate(ITEMS):
        grade_rates[label] = {
            grade: rate(all_codes[:, i] == code)
            for code, grade in enumerate(GRADES_LOW_TO_HIGH)
        }

    dai_ji = codes == 6
    dai_xiong = codes == 0
    positive = codes >= 4
    negative = codes <= 2
    ping = codes == 3
    important = {
        "A_恰一細項大吉_總分非大吉_至少兩細項小凶或更差": float(probs[0]),
        "B_恰一細項大凶_總分非大凶_至少兩細項小吉或更好": float(probs[1]),
        "C_五細項全部至少平": float(probs[2]),
        "D_五細項全部至多平": float(probs[3]),
        "C與D重疊_五細項全部為平": rate(np.all(ping, axis=1)),
        "恰一細項大吉": rate(dai_ji.sum(axis=1) == 1),
        "恰一細項大凶": rate(dai_xiong.sum(axis=1) == 1),
        "至少一細項大吉": rate(dai_ji.any(axis=1)),
        "至少一細項大凶": rate(dai_xiong.any(axis=1)),
        "細項同時存在大吉與大凶": rate(dai_ji.any(axis=1) & dai_xiong.any(axis=1)),
        "至少一細項小吉或更好且至少一細項小凶或更差": rate(positive.any(axis=1) & negative.any(axis=1)),
        "五細項全部至少小吉": rate(np.all(positive, axis=1)),
        "五細項全部小凶或更差": rate(np.all(negative, axis=1)),
        "總分大吉且至少一細項大凶": rate((overall_codes == 6) & dai_xiong.any(axis=1)),
        "總分大凶且至少一細項大吉": rate((overall_codes == 0) & dai_ji.any(axis=1)),
    }

    corr = np.corrcoef(scores, rowvar=False)
    offdiag = corr[np.triu_indices(DOMAIN_COUNT, 1)]
    threshold_map = {
        DOMAINS[d]: [float(v) for v in thresholds[d]]
        for d in range(DOMAIN_COUNT)
    }
    threshold_map["總分"] = [float(v) for v in overall_thresholds]

    body_delta = params[:BODY_COLUMNS].reshape(BODY_COUNT, DOMAIN_COUNT)
    house_delta = params[BODY_COLUMNS:BODY_COLUMNS + HOUSE_COLUMNS].reshape(HOUSE_COUNT, DOMAIN_COUNT)
    aspect_delta = params[BODY_COLUMNS + HOUSE_COLUMNS:].reshape(ASPECT_COUNT, DOMAIN_COUNT)
    return {
        "name": name,
        "core_events": {"A": float(probs[0]), "B": float(probs[1]), "C": float(probs[2]), "D": float(probs[3])},
        "core_sum_abs_error_pp": float(np.sum(np.abs(probs - CORE_TARGETS)) * 100),
        "core_max_abs_error_pp": float(np.max(np.abs(probs - CORE_TARGETS)) * 100),
        "grade_rates": grade_rates,
        "thresholds_low_to_high": threshold_map,
        "important_combinations": important,
        "continuous_score_correlation": {
            "matrix": [[float(v) for v in row] for row in corr],
            "mean_pairwise": float(np.mean(offdiag)),
            "mean_abs_pairwise": float(np.mean(np.abs(offdiag))),
        },
        "parameter_adjustments": {
            "body_domain_multiplier": {
                BODIES[b]: {DOMAINS[d]: float(1.0 + body_delta[b, d]) for d in range(DOMAIN_COUNT)}
                for b in range(BODY_COUNT)
            },
            "house_domain_additive_delta": {
                str(h + 1): {DOMAINS[d]: float(house_delta[h, d]) for d in range(DOMAIN_COUNT)}
                for h in range(HOUSE_COUNT)
            },
            "aspect_domain_additive_delta": {
                ASPECTS[a]: {DOMAINS[d]: float(aspect_delta[a, d]) for d in range(DOMAIN_COUNT)}
                for a in range(ASPECT_COUNT)
            },
        },
        "parameter_abs_mean": float(np.mean(np.abs(params))),
        "parameter_min": float(np.min(params)),
        "parameter_max": float(np.max(params)),
    }


def pct(x: float) -> str:
    return f"{x * 100:.4f}%"


def markdown_report(result: dict) -> str:
    lines = [
        "# 占星細粒度參數搜尋 v3（1900–2100 全量驗證）",
        "",
        f"完整資料：{result['rows']:,} 筆（1900–2100 每日 × 12 星座）。",
        "",
        "## 固定規則與搜尋空間",
        "",
        "- 總分永遠是五細項算術平均；財運、戀愛、工作／學業、人際、健康固定各 20%，搜尋器不可修改。",
        "- 星象調整通道：行星×領域、宮位×領域、相位種類×領域。",
        "- 原始因子的正負方向不翻轉。參數界線保證任何因子的合成有效倍率都大於 0。",
        "- 每個細項與總分各自依其候選分數分布校準六個切點；切點可以彼此不同，也不要求正負數值鏡像。",
        "- 七級邊際目標固定為：大吉 10%、吉 13%、小吉 17%、平 20%、小凶 17%、凶 13%、大凶 10%。",
        "- A≈10%、B≈10%、C≈7.5%、D≈7.5%；C/D 都只看五細項，不含總分。",
        "",
        "有效倍率規則：一般單星因素 = `1 + 行星調整`；落宮因素 = `1 + 行星調整 + 宮位調整`；行星相位 = `1 + 0.5×行星A調整 + 0.5×行星B調整 + 相位種類調整`。",
        "",
    ]

    for key in ["production_baseline", "threshold_only_baseline"]:
        s = result[key]
        lines += [f"## {s['name']}", "", "| A | B | C | D | 最大目標偏差 |", "|---:|---:|---:|---:|---:|",
                  f"| {pct(s['core_events']['A'])} | {pct(s['core_events']['B'])} | {pct(s['core_events']['C'])} | {pct(s['core_events']['D'])} | {s['core_max_abs_error_pp']:.3f} pp |", ""]

    for scheme in result["schemes"]:
        lines += [f"## {scheme['name']}", "",
                  f"四目標最大偏差：**{scheme['core_max_abs_error_pp']:.3f} pp**；總絕對偏差：{scheme['core_sum_abs_error_pp']:.3f} pp；五細項連續分數平均兩兩相關：{scheme['continuous_score_correlation']['mean_pairwise']:.4f}。",
                  "", "### 核心事件", "", "| A | B | C | D |", "|---:|---:|---:|---:|",
                  f"| {pct(scheme['core_events']['A'])} | {pct(scheme['core_events']['B'])} | {pct(scheme['core_events']['C'])} | {pct(scheme['core_events']['D'])} |", "",
                  "### 各項七級機率", "", "| 項目 | 大吉 | 吉 | 小吉 | 平 | 小凶 | 凶 | 大凶 |", "|---|---:|---:|---:|---:|---:|---:|---:|"]
        for item in ITEMS:
            g = scheme["grade_rates"][item]
            lines.append("| " + item + " | " + " | ".join(pct(g[grade]) for grade in GRADES_HIGH_TO_LOW) + " |")

        lines += ["", "### 六個分數切點", "", "由低到高依序為：大凶/凶、凶/小凶、小凶/平、平/小吉、小吉/吉、吉/大吉。", "",
                  "| 項目 | t1 | t2 | t3 | t4 | t5 | t6 |", "|---|---:|---:|---:|---:|---:|---:|"]
        for item in ITEMS:
            t = scheme["thresholds_low_to_high"][item]
            lines.append(f"| {item} | " + " | ".join(f"{v:.4f}" for v in t) + " |")

        lines += ["", "### 重要組合", "", "| 組合 | 機率 |", "|---|---:|"]
        for k, v in scheme["important_combinations"].items():
            lines.append(f"| {k} | {pct(v)} |")

        lines += ["", "### 五細項連續分數相關矩陣", "", "| | 財運 | 戀愛 | 工作／學業 | 人際 | 健康 |", "|---|---:|---:|---:|---:|---:|"]
        matrix = scheme["continuous_score_correlation"]["matrix"]
        for i, label in enumerate(DOMAINS):
            lines.append(f"| {label} | " + " | ".join(f"{matrix[i][j]:.3f}" for j in range(DOMAIN_COUNT)) + " |")

        lines += ["", "### 行星×領域倍率", "", "| 行星 | 財運 | 戀愛 | 工作／學業 | 人際 | 健康 |", "|---|---:|---:|---:|---:|---:|"]
        bm = scheme["parameter_adjustments"]["body_domain_multiplier"]
        for body in BODIES:
            lines.append(f"| {body} | " + " | ".join(f"{bm[body][d]:.3f}" for d in DOMAINS) + " |")

        lines += ["", "### 宮位×領域附加調整", "", "| 宮位 | 財運 | 戀愛 | 工作／學業 | 人際 | 健康 |", "|---|---:|---:|---:|---:|---:|"]
        hm = scheme["parameter_adjustments"]["house_domain_additive_delta"]
        for h in range(1, 13):
            row = hm[str(h)]
            lines.append(f"| {h} | " + " | ".join(f"{row[d]:+.3f}" for d in DOMAINS) + " |")

        lines += ["", "### 相位種類×領域附加調整", "", "| 相位 | 財運 | 戀愛 | 工作／學業 | 人際 | 健康 |", "|---|---:|---:|---:|---:|---:|"]
        am = scheme["parameter_adjustments"]["aspect_domain_additive_delta"]
        for aspect in ASPECTS:
            lines.append(f"| {aspect} | " + " | ".join(f"{am[aspect][d]:+.3f}" for d in DOMAINS) + " |")
        lines.append("")
    return "\n".join(lines)


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--pattern", default="weight-v3-features-*.bin")
    ap.add_argument("--json", default="weight-v3-search-report.json")
    ap.add_argument("--markdown", default="weight-v3-search-report.md")
    ap.add_argument("--coarse-n", type=int, default=40000)
    ap.add_argument("--refine-n", type=int, default=180000)
    ap.add_argument("--iterations", type=int, default=6400)
    args = ap.parse_args()

    x = load_features(args.pattern)
    n = len(x)
    rng = np.random.default_rng(20260903)
    coarse_idx = rng.choice(n, size=min(args.coarse_n, n), replace=False)
    refine_idx = rng.choice(n, size=min(args.refine_n, n), replace=False)
    xc = x[coarse_idx]
    xr = x[refine_idx]

    coarse_candidates = []
    for seed in (7, 29, 83, 131):
        p, obj, probs = stochastic_search(xc, seed, args.iterations)
        coarse_candidates.append((p, obj, probs))
        print(json.dumps({"stage": "coarse", "seed": seed, "objective": obj, "core": probs.tolist()}, ensure_ascii=False))

    ranked_refine = []
    for p, _, _ in coarse_candidates:
        state = build_state(xr, p)
        ranked_refine.append((p, state.objective, state.probs))
    ranked_refine.sort(key=lambda item: item[1])

    refined = []
    for rank, (p, _, _) in enumerate(ranked_refine[:3]):
        p2, obj2, probs2 = stochastic_search(xr, 1001 + rank * 17, 1800, init=p)
        refined.append((p2, obj2, probs2))
        print(json.dumps({"stage": "refine", "rank": rank + 1, "objective": obj2, "core": probs2.tolist()}, ensure_ascii=False))

    # Keep a few structurally distinct candidates and validate every reported scheme on the full universe.
    candidate_params = [p for p, _, _ in refined] + [p for p, _, _ in ranked_refine]
    unique = {}
    for p in candidate_params:
        unique[tuple(np.round(p, 5))] = p
    full_ranked = []
    for p in unique.values():
        s = summarize("候選", x, p, calibrated=True)
        full_ranked.append((s["core_max_abs_error_pp"], s["core_sum_abs_error_pp"], s["parameter_abs_mean"], p, s))
    full_ranked.sort(key=lambda item: (item[0], item[1], item[2]))

    chosen = [full_ranked[0]]
    close = [item for item in full_ranked[1:] if item[0] <= full_ranked[0][0] + 0.75]
    if close:
        chosen.append(min(close, key=lambda item: item[2]))
    elif len(full_ranked) > 1:
        chosen.append(full_ranked[1])
    remaining = [item for item in full_ranked if item not in chosen]
    if remaining:
        def distance(item, other):
            return float(np.linalg.norm(item[3] - other[3]))
        pool = [item for item in remaining if item[0] <= full_ranked[0][0] + 1.25] or remaining
        chosen.append(max(pool, key=lambda item: min(distance(item, c) for c in chosen)))
    chosen = chosen[:3]

    schemes = []
    names = ["方案一：核心事件最貼近", "方案二：較少調整", "方案三：結構不同候選"]
    for i, item in enumerate(chosen):
        s = item[4]
        s["name"] = names[i]
        schemes.append(s)

    zeros = np.zeros(PARAM_COUNT, dtype=np.float64)
    production_baseline = summarize("現行正式引擎（原門檻）", x, zeros, calibrated=False)
    threshold_only_baseline = summarize("只重校門檻、不改星象權重", x, zeros, calibrated=True)

    result = {
        "rows": int(n),
        "targets": {"A": 0.10, "B": 0.10, "C": 0.075, "D": 0.075},
        "fixed_overall_weights": {d: 0.20 for d in DOMAINS},
        "target_grade_rates": {grade: float(TARGET_GRADE_RATES_LOW_TO_HIGH[i]) for i, grade in enumerate(GRADES_LOW_TO_HIGH)},
        "parameterization": {
            "version": 3,
            "parameter_count": PARAM_COUNT,
            "parameter_bounds": [PARAM_MIN, PARAM_MAX],
            "body_domain_parameters": BODY_COLUMNS,
            "house_domain_parameters": HOUSE_COLUMNS,
            "aspect_domain_parameters": ASPECT_COLUMNS,
            "effective_multiplier_guarantee": "body-only >=0.60; house/aspect composite >=0.20; no factor sign reversal",
            "grade_thresholds": "independent empirical quantile cutpoints per domain and overall; numeric symmetry not required",
        },
        "production_baseline": production_baseline,
        "threshold_only_baseline": threshold_only_baseline,
        "schemes": schemes,
        "top_full_candidates": [
            {
                "core_max_abs_error_pp": float(item[0]),
                "core_sum_abs_error_pp": float(item[1]),
                "parameter_abs_mean": float(item[2]),
                "core_events": item[4]["core_events"],
                "mean_pairwise_score_correlation": item[4]["continuous_score_correlation"]["mean_pairwise"],
            }
            for item in full_ranked[:10]
        ],
    }
    Path(args.json).write_text(json.dumps(result, ensure_ascii=False, indent=2), encoding="utf-8")
    Path(args.markdown).write_text(markdown_report(result), encoding="utf-8")
    print(json.dumps({
        "rows": n,
        "production_baseline": production_baseline["core_events"],
        "threshold_only_baseline": threshold_only_baseline["core_events"],
        "schemes": [{"name": s["name"], "core": s["core_events"], "max_error_pp": s["core_max_abs_error_pp"]} for s in schemes],
    }, ensure_ascii=False, indent=2))


if __name__ == "__main__":
    main()
