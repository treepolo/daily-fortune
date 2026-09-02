#!/usr/bin/env python3
"""Target-search wrapper for the revised A/B/C/D event definitions.

A ~= 10%: exactly one domain is DAI_JI, overall is not DAI_JI, and at least two domains are XIAO_XIONG or worse.
B ~= 10%: exactly one domain is DAI_XIONG, overall is not DAI_XIONG, and at least two domains are XIAO_JI or better.
C ~= 7.5%: all five domains are PING or better.
D ~= 7.5%: all five domains are PING or worse.

Only existing contribution-family multipliers and overall-domain weights are searched. Grade thresholds and astronomy rules are unchanged.
"""

import json
import sys
from pathlib import Path

import numpy as np

import astrology_weight_search as base

TARGETS = [0.10, 0.10, 0.075, 0.075]


def revised_core_events(scores: np.ndarray, overall: np.ndarray):
    dai_ji = scores >= 10.0
    dai_xiong = scores <= -10.0
    worse_than_ping = scores <= -1.8
    better_than_ping = scores >= 1.8

    a = (dai_ji.sum(axis=1) == 1) & (overall < 10.0) & (worse_than_ping.sum(axis=1) >= 2)
    b = (dai_xiong.sum(axis=1) == 1) & (overall > -10.0) & (better_than_ping.sum(axis=1) >= 2)

    # PING is strictly (-1.8, 1.8): exactly -1.8 is XIAO_XIONG and exactly +1.8 is XIAO_JI.
    c = np.all(scores > -1.8, axis=1)
    d = np.all(scores < 1.8, axis=1)
    return np.array([a.mean(), b.mean(), c.mean(), d.mean()], dtype=np.float64)


_original_summarize = base.summarize
_original_markdown_report = base.markdown_report


def revised_summarize(name: str, x: np.ndarray, multipliers: np.ndarray, weights: np.ndarray):
    out = _original_summarize(name, x, multipliers, weights)
    important = out["important_combinations"]
    c_value = important.pop("C_五細項全部至少吉")
    d_value = important.pop("D_五細項全部凶或大凶")

    # Keep the four requested target events at the front, followed by the existing diagnostics.
    rebuilt = {
        "A_恰一細項大吉_總分非大吉_至少兩細項小凶或更差": important.pop("A_恰一細項大吉_總分非大吉_至少兩細項小凶或更差"),
        "B_恰一細項大凶_總分非大凶_至少兩細項小吉或更好": important.pop("B_恰一細項大凶_總分非大凶_至少兩細項小吉或更好"),
        "C_五細項全部至少平": c_value,
        "D_五細項全部至多平": d_value,
    }
    rebuilt.update(important)

    scores = base.adjusted_scores(x, multipliers)
    all_ping = np.all((scores > -1.8) & (scores < 1.8), axis=1)
    rebuilt["C與D重疊_五細項全部為平"] = float(np.mean(all_ping))
    out["important_combinations"] = rebuilt
    return out


def revised_markdown_report(result: dict) -> str:
    text = _original_markdown_report(result)
    text = text.replace(
        "- C：五細項全部至少吉（吉或大吉）。",
        "- C：五細項全部至少平（平／小吉／吉／大吉），目標約 7.5%。",
    ).replace(
        "- D：五細項全部為凶或大凶。",
        "- D：五細項全部至多平（大凶／凶／小凶／平），目標約 7.5%。",
    )
    text = text.replace(
        "- A：恰好 1 個細項大吉、總分不是大吉、且至少 2 個細項為小凶／凶／大凶（平不算）。",
        "- A：恰好 1 個細項大吉、總分不是大吉、且至少 2 個細項為小凶／凶／大凶（平不算），目標約 10%。",
    ).replace(
        "- B：恰好 1 個細項大凶、總分不是大凶、且至少 2 個細項為小吉／吉／大吉（平不算）。",
        "- B：恰好 1 個細項大凶、總分不是大凶、且至少 2 個細項為小吉／吉／大吉（平不算），目標約 10%。",
    )
    text = text.replace(
        "所有候選只縮放既有四類天象貢獻",
        "C／D 只看五個細項、不含總分；五項全部為平時可同時符合 C 與 D。\n\n所有候選只縮放既有四類天象貢獻",
    )
    return text


def output_arg(flag: str, default: str) -> str:
    if flag in sys.argv:
        i = sys.argv.index(flag)
        if i + 1 < len(sys.argv):
            return sys.argv[i + 1]
    for arg in sys.argv[1:]:
        if arg.startswith(flag + "="):
            return arg.split("=", 1)[1]
    return default


def main():
    base.TARGET = TARGETS
    base.core_events = revised_core_events
    base.summarize = revised_summarize
    base.markdown_report = revised_markdown_report
    base.main()

    # base.main historically stores scalar TARGET under each key. Rewrite the metadata to the revised vector.
    json_path = Path(output_arg("--json", "weight-search-report.json"))
    if json_path.exists():
        result = json.loads(json_path.read_text(encoding="utf-8"))
        result["targets"] = {"A": 0.10, "B": 0.10, "C": 0.075, "D": 0.075}
        result["target_definitions"] = {
            "A": "恰好1個細項大吉、總分非大吉、至少2個細項小凶或更差",
            "B": "恰好1個細項大凶、總分非大凶、至少2個細項小吉或更好",
            "C": "五細項全部至少平；不含總分",
            "D": "五細項全部至多平；不含總分",
        }
        json_path.write_text(json.dumps(result, ensure_ascii=False, indent=2), encoding="utf-8")


if __name__ == "__main__":
    main()
