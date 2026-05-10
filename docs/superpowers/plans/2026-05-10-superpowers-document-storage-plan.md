# Superpowers 문서 저장 계약 적용 플랜

**날짜**: 2026-05-10
**대상**: `bluetape4k-exposed`
**설계 문서**: `docs/superpowers/specs/2026-05-10-superpowers-document-storage-design.md`
**참고 패턴**: `bluetape4k-projects/docs/superpowers/`, `bluetape4k-*/docs/lessons/`

---

## Context

`bluetape4k-exposed`에서 Superpowers 기반 작업을 시작하기 전에 spec/plan 문서 저장 위치와 파일명 규칙을 고정한다.
기준은 실제 문서가 가장 많이 누적된 `bluetape4k-projects`의 구조다.
작업 후기와 Lessons Learned는 다른 bluetape4k repo의 관례대로 `docs/lessons/`에 저장한다.

## Work Objectives

1. `docs/superpowers/specs/` 아래에 저장 계약 spec을 추가한다.
2. `docs/superpowers/plans/` 아래에 대응 plan을 추가한다.
3. `docs/lessons/` 아래에 작업 후기/Lessons 저장 계약을 남긴다.
4. `AGENTS.md`에 앞으로의 durable spec/plan/lessons 저장 규칙을 명시한다.
5. 문서 변경만 검증하고 코드 빌드는 생략한다.

## Guardrails

### Must Have

- Spec 파일명은 `YYYY-MM-DD-{slug}-design.md` 형식을 따른다.
- Plan 파일명은 `YYYY-MM-DD-{slug}-plan.md` 형식을 따른다.
- Lessons 파일명은 `YYYY-MM-DD-{slug}.md` 형식을 따른다.
- GitHub issue 연계 문서는 slug에 `issue-{number}-`를 포함한다.
- `.omx/plans`는 런타임/임시 상태로만 취급한다.
- `.omx/notepad.md`의 durable lessons는 `docs/lessons/`로 승격한다.

### Must Not Have

- 새 Superpowers 문서를 repo 밖이나 `.omx/plans`에만 남기지 않는다.
- 작업 후기나 교훈을 채팅 또는 `.omx/notepad.md`에만 남기지 않는다.
- `bluetape4k-projects`와 다른 파일명 접미사를 도입하지 않는다.
- 문서-only 작업에서 불필요한 Gradle 빌드를 실행하지 않는다.

## Tasks

### T1: 기존 패턴 확인

- `bluetape4k-projects/docs/superpowers/specs/` 파일명 패턴 확인
- `bluetape4k-projects/docs/superpowers/plans/` 파일명 패턴 확인
- `bluetape4k-*/docs/lessons/` 파일명 패턴 확인
- 대표 문서 구조 확인

**검증**: `find docs/superpowers -maxdepth 2 -type f`와 workspace `docs/lessons` 검색으로 실제 패턴 확인

### T2: exposed 저장 계약 문서 추가

- `docs/superpowers/specs/2026-05-10-superpowers-document-storage-design.md`
- `docs/superpowers/plans/2026-05-10-superpowers-document-storage-plan.md`

**검증**: 두 문서가 `docs/superpowers` 아래 같은 slug로 생성됨

### T3: Lessons 저장 계약 문서 추가

- `docs/lessons/2026-05-10-document-storage-contract.md`

**검증**: Lessons 문서가 `docs/lessons/YYYY-MM-DD-{slug}.md` 형식으로 생성됨

### T4: AGENTS.md 규칙 추가

- Durable Superpowers 문서 저장 위치 명시
- spec/plan/research 파일명 규칙 명시
- 작업 후기/Lessons 저장 위치 명시
- `.omx/plans`는 임시 상태로만 사용한다는 경계 명시
- `.omx/notepad.md`의 durable lessons를 `docs/lessons/`로 승격한다는 경계 명시

**검증**: `AGENTS.md`에서 `Superpowers Specs And Plans`, `Lessons` 섹션 확인

### T5: 최종 검증

- `git diff --check`
- `repo-status`

Gradle 테스트는 코드 변경이 없으므로 생략한다.
