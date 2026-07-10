#!/usr/bin/env python3
"""2단 파이프라인 프로토타입 — 엔진 사실 → LLM 렌더 실동 (G7).

스펙: config/llm.yml (역할 모델·캐싱 설계·생성 7계·렌더 스키마가 그대로 입력이 된다).
1단(엔진): 실난수 2d6 판정 — 수치는 여기서 확정되고 LLM은 관여하지 않는다.
2단(렌더): Claude 스트리밍 + 구조화 출력 + 프롬프트 캐싱. 2턴 연속 실행으로
          캐시 히트(cache_read_input_tokens > 0)와 첫 토큰 지연을 실측한다.

실행:  ANTHROPIC_API_KEY 필요.  python3 tools/pipeline_prototype.py
       키가 없으면 자동 드라이런 — 폴백 렌더러(엔진 원시 출력) 경로를 검증한다
       ("게임은 멈추지 않는다", llm.yml failure_handling).
"""
import json
import os
import random
import sys
import time
from pathlib import Path

import yaml

ROOT = Path(__file__).resolve().parent.parent
LLM = yaml.safe_load((ROOT / "config" / "llm.yml").read_text(encoding="utf-8"))
JUDGMENT = yaml.safe_load((ROOT / "config" / "judgment.yml").read_text(encoding="utf-8"))


# ─── 1단: 엔진 — 수치의 유일한 결정자 ───

def resolve_tier(margin: int) -> str:
    for tier in JUDGMENT["result_tiers"]:
        if tier["min_margin"] is not None and margin >= tier["min_margin"]:
            return tier["name"]
    return JUDGMENT["result_tiers"][-1]["name"]


def engine_turn(exec_base: int, resist: int, scene: dict) -> dict:
    roll = random.randint(1, 6) + random.randint(1, 6)
    margin = exec_base + roll - resist
    return {
        "scene": scene,
        "judgment": {
            "roll_2d6": roll, "exec": exec_base + roll, "resist": resist,
            "margin": margin, "tier": resolve_tier(margin),
        },
        # 엔진이 주는 선택지 골격 — LLM은 문장만 입힌다 (7계 ① 엔진 불가침)
        "choice_skeleton": scene["next_options"],
    }


# ─── 2단: 렌더 — 서사만 ───

def build_system() -> list:
    principles = "\n".join(
        f"- {p['id']}: {p['rule']}" for p in LLM["generation_principles"])
    fixed = (
        "너는 무협 텍스트 RPG '혼천'의 렌더러다. 입력된 엔진 사실을 서사로만 옮긴다.\n"
        "생성 7계 (위반 금지):\n" + principles
    )
    return [{"type": "text", "text": fixed, "cache_control": {"type": "ephemeral"}},
            {"type": "text", "text": "세션: 단이 — 14세 소년, 농가의 자식, 범인. 덫·사냥 2. 청하현 외곽."}]


def render_llm(client, facts: dict) -> dict:
    model = LLM["roles"]["turn_renderer"]["model"]
    t0 = time.monotonic()
    first_token = None
    chunks = []
    with client.messages.stream(
        model=model,
        max_tokens=1024,
        system=build_system(),
        output_config={"format": {"type": "json_schema", "schema": LLM["render_schema"]}},
        messages=[{"role": "user", "content": "엔진 사실:\n" + json.dumps(facts, ensure_ascii=False)}],
    ) as stream:
        for text in stream.text_stream:
            if first_token is None:
                first_token = time.monotonic() - t0
            chunks.append(text)
        final = stream.get_final_message()
    usage = final.usage
    return {
        "render": json.loads("".join(chunks)),
        "metrics": {
            "model": model,
            "first_token_s": round(first_token or 0, 3),
            "total_s": round(time.monotonic() - t0, 3),
            "input_tokens": usage.input_tokens,
            "cache_creation": usage.cache_creation_input_tokens,
            "cache_read": usage.cache_read_input_tokens,
            "output_tokens": usage.output_tokens,
        },
    }


def render_fallback(facts: dict) -> dict:
    """폴백 = 엔진 원시 출력 — 서사 없는 턴은 심심하지만 유효하다 (llm.yml failure_handling)."""
    j = facts["judgment"]
    return {
        "render": {
            "narration": f"[폴백] {facts['scene']['action']} — {j['tier']} (마진 {j['margin']:+d})",
            "choices": [{"id": o["id"], "text": o["hint"]} for o in facts["choice_skeleton"]],
        },
        "metrics": {"model": "fallback-template", "first_token_s": 0.0},
    }


def main() -> None:
    scenes = [
        {"place": "북쪽 산길 초입", "action": "덫 자리 조사",
         "next_options": [{"id": "set_trap", "hint": "덫을 놓는다"},
                          {"id": "track", "hint": "발자국을 따라간다"},
                          {"id": "return", "hint": "마을로 돌아간다"}]},
        {"place": "북쪽 산길 덫 근처", "action": "늑대 기척에 몸을 숨긴다",
         "next_options": [{"id": "lure", "hint": "덫으로 유인한다"},
                          {"id": "throw", "hint": "돌을 던져 주의를 끈다"},
                          {"id": "flee", "hint": "물러난다"}]},
    ]
    dry = "--dry-run" in sys.argv or not os.environ.get("ANTHROPIC_API_KEY")
    client = None
    if not dry:
        import anthropic
        client = anthropic.Anthropic()
    else:
        print("(드라이런 — ANTHROPIC_API_KEY 없음: 폴백 렌더러 경로 검증)\n")

    for i, scene in enumerate(scenes, 1):
        facts = engine_turn(exec_base=5, resist=10 + i, scene=scene)
        result = render_fallback(facts) if dry else render_llm(client, facts)
        r, m = result["render"], result["metrics"]
        print(f"── 턴 {i} │ 엔진: 2d6={facts['judgment']['roll_2d6']} "
              f"마진 {facts['judgment']['margin']:+d} → {facts['judgment']['tier']}")
        print(r["narration"])
        for c in r["choices"]:
            print(f"  [{c['id']}] {c['text']}")
        print(f"  ({json.dumps(m, ensure_ascii=False)})\n")

    if not dry:
        print("검증 포인트: 턴 2의 cache_read > 0 이면 프리픽스 캐싱 정상 —")
        print("            첫 토큰 지연 목표 1.5s 이내 (llm.yml latency_budget)")


if __name__ == "__main__":
    main()
