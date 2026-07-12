#!/usr/bin/env python3
"""게임 감사 — 혼천 게임 시스템의 눈과 자.

맵에는 `/혼천 검수`가 있고 리소스팩에는 `texture_audit.py`가 있다. 게임 시스템에는 없었다.
이 도구는 config/·core/ 를 읽어 두 가지를 한다:

  ① 정합 린트  — config 26+종이 서로를 참조한다. 참조가 깨지면 조용히 죽는다.
                 등록제 검증 / 엔진 키 대조 / 경지 사다리 철자 / 고아 절 / 생성 규칙 산술.
  ② 밸런스 시뮬 — config 수치를 그대로 읽어 가상 플레이어를 N일 굴린다.
                 성장 곡선 / 경제 수지 / 판정 분포(해석적) / 내력 수지.

봇·엔진 코드를 복제하지 않는다 — config 수치만으로 계산한다.
config 를 고치지 않는다 — 재기만 한다.

사용법:
    python3 tools/game_audit.py                # 전체
    python3 tools/game_audit.py --lint-only    # ① 정합 린트만
    python3 tools/game_audit.py --sim-only     # ② 밸런스 시뮬만
    python3 tools/game_audit.py --days 720     # 시뮬 기간 (기본 540)

외부 라이브러리 없음 (표준 라이브러리 + 자체 YAML 서브셋 파서).
종료 코드: 위반(❌) 1건 이상이면 1, 아니면 0.
"""

from __future__ import annotations

import argparse
import os
import re
import sys
from fractions import Fraction

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
CONFIG = os.path.join(ROOT, "config")
ENGINE_DIR = os.path.join(ROOT, "core", "src", "main", "java", "com", "honcheon", "core", "rules")


# ══════════════════════════════════════════════════════════════════════════════
#  YAML 서브셋 파서 (순수 파이썬 — 외부 의존 금지)
#  config/*.yml 이 쓰는 문법만 지원: 블록 매핑/시퀀스, 흐름 매핑/시퀀스, 주석,
#  따옴표 스칼라, 정수/실수/불리언/널, 무따옴표 한글 스칼라.
#  (앵커·별칭·복수행 블록 스칼라·문서 구분자는 config 에서 쓰지 않는다)
# ══════════════════════════════════════════════════════════════════════════════

class YamlError(Exception):
    pass


def _strip_comment(line: str) -> str:
    """따옴표 밖의 '#' 부터 잘라낸다 (겹따옴표 안의 \\" 이스케이프 존중)."""
    out, quote, i = [], None, 0
    while i < len(line):
        ch = line[i]
        if quote:
            out.append(ch)
            if ch == "\\" and quote == '"' and i + 1 < len(line):
                out.append(line[i + 1])
                i += 2
                continue
            if ch == quote:
                quote = None
        elif ch in "\"'":
            quote = ch
            out.append(ch)
        elif ch == "#" and (i == 0 or line[i - 1] in " \t"):
            break
        else:
            out.append(ch)
        i += 1
    return "".join(out).rstrip()


def _split_top(text: str, sep: str) -> list:
    """따옴표·괄호 깊이를 존중하며 sep 로 분할."""
    parts, buf, depth, quote, i = [], [], 0, None, 0
    while i < len(text):
        ch = text[i]
        if quote:
            buf.append(ch)
            if ch == "\\" and quote == '"' and i + 1 < len(text):
                buf.append(text[i + 1])
                i += 2
                continue
            if ch == quote:
                quote = None
        elif ch in "\"'":
            quote = ch
            buf.append(ch)
        elif ch in "[{":
            depth += 1
            buf.append(ch)
        elif ch in "]}":
            depth -= 1
            buf.append(ch)
        elif ch == sep and depth == 0:
            parts.append("".join(buf))
            buf = []
        else:
            buf.append(ch)
        i += 1
    parts.append("".join(buf))
    return parts


_ESCAPES = {"\\": "\\", '"': '"', "/": "/", "n": "\n", "t": "\t", "r": "\r",
            "b": "\b", "f": "\f", "0": "\0", "a": "\a", "e": "\x1b", " ": " "}


def _unescape(s: str) -> str:
    """겹따옴표 스칼라의 이스케이프 해석 — \\\\, \\", \\n, \\uXXXX …"""
    out, i = [], 0
    while i < len(s):
        ch = s[i]
        if ch != "\\" or i + 1 >= len(s):
            out.append(ch)
            i += 1
            continue
        nxt = s[i + 1]
        if nxt == "u" and i + 5 < len(s) + 1:
            try:
                out.append(chr(int(s[i + 2:i + 6], 16)))
                i += 6
                continue
            except ValueError:
                pass
        if nxt == "x" and i + 3 < len(s) + 1:
            try:
                out.append(chr(int(s[i + 2:i + 4], 16)))
                i += 4
                continue
            except ValueError:
                pass
        out.append(_ESCAPES.get(nxt, nxt))
        i += 2
    return "".join(out)


# YAML 1.1 불리언 — SnakeYAML(엔진)·PyYAML 이 실제로 이렇게 읽는다.
# 'on'/'off'/'yes'/'no' 를 무따옴표로 쓰면 키가 True/False 로 바뀐다 (linter 가 위험으로 잡는다).
_TRUE = {"true", "yes", "on", "y"}
_FALSE = {"false", "no", "off", "n"}


def _scalar(tok: str):
    tok = tok.strip()
    if not tok:
        return None
    if tok[0] in "\"'" and len(tok) >= 2 and tok[-1] == tok[0]:
        inner = tok[1:-1]
        return _unescape(inner) if tok[0] == '"' else inner.replace("''", "'")
    if tok.startswith("[") and tok.endswith("]"):
        inner = tok[1:-1].strip()
        return [] if not inner else [_scalar(p) for p in _split_top(inner, ",")]
    if tok.startswith("{") and tok.endswith("}"):
        inner = tok[1:-1].strip()
        if not inner:
            return {}
        out = {}
        for pair in _split_top(inner, ","):
            kv = _split_top(pair, ":")
            if len(kv) < 2:
                raise YamlError(f"흐름 매핑 파싱 실패: {pair!r}")
            out[_scalar(kv[0])] = _scalar(":".join(kv[1:]))
        return out
    low = tok.lower()
    if low in ("null", "~", ""):
        return None
    if low in _TRUE:
        return True
    if low in _FALSE:
        return False
    if re.fullmatch(r"[+-]?\d+", tok):
        return int(tok)
    if re.fullmatch(r"[+-]?(\d+\.\d*|\.\d+|\d+)([eE][+-]?\d+)?", tok):
        return float(tok)
    return tok


def _balanced(text: str) -> bool:
    """따옴표가 닫혔고 흐름 괄호 깊이가 0인가 — 여러 줄에 걸친 스칼라/흐름 시퀀스 판별."""
    quote, depth = None, 0
    for ch in text:
        if quote:
            if ch == quote:
                quote = None
        elif ch in "\"'":
            quote = ch
        elif ch in "[{":
            depth += 1
        elif ch in "]}":
            depth -= 1
    return quote is None and depth <= 0


def _key(tok: str):
    """매핑 키 — 문자열 유지가 원칙이나 수치 키(예: 12)는 그대로 둔다."""
    k = _scalar(tok)
    return k


BLOCK_SCALAR = re.compile(r"^(.*?):\s*([|>])([+-]?)\s*$")


