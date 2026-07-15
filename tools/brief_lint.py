#!/usr/bin/env python3
"""브리프 린트 — 문파 브리프가 조사 프로토콜을 지키는지 재는 눈.

무엇을 재는가 — ★지어내지 않았다. 두 등록부가 요건을 열거했고, 이 눈은 그 목록 그대로다:
  · docs/design/sect_brief_protocol.md   (★사용자 직접 작성 — 조사 원칙 · 출처 등급 ★5~★1 ·
    근거 4분류(역사적 사실/문화적 특징/무협 통용/MC 결정) 혼합 금지 · 문서 6종 · STEP 1~10)
  · docs/design/map_charter_v5.md §3.5   (「브리프 lint 요건 — 기계가 잴 수 있는 것만 잰다」 —
    회신 축 4-b 의 여섯 항목, 구조화 근거 문법(claims/sources), 소급 면제 상태)

여섯 눈 (헌장 §3.5 의 번호 그대로):
  ① 모든 claim 에 kind 가 있는가                       — [표식누락]/[표식어휘]
  ② fact/culture/wuxia_common claim 에 source_id 가 있고, source 레코드에
     제목·발행 주체·URL/서지·열람일·등급(★5~★1)이 있는가 — [출처없음]/[출처레코드불비]
  ③ 숫자에 evidence_ref 가 있는가 — 없으면 candidate + decision_ref
     또는 unresolved 인가                               — [출처없는수치]
  ④ ★1 팬 출처가 supports 에 들어가지 않았는가          — [팬설정근거]
     (프로토콜 원문: "팬 설정은 참고만 하며 설계 근거로 사용하지 않는다")
  ⑤ 문서 6종과 STEP 1~10 완료 상태가 있는가            — [문서6종불비]/[STEP상태없음]
  ⑥ decision_ref 의 결정 ID 가 실제 결정표에 존재하는가 — [유령결정]

★ 소급 면제는 기계가 읽는 상태다 (헌장 §3.5 말미 · Codex 회신 §4-b):
  화산 문서 셋 + 브리프는 `research_status: grandfathered` + basis 를 **명시**하고,
  이 도구의 승인 목록(GRANDFATHERED)에 있어야 면제된다 — 표식만 적으면 도용이고([면제도용]),
  목록에만 있고 표식이 없으면 침묵이다([면제표식없음]). 면제는 조사 pass(①~⑤)를 덮는다.
  ⑥(유령 결정)은 조사가 아니라 결정 원장의 일이므로 면제 밖이다.

★ 기계가 잴 수 없는 것은 재지 않는다 (헌장 §3.5): 출처 내용의 진위 · 등급의 타당성 ·
  「문화」와 「사실」 경계의 의미 · MC 결정의 좋고 나쁨 — 그것은 사람이 판정한다.

  사용법:  python3 tools/brief_lint.py [파일...] [--selftest]
           (파일 생략 시 docs/design/*brief*.md — 프로토콜 문서 자신은 제외)
  종료코드: 위반 0건이면 0, 아니면 1
"""
from __future__ import annotations

import re
import sys
import pathlib

try:
    import yaml
except ImportError:  # pragma: no cover
    print("PyYAML 이 없다: pip install pyyaml")
    sys.exit(2)

ROOT = pathlib.Path(__file__).resolve().parent.parent
DESIGN = ROOT / "docs" / "design"

# ─── 어휘 — 전부 등록부의 것이다 (밖의 값은 오타이거나 발명이다) ───
# 헌장 §3.5 claims.kind = 프로토콜의 근거 4분류 (protocol :101-108)
KINDS = {"fact", "culture", "wuxia_common", "mc_decision"}
RESEARCH_KINDS = {"fact", "culture", "wuxia_common"}     # source_id 필수인 셋 (②)
# 헌장 §3.5 sources 레코드 — "제목·발행 주체·URL/서지·열람일·등급"
SOURCE_FIELDS = ["title", "publisher", "url", "accessed", "grade"]
SOURCE_ROLES = {"supports", "inspiration"}
# 헌장 §3.1 코어 — research_status: protocol_v1 | grandfathered
RESEARCH_STATUSES = {"protocol_v1", "grandfathered"}
# 헌장 §3.2 — 문서 6종 = 확장 6종 (프로토콜 「생성해야 하는 문서」와 1:1)
EXTENSIONS = ["domain", "architecture", "economy", "npc_ecology", "gameplay", "procgen"]
APPLICABILITY = {"required", "optional", "not_applicable", "unresolved"}
STEPS = 10                                               # 프로토콜 STEP 1~10

