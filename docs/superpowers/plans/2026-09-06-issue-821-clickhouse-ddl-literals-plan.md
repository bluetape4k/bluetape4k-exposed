# #821 ClickHouse DDL 리터럴 보존 계획

## 범위와 승인

- Type C 버그 수정. 2026-09-06 사용자 승인으로 구현·검증·리뷰·PR 생성을 진행한다.
- 저장소: bluetape4k-exposed. 기준: `f2fe16416461ec65dca7954d14dcae8664b1b2c1`.
- PR: `fix/issue-821-clickhouse-ddl-literals` → `develop`. 머지·배포·catalog 변경은 별도 승인이다.
- #815 downstream 이전, #823 keyset reader, 기존 #771/#808 worktree는 변경하지 않는다.

## 실행 순서와 완료 증거

1. `ClickHouseDdlSanitizerTest`에서 인용 문자열·식별자·DEFAULT 식·실제 제약 제거를 검증한다.
   기존 `MergeTreeDslTest` 기준 통과 후 새 테스트가 의도한 결과 불일치로 실패해야 한다.
2. `ClickHouseTable.kt`의 제약 제거를 수정한다.
   공개 API와 Exposed의 기본 DDL 생성 경로를 유지하고 새 의존성을 추가하지 않는다.
3. `ClickHouseDefaultLiteralTest`에서 실제 서버의 `system.columns.default_expression`과
   기본값으로 삽입된 행을 검사한다. 모듈 전체 테스트·Detekt·ABI 검사가 통과해야 한다.
4. README 영문·한국어, KDoc, CHANGELOG, 교훈과 리뷰 기록을 맞춘다.
   독립 코드·설계 리뷰 결과를 반영하고 P0/P1이 없을 때 PR을 생성한다.
5. 정확한 PR head의 CI와 리뷰 스레드를 다시 확인하고 머지 준비를 보고한다.
   머지는 새 사용자 승인을 받은 뒤에만 진행한다.

실패하면 해당 재현 또는 수정 단계로 돌아가 영향받는 검사를 다시 실행한다.
복구가 필요하면 해당 feature branch의 변경만 되돌리며 다른 worktree나 catalog는 건드리지 않는다.

## 구현 선택의 근거

`Column`은 Exposed 1.5.0 JAR에서 final이고 `descriptionDdl`도 final이다.
컬럼 DDL 전체를 다시 구현하면 기본값·타입 생성 규칙을 복제하게 된다.
따라서 기존 `super.createStatement()`를 유지하고 인용 영역을 같은 길이로 마스킹한 후
최상위 제약의 원문 구간만 제거한다. 원래 문자열은 재인코딩하지 않는다.

기존 R2DBC 쿼리 분석의 인용·escape 처리 방식을 참고하되 Spring 모듈 의존성은 추가하지 않는다.
Exposed 생성 DDL만 지원하며 범용 수동 SQL 파서는 만들지 않는다.

## 기준 정보와 검증 원칙

- 사용자 `/Users/debop/.codex/AGENTS.md`, 작업공간 `.github/docs/workspace/AGENTS.md`,
  저장소 `AGENTS.md`를 확인했다. 작업공간 루트는 canonical 파일의 symlink다.
- workflow, bugfix, Kotlin patterns/testing/final checklist, systematic-debugging, TDD,
  using-git-worktrees, writer를 적용한다. 고비용 검사는 한 번에 한 프로세스만 실행한다.
- GNO GitHub 검색에는 #821 결과가 없었다. docs에는 기존 ClickHouse 설계·매뉴얼이 검색됐다.
  현재 판단은 live #821과 현재 소스에 근거한다.
- Kotlin IDE 진단 도구는 현재 노출되지 않아 compile·Detekt·ABI·테스트로 검증한다.
- 문서 SPW-01–05: 한국어 유지보수자용 계획, source-to-step 대응, 기술 용어·승인 경계 확인.