def yaml_load(path: str):
    """블록 구조를 들여쓰기 스택으로 조립한다."""
    with open(path, encoding="utf-8") as fh:
        raw = fh.read().split("\n")

    # 여러 줄에 걸친 흐름 리스트/맵을 한 줄로 접는다 — 실제 YAML 은 이것을 허용하고
    # world_map.yml 이 실제로 그렇게 쓴다. 접지 않으면 파서가 "매핑이 아님"으로 죽는다.
    folded, buf, depth = [], None, 0
    for text in raw:
        stripped = _strip_comment(text)
        if buf is None:
            depth = stripped.count("[") - stripped.count("]") + stripped.count("{") - stripped.count("}")
            if depth > 0:
                buf = text.rstrip()
                continue
            folded.append(text)
        else:
            depth += stripped.count("[") - stripped.count("]") + stripped.count("{") - stripped.count("}")
            buf += " " + stripped.strip()
            if depth <= 0:
                folded.append(buf)
                buf = None
    if buf is not None:
        folded.append(buf)

    # "key:" 다음 줄에 흐름 값이 오는 형태(YAML 이 허용한다)도 붙인다 —
    #   실지명_중원:
    #     [장안, 낙양, ...]
    merged, skip = [], False
    for idx, text in enumerate(folded):
        if skip:
            skip = False
            continue
        body = _strip_comment(text).rstrip()
        if body.endswith(":") and idx + 1 < len(folded):
            nxt = _strip_comment(folded[idx + 1]).strip()
            if nxt.startswith("[") or nxt.startswith("{"):
                merged.append(body + " " + nxt)
                skip = True
                continue
        merged.append(text)
    raw = merged

    # 토큰화 — (indent, 본문, 줄번호[, 미리계산된 값])
    lines = []
    i = 0
    while i < len(raw):
        text = raw[i]
        if not text.strip() or text.lstrip().startswith("#"):
            i += 1
            continue
        indent = len(text) - len(text.lstrip(" "))

        # ── 블록 스칼라 (key: | / key: >) — 주석 제거 금지, 들여쓴 블록을 통째로 먹는다
        m = BLOCK_SCALAR.match(_strip_comment(text).rstrip())
        if m and not m.group(1).strip().startswith("- "):
            key_part, style, chomp = m.group(1).strip(), m.group(2), m.group(3)
            i += 1
            block = []
            while i < len(raw):
                nxt = raw[i]
                if not nxt.strip():
                    block.append("")
                    i += 1
                    continue
                nxt_indent = len(nxt) - len(nxt.lstrip(" "))
                if nxt_indent <= indent:
                    break
                block.append(nxt)
                i += 1
            while block and not block[-1].strip():
                block.pop()
            if block:
                base = min(len(b) - len(b.lstrip(" ")) for b in block if b.strip())
                body_lines = [b[base:] if b.strip() else "" for b in block]
            else:
                body_lines = []
            if style == "|":
                value = "\n".join(body_lines)
                value += "" if chomp == "-" else "\n"
            else:  # '>' 접힘 — 빈 줄은 문단 구분
                folded, buf = [], []
                for bl in body_lines:
                    if bl.strip():
                        buf.append(bl.strip())
                    else:
                        folded.append(" ".join(buf))
                        buf = []
                folded.append(" ".join(buf))
                value = "\n".join(folded)
                value += "" if chomp == "-" else "\n"
            lines.append((indent, f"{key_part}:", i, value))
            continue

        # ── 일반 줄 — 따옴표·괄호가 안 닫혔으면 다음 줄을 이어 붙인다
        body = _strip_comment(text)
        no = i + 1
        i += 1
        while not _balanced(body) and i < len(raw):
            body += " " + _strip_comment(raw[i]).strip()
            i += 1
        if not body.strip():
            continue
        lines.append((indent, body.strip(), no))

    def parse_block(i: int, indent: int):
        """indent 이상 들여쓴 연속 블록을 파싱해 (값, 다음 인덱스) 반환."""
        if i >= len(lines):
            return None, i
        cur_indent = lines[i][0]
        if lines[i][1].startswith("- "):  # 시퀀스
            seq = []
            while i < len(lines) and lines[i][0] == cur_indent and lines[i][1].startswith("- "):
                item = lines[i][1][2:].strip()
                no = lines[i][2]
                i += 1
                # '- key: value' 형태 = 인라인 매핑 항목
                if item and not item.startswith(("[", "{")) and _looks_like_pair(item):
                    node, i = _inline_map_item(item, i, cur_indent, no)
                    seq.append(node)
                elif item:
                    seq.append(_scalar(item))
                else:  # '-' 단독 → 다음 줄부터 중첩
                    child, i = parse_block(i, cur_indent + 1)
                    seq.append(child)
            return seq, i
        # 매핑
        mp = {}
        while i < len(lines) and lines[i][0] == cur_indent and not lines[i][1].startswith("- "):
            body, no = lines[i][1], lines[i][2]
            kv = _split_top(body, ":")
            if len(kv) < 2:
                raise YamlError(f"{os.path.basename(path)}:{no} 매핑이 아님: {body!r}")
            key = _key(kv[0])
            rest = ":".join(kv[1:]).strip()
            literal = lines[i][3] if len(lines[i]) > 3 else None
            i += 1
            if literal is not None:
                mp[key] = literal
            elif rest:
                mp[key] = _scalar(rest)
            else:
                # 자식 블록: 더 깊거나(매핑) 같은 깊이의 시퀀스
                if i < len(lines) and (
                    lines[i][0] > cur_indent
                    or (lines[i][0] == cur_indent and lines[i][1].startswith("- "))
                ):
                    mp[key], i = parse_block(i, cur_indent + 1)
                else:
                    mp[key] = None
        return mp, i

    def _looks_like_pair(item: str) -> bool:
        kv = _split_top(item, ":")
        return len(kv) >= 2

    def _inline_map_item(item, i, cur_indent, no):
        """'- key: value' 로 시작하는 시퀀스 항목의 매핑 본문을 이어 읽는다."""
        mp = {}
        kv = _split_top(item, ":")
        key = _key(kv[0])
        rest = ":".join(kv[1:]).strip()
        item_indent = cur_indent + 2  # '- ' 만큼 밀린 자식 키들
        if rest:
            mp[key] = _scalar(rest)
        else:
            if i < len(lines) and lines[i][0] > item_indent - 1 and lines[i][0] != item_indent:
                mp[key], i = parse_block(i, lines[i][0])
            elif i < len(lines) and lines[i][0] > item_indent:
                mp[key], i = parse_block(i, lines[i][0])
            else:
                mp[key] = None
        # 같은 항목에 속한 나머지 키들 (indent == item_indent)
        while i < len(lines) and lines[i][0] == item_indent and not lines[i][1].startswith("- "):
            body2, no2 = lines[i][1], lines[i][2]
            kv2 = _split_top(body2, ":")
            if len(kv2) < 2:
                raise YamlError(f"{os.path.basename(path)}:{no2} 매핑이 아님: {body2!r}")
            k2 = _key(kv2[0])
            rest2 = ":".join(kv2[1:]).strip()
            literal2 = lines[i][3] if len(lines[i]) > 3 else None
            i += 1
            if literal2 is not None:
                mp[k2] = literal2
            elif rest2:
                mp[k2] = _scalar(rest2)
            elif i < len(lines) and (
                lines[i][0] > item_indent
                or (lines[i][0] == item_indent and lines[i][1].startswith("- "))
            ):
                mp[k2], i = parse_block(i, item_indent + 1)
            else:
                mp[k2] = None
        return mp, i

    value, idx = parse_block(0, 0)
    if idx != len(lines):
        raise YamlError(f"{os.path.basename(path)}: {lines[idx][2]}행에서 구조가 끊김")
    return value


# ══════════════════════════════════════════════════════════════════════════════
#  보고서 수집기
# ══════════════════════════════════════════════════════════════════════════════

OK, WARN, FAIL = "✅", "⚠️", "❌"


class Report:
    def __init__(self):
        self.lines = []
        self.violations = []   # ❌
        self.warnings = []     # ⚠️

    def head(self, text):
        self.lines.append("")
        self.lines.append(f"── {text} " + "─" * max(0, 66 - len(text)))

    def say(self, text=""):
        self.lines.append(text)

    def ok(self, text):
        self.lines.append(f"  {OK} {text}")

    def warn(self, text):
        self.lines.append(f"  {WARN} {text}")
        self.warnings.append(text)

    def fail(self, text):
        self.lines.append(f"  {FAIL} {text}")
        self.violations.append(text)

    def verdict(self, cond, text):
        (self.ok if cond else self.fail)(text)

    def dump(self):
        print("\n".join(self.lines))


# ══════════════════════════════════════════════════════════════════════════════
#  config 적재
# ══════════════════════════════════════════════════════════════════════════════

def load_all():
    cfg = {}
    for dirpath, _dirs, files in os.walk(CONFIG):
        for fn in sorted(files):
            if not fn.endswith((".yml", ".yaml")):
                continue
            full = os.path.join(dirpath, fn)
            rel = os.path.relpath(full, CONFIG).replace(os.sep, "/")
            cfg[rel] = yaml_load(full)
    return cfg


def walk_keys(node, prefix=""):
    """모든 (경로, 키) 를 훑는다."""
    if isinstance(node, dict):
        for k, v in node.items():
            path = f"{prefix}.{k}" if prefix else str(k)
            yield path, k, v
            yield from walk_keys(v, path)
    elif isinstance(node, list):
        for n, v in enumerate(node):
            path = f"{prefix}[{n}]"
            yield from walk_keys(v, path)


def all_strings(node):
    """노드 아래 모든 문자열(키+값)."""
    out = set()
    if isinstance(node, dict):
        for k, v in node.items():
            if isinstance(k, str):
                out.add(k)
            out |= all_strings(v)
    elif isinstance(node, list):
        for v in node:
            out |= all_strings(v)
    elif isinstance(node, str):
        out.add(node)
    return out


def dig(node, *path, default=None):
    for p in path:
        if isinstance(node, dict) and p in node:
            node = node[p]
        elif isinstance(node, list) and isinstance(p, int) and p < len(node):
            node = node[p]
        else:
            return default
    return node


def num(v, default=0.0):
    if isinstance(v, bool):
        return default
    if isinstance(v, (int, float)):
        return float(v)
    return default


def mid(v, default=0.0):
    """[a, b] 범위면 중간값, 숫자면 그대로."""
    if isinstance(v, list) and v and all(isinstance(x, (int, float)) for x in v):
        return sum(float(x) for x in v) / len(v)
    return num(v, default)


# ══════════════════════════════════════════════════════════════════════════════
#  ① 정합 린트
# ══════════════════════════════════════════════════════════════════════════════

def lint(cfg, rep):
    rep.say()
    rep.say("═" * 72)
    rep.say("  ① 정합 린트 — config·엔진·문서의 모순")
    rep.say("═" * 72)

    lint_registries(cfg, rep)
    lint_realm_ladder(cfg, rep)
    lint_engine_keys(cfg, rep)
    lint_creation_arithmetic(cfg, rep)
    lint_gate_coherence(cfg, rep)
    lint_orphans(cfg, rep)
    audit_weapon_calls(rep)   # 조성기가 못 만드는 병기를 요구하면 그 지역이 안 선다


# ── 등록부 ────────────────────────────────────────────────────────────────────

ID_RE = re.compile(r"[a-z][a-zA-Z0-9_]{2,}$")   # id 꼴 — 소문자 시작 (한글 이름·산문은 별도 취급)


def is_name_like(v):
    """등록부를 가리키려는 '이름'인가, 그냥 산문인가.

    'gwangun' · '개방' 은 이름. '소속 세력' · '정파·관군 주목 축 상승 — …' 은 산문이다.
    산문을 미등록 참조로 신고하면 보고서가 쓰레기가 된다.
    """
    if not isinstance(v, str) or not v.strip():
        return False
    if ID_RE.fullmatch(v):
        return True
    if any(ch in v for ch in " —·,()<>=/'\""):
        return False
    return len(v) <= 8 and all("가" <= ch <= "힣" or ch == "_" for ch in v)


def registries(cfg):
    """명사의 등록부 — 이름 → 실재하는 id 집합. 등록부의 '실제' 경로를 쓴다."""
    reg = {}

    # 세력: factions.yml 은 2층 — faction_groups.<group>.members.<id>
    groups, members, korean = set(), set(), set()
    for gid, grp in (dig(cfg, "factions.yml", "faction_groups", default={}) or {}).items():
        groups.add(gid)
        for fid, f in (dig(grp, "members", default={}) or {}).items():
            members.add(fid)
            if isinstance(f, dict):
                if isinstance(f.get("name"), str):
                    korean.add(f["name"])
                # 하위 명단(문파·세가)은 그 자체가 다른 파일이 부르는 이름이다
                for sub in ("sects", "clans"):
                    for s in (f.get(sub) or []):
                        if isinstance(s, str):
                            korean.add(s)
    reg["세력_그룹"] = groups
    reg["세력_구성원"] = members
    reg["세력_한글명"] = korean
    reg["세력"] = groups | members | korean

    reg["NPC"] = set(dig(cfg, "npcs/cheongha_npcs.yml", "npcs", default={}) or {})
    region = cfg.get("regions/cheongha_hyeon.yml") or {}
    reg["장소"] = set(region.get("locations") or {})
    reg["사건"] = set(region.get("incidents") or {})
    reg["무공"] = set(dig(cfg, "skills.yml", "martial_arts", default={}) or {})
    reg["심법"] = set(dig(cfg, "simbeop.yml", "simbeop", default={}) or {})
    reg["기연"] = set(dig(cfg, "fortune_encounters.yml", "encounters", default={}) or {})
    reg["장events"] = set(dig(cfg, "chapter_events.yml", "chapter_events", default={}) or {})
    reg["지역델타"] = set(dig(cfg, "region_state.yml", "event_deltas", default={}) or {})
    reg["문파계급"] = {r["rank"] for r in (dig(cfg, "sect_life.yml", "rank_ladder", "player_ranks", default=[]) or [])
                    if isinstance(r, dict) and isinstance(r.get("rank"), str)}
    return reg