# ─── ★ 소급 면제 승인 목록 — 사용자 확인 2026-07-15 (hwasan_brief_v5.md 머리말 ·
#   헌장 §3.5 말미). "화산 문서 셋은 '이미 인터넷 조사를 통해 작성된 내용'으로
#   조사 프로토콜 pass 를 소급 면제받았다." ★새 문파는 여기 못 들어온다 —
#   "프로토콜은 다음 문파부터 전면 적용" (같은 머리말) ───
GRANDFATHERED = {
    "hwasan_brief_v5.md": "user_confirmation_2026-07-15",
    "hwasan_campus_architecture.md": "user_confirmation_2026-07-15",
    "hwasan_domain_design.md": "user_confirmation_2026-07-15",
    "hwasan_economic_sphere.md": "user_confirmation_2026-07-15",
}

FENCE = re.compile(r"^```(?:yaml|yml)\s*$(.*?)^```\s*$", re.M | re.S)
ANY_FENCE = re.compile(r"^```.*?^```\s*$", re.M | re.S)
DECISION_ID = re.compile(r"\b([A-Z]{1,2}-\d+)\b")


def yaml_blocks(text):
    """문서의 ```yaml 블록들 — 파싱되는 dict 만 돌려준다 (산문 예시 블록은 조용히 건넌다)"""
    out = []
    for m in FENCE.finditer(text):
        try:
            data = yaml.safe_load(m.group(1))
        except yaml.YAMLError:
            continue
        if isinstance(data, dict):
            out.append(data)
    return out


def grade_num(g):
    """출처 등급 → 1~5. 등록부 표기 셋을 다 받는다: 5 · '★5' · '★★★★★'(별 개수).
    못 읽으면 None — 그것은 [출처레코드불비]다 (등급이 없는 것과 같다)."""
    if isinstance(g, bool):
        return None
    if isinstance(g, int):
        return g if 1 <= g <= 5 else None
    if isinstance(g, str):
        s = g.strip()
        stars = s.count("★")
        if stars and set(s) <= {"★", "☆"}:
            return stars if 1 <= stars <= 5 else None
        m = re.fullmatch(r"★?([1-5])", s)
        if m:
            return int(m.group(1))
    return None


def known_decision_ids():
    """★⑥의 원장 — 결정 ID 가 실제로 사는 곳들 (헌장 §3.6: 결정의 원장은 결정표가 소유한다).
    브리프 자신의 결정표(본문 — yaml 블록 밖) + 장부(BACKLOG) + 1단계 문서."""
    ids = set()
    for f in (ROOT / "docs" / "BACKLOG.md", DESIGN / "v5_stage1_cheonghagwon.md"):
        if f.is_file():
            ids |= set(DECISION_ID.findall(f.read_text(encoding="utf-8")))
    return ids


