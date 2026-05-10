# Superpowers 문서 저장 계약 설계 Spec

**날짜**: 2026-05-10
**대상**: `bluetape4k-exposed/docs/superpowers/`, `bluetape4k-exposed/docs/lessons/`
**참고 패턴**: `bluetape4k-projects/docs/superpowers/`, `bluetape4k-*/docs/lessons/`

---

## 1. 개요

`bluetape4k-exposed`의 장기 설계 문서와 실행 계획 문서를 한 위치에 같은 파일명 규칙으로 저장한다.
앞으로 Superpowers를 사용할 때 생성되는 durable spec/plan 문서는 `.omx/plans` 같은 런타임 상태가 아니라
프로젝트 히스토리로 남는 `docs/superpowers/` 아래에 둔다.
작업 후기와 Lessons Learned 문서는 `docs/lessons/` 아래에 별도로 저장한다.

## 2. 저장 위치

| 문서 유형 | 경로 | 파일명 규칙 |
|---|---|---|
| 설계 spec | `docs/superpowers/specs/` | `YYYY-MM-DD-{slug}-design.md` |
| 실행 plan | `docs/superpowers/plans/` | `YYYY-MM-DD-{slug}-plan.md` |
| 조사 research | `docs/superpowers/research/` | `YYYY-MM-DD-{slug}-research.md` |
| 작업 후기 / lessons | `docs/lessons/` | `YYYY-MM-DD-{slug}.md` |

`research`는 필요한 경우에만 사용한다. 최소 단위는 spec과 plan이다.

## 3. 파일명 규칙

- 날짜는 작업을 시작하거나 문서를 확정한 날짜를 `YYYY-MM-DD`로 적는다.
- `{slug}`는 lowercase ASCII kebab-case를 사용한다.
- GitHub issue에 직접 연결된 문서는 `issue-{number}-{topic}` 형태를 우선한다.
- 설계 문서는 `-design.md`, 실행 계획은 `-plan.md`, 조사 메모는 `-research.md`로 끝낸다.
- 작업 후기와 Lessons Learned 문서는 별도 접미사 없이 `YYYY-MM-DD-{slug}.md`를 사용한다.

예시:

```text
docs/superpowers/specs/2026-05-10-issue-24-cockroachdb-design.md
docs/superpowers/plans/2026-05-10-issue-24-cockroachdb-plan.md
docs/superpowers/research/2026-05-10-cockroachdb-testcontainers-research.md
docs/lessons/2026-05-10-issue-24-cockroachdb.md
```

## 4. 문서 작성 계약

- Spec은 배경, 목표, 비목표, 설계 결정, 모듈/패키지 구조, 테스트 전략을 포함한다.
- Plan은 대응 spec 경로, 작업 흐름, task 목록, 검증 명령, guardrail을 포함한다.
- Lessons는 작업 후 실제로 배운 점, 실패/우회, 다음 작업에서 재사용할 판단을 포함한다.
- 코드 구현이 시작되기 전에 관련 spec/plan을 먼저 만들거나 기존 문서를 갱신한다.
- `.omx/plans`와 `.omx/notepad.md`는 Superpowers 실행 중 생성되는 임시 상태로만 사용하고,
  남겨야 할 결정과 교훈은 `docs/superpowers/` 또는 `docs/lessons/`로 승격한다.

## 5. 결정

`bluetape4k-projects`에서 가장 많이 사용된 실전 패턴을 따른다:

- `docs/superpowers/specs/YYYY-MM-DD-{slug}-design.md`
- `docs/superpowers/plans/YYYY-MM-DD-{slug}-plan.md`
- 선택적으로 `docs/superpowers/research/YYYY-MM-DD-{slug}-research.md`
- 작업 후기와 Lessons Learned는 `docs/lessons/YYYY-MM-DD-{slug}.md`

별도 인덱스 파일은 필수로 두지 않는다. 필요하면 나중에 검색/탐색 편의를 위해 추가한다.