# 필드 이름 → 어느 등록부를 가리키는가
NPC_FIELDS = {"npc", "npc_ref", "heir", "standin", "standin_secondary", "issuer",
              "via", "by", "related_npcs", "linked_npcs", "npcs", "members"}
LOC_FIELDS = {"location", "start_location", "target"}
FAC_FIELDS = {"faction", "factions", "group"}


def lint_registries(cfg, rep):
    rep.head("등록제 — 참조된 명사가 등록부에 실재하는가")
    reg = registries(cfg)

    rep.say(f"     등록부: 세력 {len(reg['세력_구성원'])}(+그룹 {len(reg['세력_그룹'])}, 한글명 {len(reg['세력_한글명'])}) "
            f"· NPC {len(reg['NPC'])} · 장소 {len(reg['장소'])} · 사건 {len(reg['사건'])}")
    rep.say(f"             무공 {len(reg['무공'])} · 심법 {len(reg['심법'])} · 기연 {len(reg['기연'])} "
            f"· 장 사건 {len(reg['장events'])} · 지역 델타 {len(reg['지역델타'])}")
    rep.say("")

    bad_npc, bad_loc, bad_fac = {}, {}, {}
    reaction_inputs = set(dig(cfg, "faction_reaction.yml", "inputs", default={}) or {})

    for rel, doc in sorted(cfg.items()):
        # schema 절은 필드 설명서지 데이터가 아니다
        for path, key, val in walk_keys(doc):
            if not isinstance(key, str) or path.startswith("schema"):
                continue
            cand = val if isinstance(val, list) else [val]

            # ── NPC 참조
            if key in NPC_FIELDS:
                for c in cand:
                    if not isinstance(c, str) or not ID_RE.fullmatch(c) or c in reg["NPC"]:
                        continue
                    # members 는 NPC 목록이 아닐 수도 있다 (factions.yml 등) — NPC 문맥에서만
                    if key == "members" and "npc" not in rel:
                        continue
                    if key in ("target", "via", "by") and c in reg["장소"] | reg["기연"]:
                        continue
                    bad_npc.setdefault(c, []).append(f"{rel} {path}")

            # ── 장소 참조
            if key in LOC_FIELDS:
                for c in cand:
                    if not isinstance(c, str) or not ID_RE.fullmatch(c):
                        continue
                    if c in reg["장소"] or c == "cheongha_hyeon":
                        continue
                    if c in reg["NPC"] | reg["기연"] | reg["세력"]:
                        continue   # target: 다른 등록부를 가리키는 경우
                    bad_loc.setdefault(c, []).append(f"{rel} {path}")

            # ── 세력 참조
            if key in FAC_FIELDS:
                for c in cand:
                    if not is_name_like(c):
                        continue
                    if c in reg["세력"] or c in ("무소속", "미상", "없음", "해당_문파"):
                        continue
                    if c in reaction_inputs:
                        rep.fail(f"{rel} {path} = '{c}' — 'faction:' 키에 세력 id 가 아니라 "
                                 f"faction_reaction.yml inputs 의 반응 입력명이 들어 있다 (키 이름이 틀렸다)")
                        continue
                    bad_fac.setdefault(c, []).append(f"{rel} {path}")

    def dump(title, bucket, kind, level):
        if not bucket:
            return 0
        rep.say(f"     ▸ {title}")
        for name in sorted(bucket):
            where = bucket[name]
            shown = "; ".join(where[:2]) + (f" 외 {len(where) - 2}곳" if len(where) > 2 else "")
            level(f"미등록 {kind} '{name}' — {shown}")
        rep.say("")
        return len(bucket)

    n = 0
    n += dump("NPC 참조", bad_npc, "NPC", rep.fail)
    n += dump("장소 참조 (regions/cheongha_hyeon.yml locations 등록부 기준)", bad_loc, "장소", rep.fail)
    n += dump("세력 참조 (factions.yml faction_groups 기준)", bad_fac, "세력", rep.fail)

    # ── 기술/심법 id 등록부
    for sid in (dig(cfg, "skill_mechanics.yml", "skills", default={}) or {}):
        s = dig(cfg, "skill_mechanics.yml", "skills", sid)
        if isinstance(s, dict) and s.get("npc_only"):
            continue
        if sid not in reg["무공"] | reg["심법"]:
            rep.fail(f"skill_mechanics.yml skills.{sid} — skills.yml martial_arts / simbeop.yml 어느 카탈로그에도 없다")
            n += 1

    # ── 문파 계급 (sect_life_entry.maps_to)
    for rel, doc in sorted(cfg.items()):
        for path, key, val in walk_keys(doc):
            if key == "maps_to" and isinstance(val, str) and reg["문파계급"] and val not in reg["문파계급"]:
                rep.fail(f"{rel} {path} = '{val}' — sect_life.yml rank_ladder.player_ranks "
                         f"({', '.join(sorted(reg['문파계급']))}) 에 없는 계급")
                n += 1

    # ── 사건 참조 (접미사 붙은 id 포함)
    for rel, doc in sorted(cfg.items()):
        for path, key, val in walk_keys(doc):
            if key == "incident" and isinstance(val, str) and val not in reg["사건"]:
                stem = next((i for i in reg["사건"] if val.startswith(i)), None)
                if stem:
                    rep.fail(f"{rel} {path} = '{val}' — 사건 id '{stem}' 에 접미사 '{val[len(stem):]}' 가 붙어 있다 "
                             f"(id 가 아니라 id+상태. 문자열 대조가 조용히 빗나간다)")
                    n += 1
                elif is_name_like(val):
                    rep.fail(f"{rel} {path} → 미등록 사건 '{val}'")
                    n += 1

    # ── 지표 철자 (quest_generation ↔ region_state)
    qg_axes = set(dig(cfg, "quest_generation.yml", "sources", "region_state", default={}) or {})
    rs_axes = set(dig(cfg, "region_state.yml", "threshold_effects", default={}) or {})
    if qg_axes and rs_axes and not (qg_axes & rs_axes):
        rep.fail(f"quest_generation.yml sources.region_state 키 {sorted(qg_axes)} 와 "
                 f"region_state.yml threshold_effects 키 {sorted(rs_axes)} 가 하나도 겹치지 않는다 "
                 f"— 같은 임계를 두 철자로 부른다 (조인 불가, 의미로만 이어져 있다)")
        n += 1
    # 임계마다 대응하는 의뢰 발생원이 실제로 있는지 — quest_generation 을 조회해서 판정한다
    # (조회 없이 이름만 보고 경고하면 이미 넣어둔 발생원까지 오탐한다 — v1 도구의 실수)
    for a in sorted(rs_axes - qg_axes):
        rep.warn(f"region_state.yml threshold_effects.{a} — 대응하는 의뢰 발생원이 quest_generation.yml "
                 f"sources.region_state 에 없다 (임계를 넘겨도 세계가 반응하지 않는다)")
        n += 1

    # ── id 충돌 (같은 id 가 두 등록부에)
    dup = reg["무공"] & reg["심법"]
    for d in sorted(dup):
        rep.warn(f"id '{d}' 가 skills.yml martial_arts 와 simbeop.yml simbeop 양쪽에 존재 — "
                 f"단일 id 공간을 가정한 참조(skill_mechanics 등)가 어느 쪽인지 모른다")
        n += 1

    # ── YAML 1.1 불리언 키 위험 (엔진 SnakeYAML 도 동일하게 읽는다)
    for rel, doc in sorted(cfg.items()):
        for path, key, val in walk_keys(doc):
            if isinstance(key, bool):
                rep.fail(f"{rel} {path} — 무따옴표 키가 YAML 1.1 불리언으로 강제 변환됐다 "
                         f"(on/off/yes/no). 엔진(SnakeYAML)도 똑같이 읽어 키 조회가 실패한다. 따옴표 필요")
                n += 1

    if not n:
        rep.ok("모든 교차 참조가 등록부에서 해소된다")


# ── 경지 사다리 ───────────────────────────────────────────────────────────────

def realm_names(cfg):
    stages = dig(cfg, "cultivation.yml", "cultivation_stages", default=[]) or []
    return [s.get("name") for s in stages if isinstance(s, dict) and s.get("name")]


def lint_realm_ladder(cfg, rep):
    rep.head("경지 사다리 — 9경지 이름이 전 파일에서 같은 철자인가")
    names = realm_names(cfg)
    rep.say(f"     정본(cultivation.yml): {' → '.join(names)}")
    if len(names) != 9:
        rep.fail(f"cultivation_stages 가 9경지가 아니다 ({len(names)}단)")

    canon = set(names)
    # 경지 이름을 키로 쓰는 표들
    tables = [
        ("internal_energy.yml", "realm_gates", dig(cfg, "internal_energy.yml", "realm_gates", default={}), {"범인"}),
        ("player_creation.yml", "attribute_cap_by_realm",
         dig(cfg, "player_creation.yml", "attribute_cap_by_realm", default={}), set()),
    ]
    bad = 0
    for fname, sect, table, exempt in tables:
        if not isinstance(table, dict):
            continue
        keys = set(table)
        unknown = keys - canon
        missing = canon - keys - exempt
        for u in sorted(unknown):
            rep.fail(f"{fname} {sect}.'{u}' — cultivation.yml 9경지에 없는 이름")
            bad += 1
        if missing:
            rep.warn(f"{fname} {sect} — 경지 누락: {', '.join(sorted(missing))}")
            bad += 1

    # 기 발현 격의 gate 가 경지 이름인가
    for gid, g in (dig(cfg, "qi_manifestation.yml", "grades", default={}) or {}).items():
        gate = dig(g, "gate")
        if isinstance(gate, str) and gate not in canon:
            rep.fail(f"qi_manifestation.yml grades.{gid}.gate '{gate}' — 9경지에 없는 이름")
            bad += 1

    # 오의 사다리
    for path, key, val in walk_keys(cfg.get("ultimate_arts.yml") or {}):
        if key in ("realm", "gate", "realm_gate") and isinstance(val, str) and val not in canon:
            if not any(ch.isspace() for ch in val):
                rep.warn(f"ultimate_arts.yml {path} = '{val}' — 9경지 철자와 불일치")
                bad += 1

    # 승급 요건에 등장하는 경지 상당 표기 (beast_ranks 등)
    for path, key, val in walk_keys(cfg.get("cultivation.yml") or {}):
        if key == "rank" and isinstance(val, str):
            base = val.replace("_상당", "").replace("_이상", "")
            if base not in canon:
                rep.fail(f"cultivation.yml {path} = '{val}' — 9경지에 없는 경지 '{base}'")
                bad += 1

    if not bad:
        rep.ok("경지 이름이 전 파일에서 동일 철자로 쓰인다")


# ── 엔진 키 대조 ──────────────────────────────────────────────────────────────