class Lint:
    def __init__(self, name, text, ledger_ids=None):
        self.name = name                       # 파일 이름 — 면제 목록 대조 키
        self.text = text
        self.ledger_ids = ledger_ids if ledger_ids is not None else known_decision_ids()
        self.bad = []
        self.warn = []
        self.info = []
        # 병합 판독 — 블록 여럿이면 claims/sources 는 잇고, 스칼라는 먼저 온 것이 이긴다
        self.data = {}
        self.claims = []
        self.sources = []
        for b in yaml_blocks(text):
            for k, v in b.items():
                if k == "claims" and isinstance(v, list):
                    self.claims += [c for c in v if isinstance(c, dict)]
                elif k == "sources" and isinstance(v, list):
                    self.sources += [s for s in v if isinstance(s, dict)]
                elif k not in self.data:
                    self.data[k] = v

    # ────────────────────────────────────────────────────────────────
    def research_status(self):
        """yaml 블록이 먼저다. 블록이 안 파싱되는 문서(산문 셋)를 위해 원문 표기도 읽는다 —
        면제는 **표식이 명시**돼야 하는 상태이므로, 읽는 길은 넓게 두되 판정은 좁게 한다."""
        v = self.data.get("research_status")
        if v is not None:
            return str(v).strip()
        m = re.search(r"research_status:\s*([a-z_0-9]+)", self.text)
        return m.group(1) if m else None

    def research_basis(self):
        v = self.data.get("research_basis") or self.data.get("basis")
        if v is not None:
            return str(v).strip()
        m = re.search(r"(?:research_basis|basis):\s*([\w-]+)", self.text)
        return m.group(1) if m else None

    # ────────────────────────────────────────────────────────────────
    def run(self):
        status = self.research_status()
        exempt = False

        # ── 소급 면제 — lint 가 읽는 승인된 상태 (헌장 §3.5 말미) ──
        if status == "grandfathered":
            want = GRANDFATHERED.get(self.name)
            if want is None:
                self.bad.append(
                    f"[면제도용] {self.name} — research_status: grandfathered 라 적었는데 "
                    f"**승인 목록에 없다.** 면제는 사용자 확인(2026-07-15 · 화산 문서 셋)의 것이다 — "
                    f"\"프로토콜은 다음 문파부터 전면 적용\" (hwasan_brief_v5.md 머리말)")
            elif self.research_basis() != want:
                self.bad.append(
                    f"[면제근거불일치] {self.name} — basis '{self.research_basis()}' ≠ 승인된 "
                    f"'{want}'. ★면제는 표식 누락을 조용히 허용하는 예외가 아니라 "
                    f"**lint 가 읽는 승인된 상태**다 (헌장 §3.5)")
            else:
                exempt = True
                self.info.append(
                    f"[면제] {self.name} — 조사 pass(①~⑤) 소급 면제 (grandfathered · {want}). "
                    f"⑥(유령 결정)은 면제 밖이다")
        elif self.name in GRANDFATHERED:
            self.bad.append(
                f"[면제표식없음] {self.name} — 승인 목록에는 있는데 문서가 "
                f"research_status: grandfathered 를 **명시하지 않았다.** "
                f"면제는 기계가 읽는 상태다 — 침묵은 면제가 아니다 (헌장 §3.5)")
        elif status is None:
            self.bad.append(
                f"[조사상태없음] {self.name} — research_status 가 없다 (헌장 §3.1 코어: "
                f"protocol_v1 | grandfathered). ★\"모든 문파 브리프는 인터넷 조사를 기반으로 "
                f"작성한다\" (프로토콜) — 그 상태를 기계가 읽을 자리가 이 키다")
        elif status not in RESEARCH_STATUSES:
            self.bad.append(
                f"[어휘위반] {self.name}.research_status = '{status}' — 헌장 §3.1 의 어휘"
                f"(protocol_v1 · grandfathered) 밖이다")

        # ⑥ 유령 결정 ID — 면제 밖 (조사가 아니라 결정 원장의 일이다) ──────────
        self.ghost_decisions()

        if exempt:
            return self

        # ── 조사 pass ①~⑤ (헌장 §3.5 번호 그대로) ──────────────────────────
        by_id = {}
        for s in self.sources:
            by_id[str(s.get("id"))] = s
            # ② 후반 — source 레코드 불비
            missing = [f for f in SOURCE_FIELDS if not s.get(f)]
            if missing:
                self.bad.append(
                    f"[출처레코드불비] {self.name} source '{s.get('id')}' — "
                    f"{'·'.join(missing)} 이 없다 (헌장 §3.5: 제목·발행 주체·URL/서지·열람일·등급)")
            elif grade_num(s.get("grade")) is None:
                self.bad.append(
                    f"[출처레코드불비] {self.name} source '{s.get('id')}'.grade = "
                    f"{s.get('grade')!r} — ★5~★1 로 읽히지 않는다 (프로토콜 출처 우선순위표)")
            # ④ ★1 팬 출처는 supports 금지 — "팬 설정은 참고만 하며 설계 근거로 사용하지 않는다"
            if grade_num(s.get("grade")) == 1 and s.get("role") == "supports":
                self.bad.append(
                    f"[팬설정근거] {self.name} source '{s.get('id')}' — ★1(팬 설정)이 "
                    f"role: supports 다. **팬 설정은 참고(inspiration)만 하며 설계 근거로 "
                    f"사용하지 않는다** (프로토콜 원문)")
            role = s.get("role")
            if role is not None and role not in SOURCE_ROLES:
                self.bad.append(
                    f"[어휘위반] {self.name} source '{s.get('id')}'.role = '{role}' — "
                    f"supports | inspiration 밖이다 (헌장 §3.5)")

        if not self.claims:
            self.bad.append(
                f"[조사기록없음] {self.name} — claims/sources 레코드가 없다. "
                f"★조사 기반 주장·수치는 구조화 레코드로 적는다 — \"자유문장 표식(【사실】…)만으로는 "
                f"lint 가 못 잰다\" (헌장 §3.1-⑤ · §3.5 · Codex 회신 축 4-b)")

        for c in self.claims:
            cid = c.get("id", "?")
            kind = c.get("kind")
            # ① 표식 누락 — 근거 4분류 (프로토콜: "이 네 가지를 혼합하지 말고 각각의 근거를 유지한다")
            if kind is None:
                self.bad.append(
                    f"[표식누락] {self.name} claim '{cid}' — kind 가 없다. 근거 4분류"
                    f"(fact/culture/wuxia_common/mc_decision — 역사적 사실·문화적 특징·"
                    f"무협 통용·MC 결정)를 **명확히 구분**한다 (프로토콜 Research Requirement)")
            elif kind not in KINDS:
                self.bad.append(
                    f"[표식어휘] {self.name} claim '{cid}'.kind = '{kind}' — 4분류 밖이다 "
                    f"(fact · culture · wuxia_common · mc_decision)")
            # ② 전반 — 조사 3분류는 source_id 필수
            if kind in RESEARCH_KINDS:
                sid = c.get("source_id")
                if not sid:
                    self.bad.append(
                        f"[출처없음] {self.name} claim '{cid}' ({kind}) — source_id 가 없다. "
                        f"조사 분류(fact/culture/wuxia_common)는 출처가 근거다 (헌장 §3.5-②)")
                elif str(sid) not in by_id:
                    self.bad.append(
                        f"[출처없음] {self.name} claim '{cid}' — source_id '{sid}' 의 "
                        f"source 레코드가 없다 (매달린 참조)")
                elif grade_num(by_id[str(sid)].get("grade")) == 1:
                    # ④의 다른 얼굴 — claim 이 인용하는 출처는 그 자체로 근거(supports)다
                    self.bad.append(
                        f"[팬설정근거] {self.name} claim '{cid}' — ★1(팬 설정) 출처 '{sid}' 를 "
                        f"근거로 인용한다. **팬 설정은 설계 근거로 사용하지 않는다** (프로토콜)")
            # ③ 출처 없는 수치 — "수치가 실리면 evidence_ref 필수 —
            #   없으면 수 대신 candidate + decision_ref 또는 unresolved" (헌장 §3.5-③)
            text = str(c.get("text", ""))
            if re.search(r"\d", text):
                ok = (c.get("evidence_ref")
                      or (c.get("candidate") and c.get("decision_ref"))
                      or c.get("unresolved") is True)
                if not ok:
                    self.bad.append(
                        f"[출처없는수치] {self.name} claim '{cid}' — 수가 실렸는데 evidence_ref 가 "
                        f"없다 (candidate+decision_ref 도, unresolved 도 아니다). "
                        f"★근거 없는 수는 지어낸 수다 — 「후보」가 붙지 않은 수가 근거 없이 있으면 "
                        f"문서의 결함이다 (hwasan_brief_v5.md §4)")
            # mc_decision 은 decision_ref 를 가진다 (헌장 §3.5 claims 문법)
            if kind == "mc_decision" and not c.get("decision_ref"):
                self.warn.append(
                    f"[결정참조없음] {self.name} claim '{cid}' (mc_decision) — decision_ref 가 "
                    f"없다 (사용자 결정 ID·날짜 — 헌장 §3.5). ★결정 없이 내린 MC 결정인가?")

        # ⑤ 문서 6종 · STEP 1~10 완료 상태 ──────────────────────────────────
        self.extensions()
        self.steps()
        return self

    # ────────────────────────────────────────────────────────────────
    def ghost_decisions(self):
        """⑥ decision_ref 의 결정 ID 가 실제 결정표에 존재하는가 (헌장 §3.5-⑥).
        원장 = 브리프 자신의 결정표(yaml 블록 **밖**의 본문) + BACKLOG + 1단계 문서 —
        ★참조가 제 자신을 원장으로 삼으면 유령이 안 잡히므로 블록 안은 원장이 아니다."""
        prose = ANY_FENCE.sub(" ", self.text)
        universe = self.ledger_ids | set(DECISION_ID.findall(prose))
        refs = []
        for c in self.claims:
            v = c.get("decision_ref")
            refs += v if isinstance(v, list) else [v] if v else []
        v = self.data.get("decision_refs")
        if isinstance(v, list):
            refs += v
        for r in refs:
            for token in DECISION_ID.findall(str(r)):
                if token not in universe:
                    self.bad.append(
                        f"[유령결정] {self.name} — decision_ref '{token}' 이 어느 결정표에도 없다 "
                        f"(브리프 결정표·BACKLOG·1단계 문서). **없는 결정을 근거로 쓸 수 없다** "
                        f"(헌장 §3.5-⑥)")

    def extensions(self):
        """문서 6종 — 헌장 §3.2 확장 6종. 조사 프로토콜의 「생성해야 하는 문서」와 1:1 이다."""
        ext = self.data.get("extensions")
        if not isinstance(ext, dict):
            self.bad.append(
                f"[문서6종불비] {self.name} — extensions 레코드가 없다. 각 문파는 최소 문서 6종"
                f"(Architecture·Domain·Economy·NPC Ecology·Gameplay·ProcGen)을 가진다 "
                f"(프로토콜 :61-70 · 헌장 §3.2)")
            return
        for k in EXTENSIONS:
            spec = ext.get(k)
            if not isinstance(spec, dict):
                self.bad.append(f"[문서6종불비] {self.name} — extensions.{k} 가 없다 (문서 6종의 하나다)")
                continue
            app = spec.get("applicability")
            if app not in APPLICABILITY:
                self.bad.append(
                    f"[어휘위반] {self.name} extensions.{k}.applicability = '{app}' — "
                    f"required | optional | not_applicable | unresolved 밖이다 (헌장 §3.2)")
            # ★ not_applicable 에도 reason/evidence 필수 — "빈 배열만 허용하면
            #   「해당 없음」과 「아직 모름」이 다시 섞인다" (헌장 §3.2 · 회신 축 2 원문)
            if app == "not_applicable" and not (spec.get("reason") and spec.get("evidence")):
                self.bad.append(
                    f"[사유없음] {self.name} extensions.{k} — not_applicable 인데 reason/evidence 가 "
                    f"없다. **「해당 없음」과 「아직 모름」을 섞지 않는다** (헌장 §3.2)")

    def steps(self):
        """STEP 1~10 완료 상태 (헌장 §3.5-⑤ 후반 · 프로토콜 :48-59)."""
        st = self.data.get("steps")
        if not isinstance(st, dict):
            self.bad.append(
                f"[STEP상태없음] {self.name} — steps 레코드가 없다. 브리프는 STEP 1~10"
                f"(실제 지역 조사 → Registry 충돌 검토)의 완료 상태를 가진다 (헌장 §3.5-⑤)")
            return
        seen = {}
        for k, v in st.items():
            m = re.search(r"(\d+)", str(k))
            if m:
                seen[int(m.group(1))] = v
        missing = [n for n in range(1, STEPS + 1) if n not in seen]
        if missing:
            self.bad.append(
                f"[STEP상태없음] {self.name} — STEP {'·'.join(map(str, missing))} 의 상태가 없다 "
                f"(1~10 전부 — 안 했으면 안 했다고 적는다. 침묵과 미완은 다르다)")
        for n, v in sorted(seen.items()):
            if v is None or str(v).strip() == "":
                self.bad.append(f"[STEP상태없음] {self.name} — STEP {n} 이 있는데 상태가 비었다")


