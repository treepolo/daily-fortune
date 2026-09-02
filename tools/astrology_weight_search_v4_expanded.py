#!/usr/bin/env python3
"""Expanded search orchestration for astrology calibration v4.

This changes search effort only.  All scoring targets, parameter bounds, grade
constraints, and evaluation logic come directly from astrology_weight_search_v4.
"""

from __future__ import annotations

import argparse
import json
from pathlib import Path

import numpy as np

import astrology_weight_search_v4 as v4


def random_valid_profile(rng: np.random.Generator) -> np.ndarray:
    profile = np.empty((v4.ITEM_COUNT, 2), dtype=np.float64)
    for item in range(v4.ITEM_COUNT):
        while True:
            g = float(np.clip(0.13 + rng.normal(0.0, 0.012), v4.PROFILE_G_MIN, v4.PROFILE_G_MAX))
            m = float(np.clip(0.17 + rng.normal(0.0, 0.012), v4.PROFILE_M_MIN, v4.PROFILE_M_MAX))
            if v4.valid_profile(g, m):
                profile[item] = (g, m)
                break
    return profile


def main() -> None:
    ap = argparse.ArgumentParser()
    ap.add_argument("--pattern", default="weight-v3-features-*.bin")
    ap.add_argument("--seed-file", default="tools/astrology_weight_v3_seeds.json")
    ap.add_argument("--json", default="weight-v4-search-report.json")
    ap.add_argument("--markdown", default="weight-v4-search-report.md")
    ap.add_argument("--coarse-n", type=int, default=120000)
    ap.add_argument("--refine-n", type=int, default=400000)
    ap.add_argument("--iterations", type=int, default=9000)
    ap.add_argument("--starts", type=int, default=10)
    args = ap.parse_args()

    x = v4.base.load_features(args.pattern)
    n = len(x)
    rng = np.random.default_rng(20260903)
    xc = x[rng.choice(n, size=min(args.coarse_n, n), replace=False)]
    xr = x[rng.choice(n, size=min(args.refine_n, n), replace=False)]

    seed_stars = v4.read_seed_stars(args.seed_file)
    starts: list[tuple[np.ndarray, np.ndarray, str]] = [
        (np.zeros(v4.STAR_COUNT, dtype=np.float64), v4.DEFAULT_PROFILE.copy(), "zero"),
    ]
    for i, seed in enumerate(seed_stars[:3]):
        starts.append((seed, v4.DEFAULT_PROFILE.copy(), f"saved-seed-{i + 1}"))

    while len(starts) < args.starts:
        random_stars = np.clip(
            rng.normal(0.0, 0.65, size=v4.STAR_COUNT),
            v4.STAR_MIN,
            v4.STAR_MAX,
        )
        starts.append((random_stars, random_valid_profile(rng), f"random-{len(starts) + 1}"))
    starts = starts[: max(1, args.starts)]

    coarse = []
    for i, (init_stars, init_profile, label) in enumerate(starts):
        stars, profile, obj, probs = v4.search(
            xc,
            71 + i * 37,
            args.iterations,
            init_stars=init_stars,
            init_profile=init_profile,
        )
        coarse.append((stars, profile, obj, probs))
        print(json.dumps({
            "stage": "coarse",
            "start": i,
            "label": label,
            "objective": obj,
            "core": probs.tolist(),
        }, ensure_ascii=False))

    ranked = []
    for stars, profile, _, _ in coarse:
        state = v4.build_state(xr, stars, profile)
        ranked.append((state.objective, stars, profile, state.probs))
    ranked.sort(key=lambda z: z[0])

    refined = []
    for i, (_, stars, profile, _) in enumerate(ranked[:3]):
        s2, p2, obj2, probs2 = v4.search(
            xr,
            1201 + i * 31,
            2600,
            init_stars=stars,
            init_profile=profile,
        )
        refined.append((s2, p2, obj2, probs2))
        print(json.dumps({
            "stage": "refine",
            "rank": i + 1,
            "objective": obj2,
            "core": probs2.tolist(),
        }, ensure_ascii=False))

    candidates = refined + [(z[1], z[2], z[0], z[3]) for z in ranked]
    unique: dict[tuple, tuple[np.ndarray, np.ndarray]] = {}
    for stars, profile, _, _ in candidates:
        key = (tuple(np.round(stars, 5)), tuple(np.round(profile.ravel(), 5)))
        unique[key] = (stars, profile)

    full = []
    for stars, profile in unique.values():
        summary = v4.evaluate(x, stars, profile, "候選")
        full.append((
            summary["core_max_abs_error_pp"],
            summary["core_sum_abs_error_pp"],
            summary["parameter_abs_mean"],
            stars,
            profile,
            summary,
        ))
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

        chosen.append(max(
            pool,
            key=lambda z: min(float(np.linalg.norm(vec(z) - vec(c))) for c in chosen),
        ))
    chosen = chosen[:3]

    names = ["方案一：核心事件最貼近", "方案二：較少調整", "方案三：結構不同候選"]
    schemes = []
    for i, z in enumerate(chosen):
        summary = z[5]
        summary["name"] = names[i]
        schemes.append(summary)

    zero_stars = np.zeros(v4.STAR_COUNT, dtype=np.float64)
    production = v4.evaluate(
        x,
        zero_stars,
        v4.DEFAULT_PROFILE,
        "現行正式引擎（原門檻）",
        production_thresholds=True,
    )
    threshold_only = v4.evaluate(
        x,
        zero_stars,
        v4.DEFAULT_PROFILE,
        "只校準門檻、不改星象權重（搜尋起始配置）",
    )
    result = {
        "rows": int(n),
        "targets": {"A": 0.10, "B": 0.10, "C": 0.075, "D": 0.075},
        "fixed_overall_weights": {d: 0.20 for d in v4.base.DOMAINS},
        "constraints": {
            "extreme_rate_each_side": v4.EXTREME_RATE,
            "grade_symmetry": True,
            "ordering": "PING > XIAO_JI/XIAO_XIONG > JI/XIONG > DAI_JI/DAI_XIONG",
            "middle_grade_profile_searchable_for_all_six_items": True,
            "star_bounds": [v4.STAR_MIN, v4.STAR_MAX],
            "factor_sign_reversal": False,
        },
        "search_effort": {
            "independent_starts": len(starts),
            "coarse_sample_rows": int(len(xc)),
            "refine_sample_rows": int(len(xr)),
            "coarse_iterations_per_start": int(args.iterations),
            "refined_candidates": min(3, len(ranked)),
            "refine_iterations_per_candidate": 2600,
        },
        "production_baseline": production,
        "threshold_only_baseline": threshold_only,
        "schemes": schemes,
        "top_full_candidates": [
            {
                "core_max_abs_error_pp": float(z[0]),
                "core_sum_abs_error_pp": float(z[1]),
                "parameter_abs_mean": float(z[2]),
                "core_events": z[5]["core_events"],
                "mean_pairwise_score_correlation": z[5]["continuous_score_correlation"]["mean_pairwise"],
            }
            for z in full[:10]
        ],
    }

    Path(args.json).write_text(json.dumps(result, ensure_ascii=False, indent=2), encoding="utf-8")
    Path(args.markdown).write_text(v4.markdown(result), encoding="utf-8")
    print(json.dumps({
        "rows": n,
        "search_effort": result["search_effort"],
        "schemes": [
            {"name": s["name"], "core": s["core_events"], "max_error_pp": s["core_max_abs_error_pp"]}
            for s in schemes
        ],
    }, ensure_ascii=False, indent=2))


if __name__ == "__main__":
    main()