JAVADOC_CFG = re.compile(r"config/([\w/]+\.yml)")
ASSIGN = re.compile(r"(?:Map<String,\s*Object>\s+)?(?:this\.)?(\w+)\s*=\s*(RulesConfig\.section\(.*?\));", re.S)
SECTION_HEAD = re.compile(r"RulesConfig\.section\(\s*")
GET_CALL = re.compile(r"\b(\w+)\.get\(\s*\"([^\"]+)\"\s*\)")

MISSING = object()   # '이 키는 config 에 없다' 표지 — None(널 값)과 구별


def _eval_section(expr, env, sink):
    """RulesConfig.section(<expr>, "key") 를 재귀 해석해 YAML 노드를 돌려준다.

    중첩(section(section(a,"x"),"y"))을 제대로 따라간다 — 이걸 못하면
    EconomyEngine 의 bank·PartyEngine 의 group 이 엉뚱한 노드에 묶여 허위 경보가 난다.
    sink: (기반노드표현, 키, 성공여부) 를 받아 기록.
    """
    expr = expr.strip()
    m = SECTION_HEAD.match(expr)
    if not m:
        return env.get(expr, MISSING if expr not in env else env[expr])
    # 괄호 균형으로 인자 두 개를 가른다
    depth, start = 1, m.end()
    i, args, cur = start, [], []
    while i < len(expr) and depth:
        ch = expr[i]
        if ch == "(":
            depth += 1
        elif ch == ")":
            depth -= 1
            if depth == 0:
                break
        if ch == "," and depth == 1:
            args.append("".join(cur))
            cur = []
        else:
            cur.append(ch)
        i += 1
    args.append("".join(cur))
    if len(args) < 2:
        return MISSING
    base = _eval_section(args[0], env, sink)
    raw_key = args[1].strip()
    if not (len(raw_key) >= 2 and raw_key[0] == '"' and raw_key[-1] == '"'):
        return MISSING          # 키가 자바 변수다 (예: section(priceTable, category)) — 정적 검사 불가
    key = raw_key[1:-1]
    if not isinstance(base, dict):
        return MISSING
    ok = key in base
    sink(args[0].strip(), key, ok)
    return base[key] if ok else MISSING


def lint_engine_keys(cfg, rep):
    """엔진(core/**/rules/*.java)이 읽는 config 키가 실제로 있는가.

    변수 → YAML 노드를 얕게 추적한다:
      · 생성자 파라미터 이름이 config 파일명과 겹치면 그 파일의 루트로 묶는다
      · x = RulesConfig.section(y, "k")  →  x 는 y 의 하위 노드 k  (중첩 해석)
      · x.get("k")                       →  k 가 x 에 있어야 한다
    추적 불가한 변수는 건너뛴다 (허위 경보 방지).
    """
    rep.head("엔진 키 대조 — core/rules/*.java 가 읽는 config 키가 실재하는가")
    if not os.path.isdir(ENGINE_DIR):
        rep.warn(f"엔진 디렉터리 없음: {ENGINE_DIR}")
        return

    checked = miss = 0
    for fn in sorted(os.listdir(ENGINE_DIR)):
        if not fn.endswith(".java") or fn == "RulesConfig.java":
            continue
        src = open(os.path.join(ENGINE_DIR, fn), encoding="utf-8").read()
        cfg_files = [c for c in JAVADOC_CFG.findall(src) if c in cfg]
        if not cfg_files:
            continue

        env = {}
        for pname in re.findall(r"Map<String,\s*Object>\s+(\w+)\s*[,)]", src):
            hit = [c for c in cfg_files if os.path.basename(c)[:-4] in (pname, pname.lower())]
            if hit:
                env[pname] = cfg[hit[0]]
            elif pname in ("config", "rules") and len(cfg_files) == 1:
                env[pname] = cfg[cfg_files[0]]

        reported = []

        def sink(base_expr, key, ok):
            nonlocal checked, miss
            checked += 1
            if not ok:
                reported.append(f"{fn}: RulesConfig.section(…, \"{key}\") — '{key}' 절이 config 에 없다")
                miss += 1

        # 대입식을 순서대로 평가해 변수 환경을 키운다
        for m in ASSIGN.finditer(src):
            var, expr = m.group(1), m.group(2)
            node = _eval_section(expr, env, sink)
            if node is not MISSING:
                env[var] = node

        # 대입되지 않고 인라인으로 쓰인 section(...) 도 검사
        for m in re.finditer(r"RulesConfig\.section\([^;]*?\)", src):
            _eval_section(m.group(0), env, sink)

        # x.get("k")
        for m in GET_CALL.finditer(src):
            var, key = m.group(1), m.group(2)
            node = env.get(var)
            if not isinstance(node, dict):
                continue
            checked += 1
            if key not in node:
                reported.append(f"{fn}: {var}.get(\"{key}\") — config 에 '{key}' 키가 없다")
                miss += 1

        for r in dict.fromkeys(reported):
            rep.fail(r)

    rep.say(f"     추적 가능한 키 접근 {checked}건 검사")
    if not miss:
        rep.ok("엔진이 읽는 키가 모두 config 에 실재한다")


# ── 생성 규칙 산술 ────────────────────────────────────────────────────────────

def lint_creation_arithmetic(cfg, rep):
    rep.head("캐릭터 생성 — 프리셋이 자기 규칙을 지키는가")
    pc = cfg.get("player_creation.yml") or {}
    alloc = pc.get("attribute_allocation") or {}
    base = int(num(alloc.get("base_value"), 2))
    free = int(num(alloc.get("free_points"), 6))
    cmax = int(num(alloc.get("creation_max"), 4))
    cmin = int(num(alloc.get("creation_min"), 1))
    attrs = dig(cfg, "judgment.yml", "attributes", default=[]) or []
    n_attr = len(attrs)
    caps = pc.get("attribute_cap_by_realm") or {}
    start_realm = dig(pc, "age_and_lifepath", "starting_realm", default="범인")
    realm_cap = int(num(caps.get(start_realm), 99))

    rep.say(f"     규칙: 기본 {base} × 능력치 {n_attr} + 자유 {free} = 총합 {base * n_attr + free}, "
            f"생성 상한 {cmax}, 하한 {cmin} / 시작 경지 '{start_realm}' 능력치 상한 {realm_cap}")

    bad = 0
    if cmax > realm_cap:
        rep.fail(f"attribute_allocation.creation_max({cmax}) > attribute_cap_by_realm.{start_realm}({realm_cap}) "
                 f"— 생성 규칙이 시작 경지의 상한을 넘는다")
        bad += 1

    total_expected = base * n_attr + free
    for pid, preset in (pc.get("disposition_presets") or {}).items():
        stats = dig(preset, "stats", default=[]) or []
        if not stats or not all(isinstance(s, (int, float)) for s in stats):
            continue
        s = sum(int(x) for x in stats)
        if len(stats) != n_attr:
            rep.fail(f"disposition_presets.{pid}.stats 항목 {len(stats)}개 — judgment.yml attributes({n_attr}종)와 불일치")
            bad += 1
        if s != total_expected:
            rep.fail(f"disposition_presets.{pid} 총합 {s} ≠ 규칙 총합 {total_expected}")
            bad += 1
        over = [(attrs[i] if i < len(attrs) else f"#{i}", v) for i, v in enumerate(stats) if v > cmax]
        for name, v in over:
            rep.fail(f"disposition_presets.{pid}.{name} = {v} > creation_max({cmax})")
            bad += 1
        overr = [(attrs[i] if i < len(attrs) else f"#{i}", v)
                 for i, v in enumerate(stats) if v > realm_cap and v <= cmax]
        for name, v in overr:
            rep.fail(f"disposition_presets.{pid}.{name} = {v} > 시작 경지 '{start_realm}' 상한({realm_cap})")
            bad += 1
        under = [v for v in stats if v < cmin]
        if under:
            rep.fail(f"disposition_presets.{pid} 하한 위반 {under} < creation_min({cmin})")
            bad += 1

    # v2 나이 브래킷과 프리셋의 정합 — v2 는 전원 유년/소년 시작이라 프리셋이 그대로는 못 쓰인다
    brackets = dig(pc, "age_and_lifepath", "age_brackets", default={}) or {}
    presets = pc.get("disposition_presets") or {}
    if brackets and presets:
        bsum = {b: int(num(v.get("base_value"), 0)) * n_attr + int(num(v.get("free_points"), 0))
                for b, v in brackets.items() if isinstance(v, dict)}
        bmax = {b: int(num(v.get("creation_max"), 0)) for b, v in brackets.items() if isinstance(v, dict)}
        preset_sums = {sum(int(x) for x in (p.get("stats") or []))
                       for p in presets.values() if isinstance(p, dict) and p.get("stats")}
        if preset_sums and not (preset_sums & set(bsum.values())):
            rep.fail(f"disposition_presets 총합 {sorted(preset_sums)} 이 age_brackets 총합 {bsum} 중 어느 것과도 맞지 않는다 "
                     f"— v2(전원 유년/소년 시작)에서 프리셋을 그대로 쓸 수 없다")
            bad += 1
        preset_max = max((max(p["stats"]) for p in presets.values()
                          if isinstance(p, dict) and p.get("stats")), default=0)
        if bmax and preset_max > max(bmax.values()):
            rep.fail(f"disposition_presets 최고치 {preset_max} > age_brackets 최대 creation_max {max(bmax.values())} "
                     f"({bmax}) — 아이의 몸이 감당 못하는 프리셋")
            bad += 1

    if not bad:
        rep.ok("프리셋이 배분 규칙·경지 상한·나이 브래킷을 지킨다")


# ── 게이트 정합 (경지 ↔ 자원) ─────────────────────────────────────────────────