# ────────────────────────────────────────────────────────────────────
def targets(argv):
    files = [pathlib.Path(a) for a in argv if not a.startswith("--")]
    if files:
        return files
    # 기본: 브리프로 보이는 문서 전부 — ★프로토콜 문서 자신은 브리프가 아니다
    return [f for f in sorted(DESIGN.glob("*brief*.md")) if "protocol" not in f.name]


def report(lints):
    n_bad = 0
    for lt in lints:
        print(f"══ 브리프 린트 — {lt.name} ══")
        for line in lt.bad:
            print("✗ " + line)
        for line in lt.warn:
            print("! " + line)
        for line in lt.info:
            print("  " + line)
        if not lt.bad and not lt.warn:
            print("  (조용하다)")
        n_bad += len(lt.bad)
    print("── 총평 ──")
    print(f"{'✓ 위반 0건' if n_bad == 0 else f'✗ 위반 {n_bad}건'}"
          f"  ·  경고 {sum(len(x.warn) for x in lints)}건")
    return 0 if n_bad == 0 else 1


# ────────────────────────────────────────────────────────────────────
# ★ 자기 시험 — "시험 없는 눈은 눈이 아니다."
#   헌장 §3.5 가 심을 표본까지 지정했다: 표식 누락 · 출처 없는 수치 · ★1 supports ·
#   유령 결정 ID — "각각 일부러 넣은 표본으로 눈을 시험한다"
# ────────────────────────────────────────────────────────────────────

