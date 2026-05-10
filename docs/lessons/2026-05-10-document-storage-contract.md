# Lessons Learned — 문서 저장 계약 정리 (2026-05-10)

**관련 브랜치**: `docs/superpowers-storage-contract`
**영향 문서**: `AGENTS.md`, `docs/superpowers/`, `docs/lessons/`

## L1: spec/plan은 런타임 상태가 아니라 프로젝트 히스토리다

### 문제

Superpowers나 OMX 실행 중 생성되는 `.omx/plans`는 작업을 진행하기 위한 런타임 상태에 가깝다.
장기적으로 다시 읽어야 하는 설계 결정과 실행 계획을 여기에만 남기면 repo 히스토리와 분리되고,
다른 작업자가 같은 형식으로 찾기 어렵다.

### 교훈

- durable spec은 `docs/superpowers/specs/YYYY-MM-DD-{slug}-design.md`에 둔다.
- durable plan은 `docs/superpowers/plans/YYYY-MM-DD-{slug}-plan.md`에 둔다.
- `.omx/plans`는 임시 작업 상태로 보고, 남겨야 할 결정은 `docs/superpowers/`로 승격한다.

---

## L2: Lessons는 spec/plan과 분리해야 재사용하기 쉽다

### 문제

작업 후기와 교훈을 spec/plan 안에만 묻어두면 나중에 "무엇을 배웠는가"를 찾기 어렵다.
반대로 모든 후기를 `.omx/notepad.md`나 채팅에만 두면 repo 밖 상태에 의존하게 된다.

### 교훈

- 작업 후기와 Lessons Learned는 `docs/lessons/YYYY-MM-DD-{slug}.md`에 저장한다.
- 반복되는 실수, 도구 사용 실패, 검증에서 드러난 판단 기준은 `docs/lessons/`로 승격한다.
- `AGENTS.md`에는 저장 위치만 간결히 적고, 구체 사례는 lessons 문서에 남긴다.

---

## L3: 기존 repo의 가장 많이 쓰인 패턴을 기준으로 삼는다

### 문제

새 repo에서 독자적인 파일명 접미사나 인덱스 규칙을 만들면 workspace 전체 검색성이 떨어진다.

### 교훈

- `bluetape4k-projects/docs/superpowers/`의 `*-design.md`, `*-plan.md` 패턴을 따른다.
- 다른 bluetape4k repo에서 이미 쓰는 `docs/lessons/YYYY-MM-DD-{slug}.md` 패턴을 따른다.
- 별도 인덱스는 필요해질 때 추가하고, 기본 계약은 경로와 파일명 규칙에 집중한다.