def lint_gate_coherence(cfg, rep):
    rep.head("게이트 정합 — 열어준 능력을 실제로 쓸 수 있는가")
    names = realm_names(cfg)
    gates = dig(cfg, "internal_energy.yml", "realm_gates", default={}) or {}
    bands = dig(cfg, "internal_energy.yml", "cost_bands", default={}) or {}
    stages = {s["name"]: s for s in (dig(cfg, "cultivation.yml", "cultivation_stages", default=[]) or [])
              if isinstance(s, dict) and s.get("name")}
    bad = 0

    # 개화(단전 개방)가 요건인 최초 경지 = 내공/내력이 0 이 아닌 최초 경지
    bloom_realm = None
    for nm in names:
        reqs = dig(stages.get(nm, {}), "promotion", "requirements", default=[]) or []
        if any(isinstance(r, str) and "개화" in r for r in reqs):
            bloom_realm = nm
            break

    if bloom_realm:
        bloom_idx = names.index(bloom_realm)
        rep.say(f"     개화(단전 개방) 요건 경지 = '{bloom_realm}' → 그 이전 경지는 내공 0 = 내력 0")
        for realm, allowed in gates.items():
            if realm not in names:
                continue
            if names.index(realm) >= bloom_idx:
                continue
            for band in (allowed or []):
                cost = mid(dig(bands, band, "cost"), 0.0)
                if cost > 0:
                    rep.fail(f"internal_energy.yml realm_gates.{realm} — '{band}'(내력 {cost:g})를 열어주지만, "
                             f"'{realm}'은 개화 이전이라 내공 0 = 내력 0. 시전 자체가 불가능하다. "
                             f"열어놓고 못 쓰는 문 (qi_manifestation.yml grades.발경.gate 도 "
                             f"'{dig(cfg, 'qi_manifestation.yml', 'grades', '발경', 'gate')}'로 동일)")
                    bad += 1

    # 승급 요건 내공치가 심법 용량으로 도달 가능한가
    caps = {sid: num(dig(sb, "capacity"), 0) for sid, sb in
            (dig(cfg, "simbeop.yml", "simbeop", default={}) or {}).items() if isinstance(sb, dict)}
    if caps:
        best = max(caps.values())
        for nm in names:
            reqs = dig(stages.get(nm, {}), "promotion", "requirements", default=[]) or []
            for r in reqs:
                m = re.search(r"내공\s*(\d+)", str(r))
                if not m:
                    continue
                need = int(m.group(1))
                viable = [sid for sid, c in caps.items() if c >= need]
                if not viable:
                    rep.fail(f"'{nm}' 승급 요건 '내공 {need}' — 어떤 심법도 용량이 못 미친다 (최대 {best:g})")
                    bad += 1
                elif len(viable) == 1:
                    rep.warn(f"'{nm}' 승급 요건 '내공 {need}' — 도달 가능한 심법이 {viable[0]} 하나뿐 "
                             f"(용량 {caps[viable[0]]:g}) — 사실상 강제 선택")
                    bad += 1
                elif len(viable) <= 2 and need >= 7:
                    rep.warn(f"'{nm}' 승급 요건 '내공 {need}' — 심법 {len(viable)}종만 도달 가능: {', '.join(sorted(viable))}")
                    bad += 1

    if not bad:
        rep.ok("경지가 열어준 기 운용·내공 요건이 실제 자원으로 도달 가능하다")


# ── 고아 절 ──────────────────────────────────────────────────────────────────

def lint_orphans(cfg, rep):
    rep.head("고아 절 — 아무도 참조하지 않는 config 최상위 절")
    # 어떤 문자열이든 다른 파일(config/engine/docs)에서 언급되면 '참조됨'
    engine_src = ""
    if os.path.isdir(ENGINE_DIR):
        for fn in os.listdir(ENGINE_DIR):
            if fn.endswith(".java"):
                engine_src += open(os.path.join(ENGINE_DIR, fn), encoding="utf-8").read()

    # 봇/맵 소스도 참조처로 인정 (읽기만)
    other_src = engine_src
    for sub in ("server-bot", "server-mvt", "tools", "docs/design"):
        d = os.path.join(ROOT, sub)
        if not os.path.isdir(d):
            continue
        for dp, _dn, fns in os.walk(d):
            for fn in fns:
                if fn.endswith((".java", ".py", ".md", ".kt")):
                    try:
                        other_src += open(os.path.join(dp, fn), encoding="utf-8", errors="ignore").read()
                    except OSError:
                        pass

    orphans = []
    for rel, doc in sorted(cfg.items()):
        if not isinstance(doc, dict):
            continue
        others = "".join(str(v) for k, v in cfg.items() if k != rel)
        for sect in doc:
            if not isinstance(sect, str) or sect.startswith(("principles", "note", "design_note")):
                continue
            if sect in other_src or f"'{sect}'" in others or f'"{sect}"' in others:
                continue
            # 같은 파일 안에서 참조되는지 (문자열 값으로)
            self_refs = all_strings({k: v for k, v in doc.items() if k != sect})
            if any(sect in s for s in self_refs if isinstance(s, str)):
                continue
            orphans.append((rel, sect))

    if not orphans:
        rep.ok("모든 최상위 절이 어딘가에서 참조된다")
        return

    # 엔진이 읽는 config 의 고아 절 = 강한 신호 (규칙은 있는데 아무도 안 읽는다)
    engine_cfgs = set()
    if os.path.isdir(ENGINE_DIR):
        for fn in os.listdir(ENGINE_DIR):
            if fn.endswith(".java"):
                src = open(os.path.join(ENGINE_DIR, fn), encoding="utf-8").read()
                engine_cfgs |= {c for c in JAVADOC_CFG.findall(src) if c in cfg}

    hot = [(r, s) for r, s in orphans if r in engine_cfgs]
    cold = [(r, s) for r, s in orphans if r not in engine_cfgs]

    rep.say(f"     최상위 절 {len(orphans)}개가 엔진·봇·맵·문서·타 config 어디에서도 이름으로 불리지 않는다.")
    rep.say("     대부분은 '아직 미배선'이지 '죽은 설정'이 아니다 — 둘의 구분은 사람이 한다.")
    rep.say("     여기서는 엔진이 이미 읽는 파일의 고아 절만 경고로 올린다 (규칙은 있는데 엔진이 안 읽는 절).")
    rep.say("")

    if hot:
        by_file = {}
        for rel, sect in hot:
            by_file.setdefault(rel, []).append(sect)
        for rel in sorted(by_file):
            rep.warn(f"{rel} — 엔진이 읽는 파일인데 미참조 절: {', '.join(by_file[rel])}")
    else:
        rep.ok("엔진이 읽는 config 에는 고아 절이 없다")

    if cold:
        by_file = {}
        for rel, sect in cold:
            by_file.setdefault(rel, []).append(sect)
        rep.say("")
        rep.say(f"     [참고] 엔진 미연결 config 의 미참조 절 {len(cold)}개 (경고 아님):")
        for rel in sorted(by_file):
            rep.say(f"       {rel}: {', '.join(by_file[rel])}")


# ══════════════════════════════════════════════════════════════════════════════
#  ② 밸런스 시뮬
# ══════════════════════════════════════════════════════════════════════════════

YEAR = 360.0     # simbeop.yml: "1년 = 360일" (축기 규칙에 명시)


def duration_days(text):
    """'3개월' / '1년' / '2년' → 일수."""
    if isinstance(text, (int, float)):
        return float(text)
    if not isinstance(text, str):
        return None
    m = re.fullmatch(r"\s*(\d+)\s*(년|개월|일)\s*", text)
    if not m:
        return None
    n, unit = int(m.group(1)), m.group(2)
    return n * {"년": YEAR, "개월": YEAR / 12.0, "일": 1.0}[unit]


def simulate(cfg, rep, days):
    rep.say()
    rep.say("═" * 72)
    rep.say("  ② 밸런스 시뮬 — 설계가 실제로 굴러가는가")
    rep.say("═" * 72)

    growth = sim_growth(cfg, rep, days)
    sim_economy(cfg, rep, growth)
    sim_judgment(cfg, rep)
    sim_internal_energy(cfg, rep)


# ── 성장 곡선 ────────────────────────────────────────────────────────────────