def good_brief():
    """규약을 다 지킨 표본 — 여기서 병을 하나씩 심는다 (기준선이 깨끗해야 시험이 선다)"""
    data = {
        "id": "test_sect",
        "research_status": "protocol_v1",
        "steps": {f"STEP {n}": "완료" for n in range(1, STEPS + 1)},
        "extensions": {
            k: {"applicability": "required", "doc": f"{k}.md",
                "reason": "문파 required", "evidence": "protocol :61-70"}
            for k in EXTENSIONS},
        "claims": [
            {"id": "c_fact", "kind": "fact",
             "text": "화산은 오악의 서악이다", "source_id": "s_hist"},
            {"id": "c_num", "kind": "mc_decision",
             "text": "본전 단 폭 27", "evidence_ref": "RB:1007-1008",
             "decision_ref": "T-1"},
            {"id": "c_unres", "kind": "mc_decision",
             "text": "후산 반경 40 은 근거가 없다", "unresolved": True,
             "decision_ref": "T-1"},
            {"id": "c_wuxia", "kind": "wuxia_common",
             "text": "매화검수는 매화를 남긴다", "source_id": "s_wuxia"},
        ],
        "sources": [
            {"id": "s_hist", "title": "화산 문화재 조사", "publisher": "학술 자료",
             "url": "https://example.org/huashan", "accessed": "2026-07-15",
             "grade": "★5", "role": "supports"},
            {"id": "s_wuxia", "title": "무협 통용 설정집", "publisher": "무협 설정 자료",
             "url": "서지: 통용 설정 3판", "accessed": "2026-07-15",
             "grade": "★★★☆☆", "role": "supports"},
            {"id": "s_fan", "title": "팬 위키", "publisher": "팬",
             "url": "https://example.org/fan", "accessed": "2026-07-15",
             "grade": "★1", "role": "inspiration"},
        ],
    }
    prose = "## 결정표\n\n| T-1 | 시험 결정 — 표본의 원장 |\n"
    return data, prose