def sim_growth(cfg, rep, horizon):
    rep.head("성장 곡선 — 표준 플레이어(수련 1 + 사냥 2 + 의뢰 1 / 일)")

    cult = cfg.get("cultivation.yml") or {}
    train = cfg.get("training.yml") or {}
    simb = cfg.get("simbeop.yml") or {}
    hw = cult.get("combat_hwahu") or {}

    base_grant = num(hw.get("base_grant_days"), 2.0)
    decay = num(hw.get("repetition_decay_rate"), 0.25)
    cap = num(hw.get("daily_cap_days"), 16.0)
    gap_mult = dig(hw, "multipliers", "상대_격차", default={}) or {}
    stake_mult = dig(hw, "multipliers", "목숨_무게", default={}) or {}

    costs = train.get("skill_progression_cost") or {}
    skill_days = {}
    for k, v in costs.items():
        m = re.fullmatch(r"(\d+)_to_(\d+)", str(k))
        d = duration_days(v)
        if m and d:
            skill_days[int(m.group(1))] = d
    method_eff = {k: num(dig(v, "efficiency"), 0.0)
                  for k, v in (train.get("training_methods") or {}).items() if isinstance(v, dict)}
    eff = method_eff.get("사사", 1.0)   # 표준 = 스승에게 배운다 (효율 1.0)

    names = realm_names(cfg)
    stages = {s["name"]: s for s in (cult.get("cultivation_stages") or []) if isinstance(s, dict)}

    # 승급 요건 파싱 — 숙련 n / 실전 마크 n / 사선 마크 n / 개화 / 내공 n / 기초 단련 n개월
    def reqs_of(realm):
        out = {"skill": 0, "mark": 0, "death_mark": 0, "bloom": False, "naegong": 0.0, "drill_days": 0.0}
        for r in (dig(stages.get(realm, {}), "promotion", "requirements", default=[]) or []):
            r = str(r)
            if (m := re.search(r"숙련\s*(\d+)", r)):
                out["skill"] = max(out["skill"], int(m.group(1)))
            if (m := re.search(r"실전 마크\s*(\d+)", r)):
                out["mark"] = int(m.group(1))
            if (m := re.search(r"사선 마크\s*(\d+)", r)):
                out["death_mark"] = int(m.group(1))
            if (m := re.search(r"내공\s*(\d+)", r)):
                out["naegong"] = float(m.group(1))
            if "개화" in r:
                out["bloom"] = True
            if (m := re.search(r"기초 단련\s*(\d+)\s*(개월|년)", r)):
                out["drill_days"] = int(m.group(1)) * (YEAR / 12 if m.group(2) == "개월" else YEAR)
        return out

    # 짐승의 격 (beast_ranks) → 경지 인덱스
    beasts = {}
    for bid, b in (dig(hw, "beast_ranks", default={}) or {}).items():
        rk = str(dig(b, "rank", default="")).replace("_상당", "").replace("_이상", "")
        if rk in names:
            beasts[bid] = names.index(rk)

    # 축기: 내공 n→n+1 = n년 (0→1 = 1년)
    def accum_days(n):
        return YEAR * max(1, n)

    # 상태
    ledger = 0.0        # 주력 무공 숙련 화후 (일치)
    skill = 0           # 숙련 정수부
    drill = 0.0         # 기초 단련 누적 (일치)
    marks = death_marks = 0
    realm_i = 0         # 범인
    naegong = 0.0
    bloomed = False
    quests_done = 0
    reached = {}
    history = []

    # 개화 원천 — 심법을 주는 기연 (fortune_encounters)
    simbeop_gate = None
    for eid, enc in (dig(cfg, "fortune_encounters.yml", "encounters", default={}) or {}).items():
        rw = " ".join(str(x) for x in (dig(enc, "reward", default=[]) or []))
        if "심법" in rw:
            trig = " ".join(str(x) for x in (dig(enc, "trigger", default=[]) or []))
            need_q = int(m.group(1)) if (m := re.search(r"의뢰_?완수\s*(\d+)", trig)) else 2
            need_v = int(m.group(1)) if (m := re.search(r"방문\s*(\d+)\s*회", trig)) else 1
            max_realm = None
            for nm in names:
                if re.search(rf"{nm}\s*이하", trig):
                    max_realm = names.index(nm)
            simbeop_gate = {"id": eid, "quests": need_q, "visits": need_v, "max_realm": max_realm}
            break

    # '압도적_하수'(적립 0)의 정의는 config 가 준다: cultivation 주석 "자동 판정권(기대마진 +8) = 적립 0"
    # → judgment.yml auto_success_expected_margin ÷ realm_gap_per_stage 단계 격차.
    auto_margin = num(dig(cfg, "judgment.yml", "auto_resolution", "auto_success_expected_margin"), 8)
    per_stage = num(dig(cfg, "judgment.yml", "situation_modifiers", "realm_gap_per_stage"), 2)
    crush_stages = int(auto_margin / per_stage) if per_stage else 4

    def gap_tier(target_i, realm_i):
        d = target_i - realm_i
        if d >= 1:
            return "상수"
        if d == 0:
            return "동수"
        return "압도적_하수" if -d >= crush_stages else "하수"

    def run(policy):
        """policy: '동수' = 격에 맞는 사냥감만 (표준) / '상수' = 늘 격상에 도전 (공격형)"""
        ledger = drill = naegong = 0.0
        skill = marks = death_marks = quests = visits = 0
        realm_i = 0
        bloomed = False
        reached, hist = {}, []

        for day in range(1, horizon + 1):
            # 수련 1회 (full_day × 효율). diminishing: 같은 기술 1년 초과 시 -25%
            gain = 1.0 * eff * (0.75 if day > YEAR else 1.0)
            ledger += gain
            drill += gain

            # 사냥감 선택
            avail = sorted(beasts.items(), key=lambda kv: kv[1]) or [("들짐승", 1)]
            if policy == "상수":
                pick = next((b for b in avail if b[1] > realm_i), avail[-1])
            else:
                pick = next((b for b in avail if b[1] == realm_i),
                            next((b for b in avail if b[1] > realm_i), avail[-1]))

            today = 0.0
            for k in range(2):   # 사냥 2회 — 같은 유형 연속 → 감쇠
                g = (base_grant * num(gap_mult.get(gap_tier(pick[1], realm_i)), 0.0)
                     * num(stake_mult.get("실전_사냥"), 1.0) * (1.0 - decay) ** k)
                today += max(0.0, min(g, cap - today))
            # 의뢰 1건 = 새 유형(감쇠 리셋), 동수 상대
            g = base_grant * num(gap_mult.get("동수"), 1.0) * num(stake_mult.get("실전_사냥"), 1.0)
            today += max(0.0, min(g, cap - today))
            ledger += today
            drill += today
            if today > 0:
                marks += 1
            quests += 1
            visits += 1

            # 숙련 정수부
            while skill in skill_days and ledger >= sum(skill_days[l] for l in range(skill + 1) if l in skill_days):
                skill += 1

            # 개화 — 심법 기연
            if not bloomed and simbeop_gate:
                if (quests >= simbeop_gate["quests"] and visits >= simbeop_gate["visits"]
                        and (simbeop_gate["max_realm"] is None or realm_i <= simbeop_gate["max_realm"])):
                    bloomed = True
                    reached["개화"] = day
            if bloomed:
                naegong += 1.0 / accum_days(int(naegong))

            # 승급 (하위 3단 자동 — 절정부터는 '벽' = 깨달음 사건, 시뮬 범위 밖)
            while realm_i + 1 < len(names):
                nm = names[realm_i + 1]
                r = reqs_of(nm)
                if (r["skill"] > skill or r["mark"] > marks or r["death_mark"] > death_marks
                        or (r["bloom"] and not bloomed) or r["naegong"] > naegong
                        or r["drill_days"] > drill):
                    break
                if "자동" not in str(dig(stages.get(nm, {}), "promotion", "trigger", default="")):
                    break
                realm_i += 1
                reached[nm] = day
            hist.append((day, realm_i, ledger, skill, naegong))
        return reached, hist

    rep.say(f"     환산: 수련 1일 × 효율 {eff:g}(사사) = {eff:g}일치 · 실전 적립 상한 {cap:g}일치/일")
    rep.say(f"     숙련 비용(일): " + ", ".join(f"{l}→{l+1} {d:.0f}" for l, d in sorted(skill_days.items())))
    rep.say(f"     축기: 내공 n→n+1 = n년(1년={YEAR:.0f}일) — 0→1 = {accum_days(0):.0f}일")
    rep.say(f"     짐승의 격: " + ", ".join(f"{b}={names[i]}" for b, i in sorted(beasts.items(), key=lambda kv: kv[1])))
    rep.say(f"     격차 등급: 상수 ×{num(gap_mult.get('상수'),0):g} · 동수 ×{num(gap_mult.get('동수'),0):g} "
            f"· 하수 ×{num(gap_mult.get('하수'),0):g} · 압도적_하수 ×{num(gap_mult.get('압도적_하수'),0):g} "
            f"(압도적 = {crush_stages}단 이상 격차 — 기대마진 {auto_margin:g} ÷ 단계당 {per_stage:g})")
    rep.say("")
    rep.say("     사냥감 선택이 성장 속도를 지배한다 (상대_격차 곱연산) — 두 정책을 다 굴린다:")
    rep.say("")

    results = {}
    for policy, label in (("동수", "표준 — 격에 맞는 사냥감만"), ("상수", "공격형 — 늘 격상에 도전")):
        reached, hist = run(policy)
        results[policy] = (reached, hist)
        rep.say(f"     [{label}]")
        for nm in ["삼류", "이류", "개화", "일류"]:
            d = reached.get(nm)
            tag = {"개화": "개화 (심법 전수·축기 개시)"}.get(nm, nm)
            rep.say(f"       {tag:<24} " + (f"{d:>4}일차" if d else f"미도달 ({horizon}일 내)"))
        rep.say(f"       하루 적립 ≈ {hist[0][2]:.1f}일치")
        rep.say("")

    reached, history = results["동수"]
    first_class = reached.get("일류")

    if first_class is None:
        rep.fail(f"표준 플레이어가 {horizon}일(≈{horizon / YEAR:.1f}년) 안에 일류에 못 이른다 — 성장이 막혀 있다")
    elif first_class > YEAR:
        rep.warn(f"표준 일류까지 {first_class}일 (≈{first_class / YEAR:.2f}년) — 1년(360일) 초과. 성장이 느리다")
    elif first_class < YEAR * 0.5:
        rep.fail(f"표준 플레이어가 일류(‘개화한 몸’·‘정식 무인으로 인정받는 경지’)에 {first_class}일 "
                 f"(≈{first_class / YEAR:.2f}년) 만에 이른다 — 1년의 {first_class / YEAR * 100:.0f}%. "
                 f"무협의 시간 감각이 무너진다. 격상 사냥까지 하면 "
                 f"{results['상수'][0].get('일류', '—')}일")
    else:
        rep.ok(f"표준 일류까지 {first_class}일 (≈{first_class / YEAR:.2f}년) — 1년 이내, 무협의 시간 감각에 부합")

    # 병목 분해
    r = reqs_of("일류")
    skill_need = sum(skill_days[l] for l in range(r["skill"]) if l in skill_days)
    d_at = next((h[0] for h in history if h[3] >= r["skill"]), None)
    rep.say("")
    rep.say("     병목 분해 (일류 요건 3종 — 무엇이 실제로 문을 막는가):")
    rep.say(f"       · 주력 무공 숙련 {r['skill']} = 화후 {skill_need:.0f}일치  → {d_at}일차 충족  ← 유일한 실질 관문")
    rep.say(f"       · 실전 마크 {r['mark']}  → {r['mark']}일차 충족 (하루 1전투이면 자동)")
    gate_day = reached.get("개화")
    rep.say(f"       · 개화 = 기연 '{simbeop_gate['id'] if simbeop_gate else '?'}' "
            f"(의뢰 {simbeop_gate['quests']}건 + 방문 {simbeop_gate['visits']}회) → {gate_day}일차 충족")
    if gate_day and gate_day <= 10:
        rep.warn(f"개화 관문이 {gate_day}일차에 열린다 — 설계상 '일류의 사실상의 관문'인데 "
                 f"기연 조건(의뢰 {simbeop_gate['quests']}건·방문 {simbeop_gate['visits']}회)이 아무 저항이 아니다. "
                 f"관문이 문이 아니라 문턱이다")
    if skill_need and d_at:
        rep.say(f"       → 세 요건 중 둘이 한 자릿수 일차에 열린다. 성장 속도 = 화후 적립 속도 하나로 수렴하고, "
                f"그 속도는 사냥감 선택이 정한다")

    # 이후 경지 — 수치 요건의 시간 (‘벽’ 사건은 시뮬 밖)
    rep.say("")
    rep.say("     절정 이상 — 수치 요건만의 시간 (‘벽’ = 깨달음 사건은 시뮬 범위 밖):")
    for nm in names[4:8]:
        r = reqs_of(nm)
        acc = sum(accum_days(k) for k in range(int(r["naegong"]))) if r["naegong"] else 0
        skl = sum(skill_days[l] for l in range(r["skill"]) if l in skill_days) if r["skill"] else 0
        parts = []
        if r["naegong"]:
            parts.append(f"내공 {r['naegong']:.0f} = 축기 {acc / YEAR:.0f}년")
        parts.append(f"숙련 {r['skill']} = 화후 {skl / YEAR:.1f}년치" if r["skill"] else "숙련 요건 없음")
        rep.say(f"       {nm:<5} " + " · ".join(parts) +
                ("  ← 수련만으로 불가 구간 포함" if r["skill"] >= 6 else ""))
    rep.say("       (축기는 배증형: n→n+1 = n년. 내공 9 = 37년 — 현경이 '한 시대 한 손'인 이유가 수치에 있다)")

    return {"reached": reached, "history": history, "realm_names": names}


# ── 경제 수지 ────────────────────────────────────────────────────────────────