def render(data, prose):
    return ("# 시험 브리프 — 눈을 시험하는 표본\n\n```yaml\n"
            + yaml.safe_dump(data, allow_unicode=True, sort_keys=False)
            + "```\n\n" + prose)


def selftest():
    import copy
    cases = []
    base, prose = good_brief()

    def probe(name, mutate, expect, fname="test_brief.md", ledger=frozenset()):
        d = copy.deepcopy(base)
        p = mutate(d) if mutate else None
        lt = Lint(fname, render(d, p if isinstance(p, str) else prose), set(ledger)).run()
        hits = [x for x in lt.bad + lt.warn if expect in x]
        cases.append((name, bool(hits), hits[0] if hits else "— 짖지 않았다"))

    def probe_quiet(name, mutate, forbidden, evidence, fname="test_brief.md"):
        d = copy.deepcopy(base)
        if mutate:
            mutate(d)
        lt = Lint(fname, render(d, prose), set()).run()
        noisy = [x for x in lt.bad if forbidden in x]
        cases.append((name, not noisy, evidence if not noisy else f"✗ 짖었다: {noisy[0]}"))

    # ── 헌장 §3.5 가 지정한 표본 넷 ──────────────────────────────────────
    probe("★★ ①표식 누락 — claim 에서 kind 를 지운다 (헌장 지정 표본)",
          lambda d: d["claims"][0].pop("kind"), "[표식누락] test_brief.md claim 'c_fact'")
    probe("★★ ③출처 없는 수치 — evidence_ref 를 지운다 (헌장 지정 표본)\n"
          "      → 근거 없는 수는 지어낸 수다",
          lambda d: d["claims"][1].pop("evidence_ref"), "[출처없는수치] test_brief.md claim 'c_num'")
    probe("★★ ④★1 supports — 팬 출처를 설계 근거로 승격한다 (헌장 지정 표본)",
          lambda d: d["sources"][2].update(role="supports"), "[팬설정근거] test_brief.md source 's_fan'")
    probe("★★ ⑥유령 결정 ID — 없는 결정 H-999 를 근거로 쓴다 (헌장 지정 표본)",
          lambda d: d["claims"][1].update(decision_ref="H-999"), "[유령결정] test_brief.md — decision_ref 'H-999'")

    # ── 나머지 눈들 ─────────────────────────────────────────────────────
    probe("① 4분류 밖의 표식 (kind = 뇌피셜)",
          lambda d: d["claims"][0].update(kind="뇌피셜"), "[표식어휘]")
    probe("② 조사 분류인데 출처가 없다 (fact 의 source_id 삭제)",
          lambda d: d["claims"][0].pop("source_id"), "[출처없음] test_brief.md claim 'c_fact'")
    probe("② 매달린 참조 (source_id 가 가리키는 레코드가 없다)",
          lambda d: d["claims"][0].update(source_id="s_ghost"), "[출처없음] test_brief.md claim 'c_fact'")
    probe("② 출처 레코드 불비 (열람일을 지운다)",
          lambda d: d["sources"][0].pop("accessed"), "[출처레코드불비] test_brief.md source 's_hist'")
    probe("② 읽히지 않는 등급 (grade = 상급)",
          lambda d: d["sources"][0].update(grade="상급"), "[출처레코드불비] test_brief.md source 's_hist'.grade")
    probe("④ claim 이 ★1 출처를 **근거로 인용**한다 (역방향의 같은 병)",
          lambda d: d["claims"][0].update(source_id="s_fan"), "[팬설정근거] test_brief.md claim 'c_fact'")
    probe("⑤ 문서 6종에서 하나를 뺀다 (procgen 삭제)",
          lambda d: d["extensions"].pop("procgen"), "[문서6종불비] test_brief.md — extensions.procgen")
    probe("⑤ 「해당 없음」에 사유가 없다 (economy = not_applicable, reason 없음)",
          lambda d: d["extensions"].update(economy={"applicability": "not_applicable"}),
          "[사유없음] test_brief.md extensions.economy")
    probe("⑤ STEP 상태를 뺀다 (STEP 7 삭제)",
          lambda d: d["steps"].pop("STEP 7"), "[STEP상태없음] test_brief.md — STEP 7")
    probe("⑤ steps 레코드가 통째로 없다",
          lambda d: d.pop("steps"), "[STEP상태없음]")
    probe("조사 상태가 침묵한다 (research_status 삭제)",
          lambda d: d.pop("research_status"), "[조사상태없음]")
    probe("★ 면제 도용 — 승인 목록에 없는 문서가 grandfathered 를 자칭한다",
          lambda d: d.update(research_status="grandfathered"), "[면제도용] test_brief.md")
    probe("★ 면제 근거 불일치 — 화산 이름으로 다른 basis 를 댄다",
          lambda d: d.update(research_status="grandfathered", research_basis="self_claim"),
          "[면제근거불일치]", fname="hwasan_brief_v5.md")
    probe("claims 레코드가 통째로 없다 (자유문장 표식만 있는 브리프)",
          lambda d: (d.pop("claims"), d.pop("sources")), "[조사기록없음]")

    # ── 반대 방향 — 짖으면 안 되는 것 ────────────────────────────────────
    probe_quiet("★ 깨끗한 표본에는 짖지 않는가 (기준선)",
                None, "[", "✓ 조용했다 — 규약을 지킨 브리프는 통과한다")
    probe_quiet("★★ unresolved 수치에는 짖지 않는가 — **모른다고 적은 것은 정직이다**",
                None, "[출처없는수치] test_brief.md claim 'c_unres'",
                "✓ 조용했다 — 근거가 없으면 지어내지 말고 unresolved 로 둔다 (scale_systems 의 조항)")
    probe_quiet("★★ ★1 출처라도 inspiration 이면 짖지 않는가 — **참고는 죄가 아니다**",
                None, "[팬설정근거] test_brief.md source 's_fan'",
                "✓ 조용했다 — 팬 설정은 참고만 한다 (근거로 승격할 때만 짖는다)")

    # ── 실물 — 화산 브리프 (소급 면제가 읽히는가) ────────────────────────
    hwasan = DESIGN / "hwasan_brief_v5.md"
    if hwasan.is_file():
        lt = Lint(hwasan.name, hwasan.read_text(encoding="utf-8")).run()
        ok = not lt.bad and any("[면제]" in x for x in lt.info)
        cases.append(("★★ 실물 — hwasan_brief_v5.md 의 소급 면제가 **읽히는가** (위반 0 + 면제 표기)",
                      ok, lt.info[0] if ok else (lt.bad[0] if lt.bad else "— 면제가 안 읽혔다")))

    print("══ 눈을 시험한다 — 일부러 병을 넣고 짖는지 본다 (브리프 린트) ══")
    ok = True
    for name, caught, evidence in cases:
        print(("✓ " if caught else "✗ ") + f"{name}\n    → {evidence}")
        ok &= caught
    print("── " + (f"✓ 눈이 {len(cases)}번 다 짖었다" if ok else "✗ ★ 눈이 놓쳤다 — 눈을 고쳐라"))
    return 0 if ok else 1


if __name__ == "__main__":
    if "--selftest" in sys.argv:
        sys.exit(selftest())
    lints = []
    for f in targets(sys.argv[1:]):
        if not f.is_file():
            print(f"✗ 파일이 없다: {f}")
            sys.exit(2)
        lints.append(Lint(f.name, f.read_text(encoding="utf-8")).run())
    if not lints:
        print("잴 브리프가 없다 (docs/design/*brief*.md)")
        sys.exit(0)
    sys.exit(report(lints))