def sim_economy(cfg, rep, growth):
    rep.head("경제 수지 — 표준 플레이어는 생계가 되는가")
    eco = cfg.get("economy.yml") or {}
    pt = eco.get("price_table") or {}
    trading = eco.get("trading") or {}
    buy_rate = num(trading.get("npc_buy_rate"), 0.5)

    living = pt.get("생활") or {}
    meal = min([num(v) for k, v in living.items()
                if isinstance(v, (int, float)) and ("만두" in str(k) or "국밥" in str(k))] or [10])
    lodging = num(living.get("봉놋방_1박"), 20)

    spend = meal * 2 + lodging            # 끼니 2회 + 봉놋방 1박
    anchors = eco.get("currency", {}).get("anchors", {}) if isinstance(eco.get("currency"), dict) else {}
    wage = mid(anchors.get("서민_일당"), 40)
    monthly = num(anchors.get("서민_1인_월_생활"), 1000)

    # 수입: 사냥 부산물 2점(매입가) + 의뢰 1건
    game = pt.get("사냥_부산물") or {}
    pelt = num(game.get("늑대_가죽"), 100)
    quests = pt.get("의뢰_보수") or {}
    errand = mid(quests.get("잔심부름"), 75)

    income = pelt * buy_rate * 2 + errand
    net = income - spend

    rep.say(f"     지출/일: 끼니 {meal:g}×2 + 봉놋방 {lodging:g} = {spend:g}문   "
            f"(정본 대조: 서민_1인_월_생활 {monthly:g}문 ÷ 30 = {monthly / 30:.1f}문/일)")
    rep.say(f"     수입/일: 늑대 가죽 {pelt:g}문 × 매입가 {buy_rate:g} × 2점 + 잔심부름 {errand:g}문 = {income:g}문")
    rep.say(f"     수지/일: {net:+.0f}문   (수입/지출 = {income / spend:.1f}배)")
    rep.say(f"     대조   : 서민 일당 {wage:g}문 — 표준 플레이어는 서민의 {income / wage:.1f}배를 번다")
    rep.say("")

    if net < 0:
        rep.fail(f"적자 {net:.0f}문/일 — 표준 플레이가 생계를 못 댄다 (굶어 죽는 설계)")
    elif income / spend > 3.0:
        rep.warn(f"과잉 흑자 — 수입이 생계비의 {income / spend:.1f}배. 돈에 긴장이 없다 "
                 f"(사냥 부산물 매입가가 서민 일당의 {pelt * buy_rate / wage:.1f}배)")
    else:
        rep.ok(f"흑자 {net:+.0f}문/일 — 생계가 되면서 여유가 과하지 않다")

    # 목표 구매력 — 돈이 사는 것은 시간·안전
    goals = []
    for cat in ("장비", "무공", "의원"):
        for k, v in (pt.get(cat) or {}).items():
            p = v if isinstance(v, (int, float)) else (mid(dig(v, "range")) if isinstance(v, dict) else None)
            if isinstance(p, (int, float)) and p > 0:
                goals.append((f"{cat}/{k}", float(p)))
    goals.sort(key=lambda x: x[1])
    if net > 0:
        rep.say("     저축 일수 (순수지 기준):")
        for name, price in goals:
            if price < 500 or price > 300000:
                continue
            rep.say(f"       {name:<26} {price:>8,.0f}문 = {price / net:6.1f}일")
        sword = dict(goals).get("장비/검_범철")
        if sword and sword / net < 30:
            rep.warn(f"범철 검({sword:,.0f}문)이 {sword / net:.0f}일치 저축 — 무기가 이야기가 아니라 소모품이 된다")

    # 사망 리스크 — 전낭 전액 드랍
    if isinstance(eco.get("wallet_and_bank"), dict):
        fee = num(dig(eco, "wallet_and_bank", "전장", "withdraw_fee"), 0.0)
        rep.say("")
        rep.say(f"     전장 인출 수수료 {fee * 100:.0f}% — 사망 시 전낭 전액 드랍의 유일한 대항 수단")
        if net > 0 and fee * 100 < 5:
            rep.say(f"       → 하루 순수지 {net:.0f}문 대비 수수료가 미미하다: 합리적 플레이어는 전액 예치한다 "
                    f"(사망 시 돈을 잃는 긴장이 사실상 무력화)")


# ── 판정 분포 (해석적) ────────────────────────────────────────────────────────

def sim_judgment(cfg, rep):
    rep.head("판정 분포 — 2d6은 36가지뿐이다 (몬테카를로 불요)")
    j = cfg.get("judgment.yml") or {}
    tiers = j.get("result_tiers") or []
    npc_bonus = num(dig(j, "formula", "npc_fixed_bonus"), 7)
    extreme = dig(j, "optional_extreme_dice", "enabled", default=False)
    static = j.get("static_difficulty") or {}

    # 2d6 분포
    dist = {}
    for a in range(1, 7):
        for b in range(1, 7):
            dist[a + b] = dist.get(a + b, 0) + 1
    tier_order = [(t.get("name"), t.get("min_margin")) for t in tiers if isinstance(t, dict)]

    def tier_of(margin):
        for name, mn in tier_order:
            if mn is None or margin >= mn:
                return name
        return tier_order[-1][0]

    names_seq = [t[0] for t in tier_order]

    def distribution(base_exec, resistance, use_extreme):
        out = {n: Fraction(0) for n in names_seq}
        for roll, w in dist.items():
            margin = base_exec + roll - resistance
            idx = names_seq.index(tier_of(margin))
            if use_extreme and extreme:
                if roll == 2:
                    idx = min(idx + 1, len(names_seq) - 1)
                elif roll == 12:
                    idx = max(idx - 1, 0)
            out[names_seq[idx]] += Fraction(w, 36)
        return out

    # 표준 플레이어: 능력치 3 + 기술 2 = 5 (생성 프리셋 중앙값)
    p_attr, p_skill = 3, 2
    base_exec = p_attr + p_skill
    # 대등한 NPC: 같은 능력치·기술 + 경계도 1 + 고정 7
    resistance = int(p_attr + p_skill + 1 + npc_bonus)
    gap = resistance - base_exec

    rep.say(f"     표준 대립 판정: 실행력 = 능력치 {p_attr} + 기술 {p_skill} + 2d6")
    rep.say(f"                     저항값 = 대등 NPC({p_attr}+{p_skill}) + 경계도 1 + 고정 {npc_bonus:g} = {resistance}")
    rep.say(f"                     마진 = 2d6 − {gap}   (범위 {2 - gap} ~ {12 - gap})")
    rep.say("")

    for label, d in (("극단 주사위 미적용", distribution(base_exec, resistance, False)),
                     ("극단 주사위 적용", distribution(base_exec, resistance, True))):
        rep.say(f"     [{label}]")
        for n in names_seq:
            p = float(d[n]) * 100
            bar = "█" * int(round(p / 2.5))
            rep.say(f"       {n:<12} {p:5.1f}%  {bar}")
        rep.say("")

    d = distribution(base_exec, resistance, True)
    def pct(*ns):
        return sum(float(d[n]) for n in ns if n in d) * 100

    crit_succ = pct("대성공")
    fail = pct("실패", "치명적 실패", "부분 성공")
    hard_fail = pct("실패", "치명적 실패")
    crit_fail = pct("치명적 실패")

    rep.say(f"     대성공 {crit_succ:.1f}%  ·  치명적 실패 {crit_fail:.1f}%  ·  실패(실패+치명) {hard_fail:.1f}%")
    rep.say("")

    if hard_fail < 5:
        rep.warn(f"실패율 {hard_fail:.1f}% < 5% — 대등한 상대에게조차 거의 지지 않는다 (긴장 없음)")
    elif hard_fail > 40:
        rep.warn(f"실패율 {hard_fail:.1f}% > 40% — 대등한 상대에게 너무 자주 진다 (좌절 설계)")
    else:
        rep.ok(f"실패율 {hard_fail:.1f}% — 5~40% 구간")

    # 대성공/치명 실패의 구조적 도달 가능성 — 주사위 상한이 등급 상한을 못 넘는 구조인가
    crit_min = next((mn for nm, mn in tier_order if nm == "대성공"), 6)
    cfail_min = next((mn for nm, mn in tier_order if nm == "실패"), -7)
    if crit_min is not None and 12 - gap < crit_min:
        rep.fail(f"대등 판정에서 '대성공'(마진 ≥{crit_min})이 주사위만으로는 구조적으로 불가능하다 — "
                 f"최대 마진 = 12 − {gap} = {12 - gap}. 극단 주사위(12)의 등급 상승만이 유일 경로 → 대성공 = {crit_succ:.1f}% (정확히 1/36). "
                 f"판정 등급표가 6단인데 대등 상황에서 실제로 쓰이는 것은 4단뿐이다")
    if cfail_min is not None and 2 - gap > cfail_min:
        rep.fail(f"대등 판정에서 '치명적 실패'(마진 < {cfail_min})가 주사위만으로는 구조적으로 불가능하다 — "
                 f"최저 마진 = 2 − {gap} = {2 - gap}. 극단 주사위(2)의 등급 하락만이 유일 경로 → 치명적 실패 = {crit_fail:.1f}% (정확히 1/36)")
    rep.say(f"     (대성공·치명적 실패가 둘 다 optional_extreme_dice 에만 의존한다 — "
            f"judgment.yml 은 이 규칙을 'optional'로 부르는데, 끄면 등급 6단 중 2단이 사라진다)")

    # 비대립 고정 난이도 — 여기는 대성공이 뜨는가
    rep.say("")
    rep.say("     비대립(고정 난이도) 분포 — 같은 표준 플레이어:")
    for label, diff in sorted(static.items(), key=lambda kv: num(kv[1])):
        dd = distribution(base_exec, int(num(diff)), True)
        succ = sum(float(dd[n]) for n in names_seq[:3] if n in dd) * 100  # 대성공+성공+아슬
        rep.say(f"       {label:<10} (난이도 {num(diff):.0f})  성공권 {succ:5.1f}%  "
                f"대성공 {float(dd.get('대성공', 0)) * 100:5.1f}%")

    # 자동 판정 경계
    auto_s = num(dig(j, "auto_resolution", "auto_success_expected_margin"), 8)
    gap_per_stage = num(dig(j, "situation_modifiers", "realm_gap_per_stage"), 2)
    stages_to_auto = auto_s / gap_per_stage if gap_per_stage else 0
    rep.say("")
    rep.say(f"     자동 성공 경계: 기대 마진 ≥ {auto_s:g} · 경지 격차 {gap_per_stage:g}/단계 "
            f"→ 경지 {stages_to_auto:.0f}단 차이면 판정 자체가 사라진다")
    if stages_to_auto >= 5:
        rep.warn(f"경지 {stages_to_auto:.0f}단 격차가 필요 — 9경지 체계에서 사실상 도달 불가 조합. "
                 f"'압도적 하수 = 적립 0'(cultivation combat_hwahu)이 기대 마진 +8 을 기준으로 삼는데, "
                 f"경지만으로는 그 마진이 나지 않는다 (능력치·기술 격차가 있어야 성립)")


# ── 내력 수지 ────────────────────────────────────────────────────────────────

def sim_internal_energy(cfg, rep):
    rep.head("내력 수지 — 개화 직후의 몸은 실제로 굴러가는가")
    ie = cfg.get("internal_energy.yml") or {}
    bands = ie.get("cost_bands") or {}
    inner = ie.get("internal_energy") or {}
    rec = inner.get("recovery") or {}
    segments = num(dig(cfg, "time.yml", "segments_per_day"), 5)

    nat = dig(rec, "natural_per_segment_by_simbeop", default=[0.5, 2])
    nat_lo, nat_hi = (float(nat[0]), float(nat[-1])) if isinstance(nat, list) and nat else (0.5, 2.0)

    def pool(naegong):
        return round(naegong * 3)

    # 개화 직후 — 축기 1년 중 초반. 내력 1이 되는 최소 내공
    bloom_naegong = 1.0 / 3.0
    p = pool(bloom_naegong)
    balgyeong = mid(dig(bands, "발경", "cost"), 1)

    rep.say(f"     내력 = round(내공 실수치 × 3) · 하루 {segments:g}구간 · 자연 회복 {nat_lo:g}~{nat_hi:g}/구간(심법별)")
    rep.say(f"     운기조식 = 내공 × 1 /구간 (무방비)")
    rep.say("")
    rep.say(f"     개화 직후 (내공 {bloom_naegong:.2f} = 축기 {bloom_naegong * YEAR:.0f}일차): 내력 풀 = {p}")
    rep.say(f"     발경 1회 = 내력 {balgyeong:g}  →  잔량 {p - balgyeong:g}")

    if p - balgyeong <= 0:
        rep.say(f"       → 발경 한 번에 고갈. 고갈 상태 = 판정 −2 + 다운캐스트('맨 기술')")

    med = bloom_naegong * 1.0
    nat_day_lo, nat_day_hi = nat_lo * segments, nat_hi * segments
    rep.say("")
    rep.say(f"     회복: 운기조식 1구간 = {med:.2f}  vs  자연 회복 1구간 = {nat_lo:g}~{nat_hi:g}")
    rep.say(f"           하루(운기 1회 + 나머지 {segments - 1:g}구간 자연) = "
            f"{med + nat_lo * (segments - 1):.2f} ~ {med + nat_hi * (segments - 1):.2f}")

    daily_recovery = med + nat_lo * (segments - 1)
    if daily_recovery >= p:
        rep.ok(f"하루 회복량({daily_recovery:.2f}) ≥ 풀 전체({p}) — 하루 운기 1회로 완전 회복된다. 구조는 돈다")
    else:
        rep.fail(f"하루 회복량({daily_recovery:.2f}) < 풀 전체({p}) — 하루로 못 채운다")

    # 핵심 이상: 운기조식이 자연 회복보다 못한 구간
    if med < nat_lo:
        breakeven = nat_lo
        rep.fail(f"운기조식(내공 {bloom_naegong:.2f} × 1 = {med:.2f}/구간)이 자연 회복 최저치({nat_lo:g}/구간)보다 "
                 f"{nat_lo - med:.2f} 낮다 — '무방비'라는 대가를 치르고 손해를 본다. "
                 f"운기조식이 자연 회복을 넘으려면 내공 > {breakeven:g} 필요")
        rep.say(f"       → 상승 심법(자연 회복 {nat_hi:g}/구간)이면 내공 {nat_hi:g} 초과까지 운기조식은 계속 손해다. "
                f"개화~절정 구간(내공 0~3)이 게임의 대부분인데, 그 내내 운기조식은 쓸 이유가 없다")

    # 축기 진행에 따른 내력 수지 표
    rep.say("")
    rep.say("     축기 진행별 내력 (발경 = 내력 1):")
    rep.say("       내공    내력   발경 가능  운기/구간   자연/구간(최저)   운기가 이득?")
    for ng in (0.33, 0.5, 1.0, 2.0, 3.0, 5.0):
        pl = pool(ng)
        rep.say(f"       {ng:>4.2f}   {pl:>4}   {int(pl // max(balgyeong, 1)):>7}회   "
                f"{ng * 1.0:>7.2f}   {nat_lo:>13.2f}   {'예' if ng * 1.0 > nat_lo else '아니오'}")

    # 발경의 경지 게이트 vs 개화 시점
    gates = ie.get("realm_gates") or {}
    names = realm_names(cfg)
    first_gate = next((r for r in names if "발경" in (gates.get(r) or [])), None)
    if first_gate:
        stages = {s["name"]: s for s in (dig(cfg, "cultivation.yml", "cultivation_stages", default=[]) or [])
                  if isinstance(s, dict)}
        bloom_at = next((nm for nm in names
                         if any("개화" in str(r) for r in (dig(stages.get(nm, {}), "promotion", "requirements", default=[]) or []))),
                        None)
        rep.say("")
        rep.say(f"     발경 개방 경지 = '{first_gate}' · 개화(단전) 요건 경지 = '{bloom_at}'")
        if bloom_at and names.index(first_gate) < names.index(bloom_at):
            rep.fail(f"'{first_gate}'에게 발경을 열어주지만 단전이 없다(내공 0 → 내력 0) — "
                     f"'{bloom_at}' 전까지 발경은 장부에만 있는 권한이다")


# ══════════════════════════════════════════════════════════════════════════════
#  진입점
# ══════════════════════════════════════════════════════════════════════════════


def audit_weapon_calls(rep) -> None:
    """
    <b>병기 규칙을 코드가 지키는가</b> — 조성기가 짓다가 예외로 죽지 않는가.

    마교 진령이 조성될 때마다 예외로 죽었다. `RemoteBuilder` 가 **검+마병**을 요구했는데
    `Weapons.make` 가 *"마병은 도(刀)에만 존재한다"* 며 던졌다. 다섯 지역 중 하나가 서지 못했다.

    ★ 기막힌 것은 **바로 윗줄 주석이 그 규칙을 적어 두고 있었다**는 점이다:
      "여기엔 부러지지 않는 병기가 걸린다 (Weapons.Grade.마병)". 그리고 다음 줄에서 어겼다.
      **주석은 규칙을 지키지 않는다. 재는 자가 지킨다.**

    이 검산은 엔진의 가드(`Weapons.make` 가 던지는 조건)를 **코드에서 읽어** 호출부와 대조한다.
    가드가 바뀌면 이 눈도 함께 바뀐다 — 잣대를 여기 베껴 적으면 언젠가 둘이 갈라진다.
    """
    rep.head("병기 호출 — 조성기가 제 규칙을 지키는가 (짓다가 던지면 지역이 안 선다)")
    # ★ ENGINE_DIR 은 core 룰 엔진이다. **조성기(RemoteBuilder·CheonghaBuilder)는 server-mvt 에 산다.**
    #   이 눈은 조성기를 봐야 한다 — 안 그러면 "Weapons.java 가 없다"며 조용히 건너뛴다
    #   (그리고 건너뛴 눈은 통과한 눈과 화면에서 구별되지 않는다).
    mvt_dir = os.path.join(ROOT, "server-mvt", "src", "main", "java", "com", "honcheon", "mvt")
    wsrc = os.path.join(mvt_dir, "Weapons.java")
    if not os.path.isfile(wsrc):
        rep.say("  Weapons.java 가 없다 — 건너뛴다")
        return
    body = open(wsrc, encoding="utf-8").read()
    # 가드를 코드에서 읽는다: DEMONIC_SERIES = Series.도
    m = re.search(r"DEMONIC_SERIES\s*=\s*Series\.(\S+?)\s*;", body)
    demonic = m.group(1) if m else None
    if not demonic:
        rep.say("  마병 가드를 못 읽었다 — 건너뛴다 (Weapons.DEMONIC_SERIES)")
        return
    rep.say(f"  가드: 마병은 {demonic}(刀) 에만 존재한다 (Weapons.DEMONIC_SERIES)")

    bad = 0
    for name in sorted(os.listdir(mvt_dir)):
        if not name.endswith(".java") or name == "Weapons.java":
            continue
        path = os.path.join(mvt_dir, name)
        src = open(path, encoding="utf-8").read()
        for i, line in enumerate(src.splitlines(), 1):
            call = re.search(r"Weapons\.(?:makeSeeded|make)\(\s*Weapons\.Series\.(\S+?)\s*,"   # ★ 긴 이름을 먼저 — make 가 먼저면 makeSeeded 를 못 잡는다
                             r"\s*Weapons\.Grade\.(\S+?)\s*[,)]", line)
            if not call:
                continue
            series, grade = call.group(1), call.group(2)
            if grade == "마병" and series != demonic:
                rep.fail(f"{name}:{i} — {series}+마병 을 요구한다. **엔진이 던진다** "
                        f"(마병은 {demonic} 에만 있다) → 이 조성기가 짓다가 죽는다")
                bad += 1
    if bad == 0:
        rep.ok("  조성기가 요구하는 병기 조합이 전부 만들 수 있는 것이다")


def main():
    ap = argparse.ArgumentParser(description="혼천 게임 밸런스·정합 검수")
    ap.add_argument("--lint-only", action="store_true", help="① 정합 린트만")
    ap.add_argument("--sim-only", action="store_true", help="② 밸런스 시뮬만")
    ap.add_argument("--days", type=int, default=540, help="시뮬 기간(일, 기본 540 = 1.5년)")
    args = ap.parse_args()

    rep = Report()
    rep.say("╔" + "═" * 70 + "╗")
    rep.say("║" + "  혼천 게임 감사 — game_audit".ljust(69) + "║")
    rep.say("║" + "  config 를 재는 자 — 고치지 않는다".ljust(66) + "║")
    rep.say("╚" + "═" * 70 + "╝")

    try:
        cfg = load_all()
    except YamlError as e:
        print(f"{FAIL} config 파싱 실패: {e}", file=sys.stderr)
        return 2
    rep.say(f"  config {len(cfg)}종 적재 · 엔진 {len([f for f in os.listdir(ENGINE_DIR) if f.endswith('.java')]) if os.path.isdir(ENGINE_DIR) else 0}종")

    if not args.sim_only:
        lint(cfg, rep)
    if not args.lint_only:
        simulate(cfg, rep, args.days)

    rep.say()
    rep.say("═" * 72)
    n_v, n_w = len(rep.violations), len(rep.warnings)
    if n_v == 0 and n_w == 0:
        rep.say(f"  총평: {OK} 위반 0건 · 경고 0건 — 잴 수 있는 범위에서는 정합하다")
    else:
        rep.say(f"  총평: 위반 {n_v}건 · 경고 {n_w}건")
        if n_v:
            rep.say("")
            rep.say(f"  ── 위반 ({FAIL}) — 설계가 자기모순이거나 굴러가지 않는다")
            for i, v in enumerate(rep.violations, 1):
                rep.say(f"    {i:2}. {v}")
        if n_w:
            rep.say("")
            rep.say(f"  ── 경고 ({WARN}) — 굴러가지만 의도한 감각이 아닐 수 있다")
            for i, w in enumerate(rep.warnings, 1):
                rep.say(f"    {i:2}. {w}")
    rep.say("═" * 72)
    rep.dump()
    return 1 if n_v else 0


if __name__ == "__main__":
    sys.exit(main())
