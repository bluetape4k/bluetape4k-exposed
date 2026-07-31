# 이슈 #322 Type A 체크리스트

## 범위

- 저장소: `bluetape4k/bluetape4k-exposed`
- 브랜치: `feat/issue-322-migration-drift`
- 기준: `origin/develop@38d13d9`
- 승인된 결과: H2, PostgreSQL, MySQL 8에서 JDBC/R2DBC 전반의 Exposed 1.3.1 마이그레이션 드리프트 검증을 완료하고, 해당 작업 경로는 옵트인으로 유지하며, 제한 사항을 문서화하고, PR을 생성하며, 최신 정확한 헤드에 대한 승인을 새로 받을 때까지 병합을 보류한다.
- N/A: 새 모듈, public API, 릴리스, publish, dependency version change, catalog mutation, benchmark, cache, HTTP, security credential flow, diagrams. 구현 변경 사항은 테스트 전용 데이터베이스 fixture, 기존 workflow, 이중 언어 문서에 한정된다.
## Type A 게이트

- [x] **A-01 — 요구사항 격리 및 확인**
  - **작업:** 현재 `origin/develop`에서 격리된 기능 worktree를 생성하고 경계와 부작용 권한을 고정한다.
  - **근거:** Worktree `.worktrees/feat-issue-322-migration-drift`, 브랜치 `feat/issue-322-migration-drift`, base `38d13d9`; PR 생성 승인 완료; merge에는 이후의 정확한 head 승인이 필요하다.
  - **실패 시:** worktree 또는 권한 경계가 변경되면 연구 또는 변경 작업 전에 중단한다.

- [x] **A-02 — 현재 근거에 기반한 설계**
  - **작업:** 기존 plugin/demo/test/workflow 표면, 현재 Exposed 1.3.1 JAR API, 공식 문서 및 upstream 이슈를 조사한다.
  - **근거:** 기존 demo plugin 설정과 workflow를 조사했다; 기준 H2 JDBC/R2DBC 테스트가 통과했다; 기본 timestamp 출력을 재현했다; 공식 migration 문서와 Exposed #377/#2441을 조사했다.
  - **실패 시:** 승인된 설계를 변경하기 전에 연구를 다시 연다.

- [x] **A-03 — 설계 사양 승인 및 검토**
  - **작업:** 작성된 설계를 자체 검토하고 6개의 독립적인 검토 관점과 main-session 통합을 수행한다; 작성된 사양에 대한 사용자의 승인을 받는다.
  - **근거:** 설계 및 사양 검토 아티팩트; 6개의 관점과 main 통합 모두 P0=0/P1=0; 사용자 응답 `승인`; 이 체크리스트 행을 포함한 Lore 결정 커밋.
  - **실패 시:** 사양을 수정하고 영향을 받는 관점을 다시 실행한다; 계획을 중단한다.

- [x] **A-04 — 구현 계획 승인 및 검토**
  - **작업:** 정확한 파일, 명령, 위험 요소, rollback 지점 및 완전한 사양 추적성을 포함한 순서화된 TDD 계획을 작성하고 6개의 계획 관점을 실행한다.
  - **근거:** 구현 계획 및 계획 검토 아티팩트; 6개의 관점과 main 통합 모두 P0=0/P1=0; 커밋된 사양/계획.
  - **실패 시:** 구현 전에 누락된 순서, 증명, 소유권 또는 acceptance 매핑을 보완한다.

- [x] **A-05 — 트리거된 위험 예측**
  - **작업:** Testcontainers lifecycle, dialect 차이, 생성된 SQL 안전성, workflow 실행 시간 및 비결정적 filename 위험을 신호와 rollback 지점과 함께 기록한다.
  - **근거:** 검토된 계획에 가장 이른 신호, 예방/증명, 담당 작업 및 rollback 지점을 포함한 10개의 트리거된 위험 항목.
  - **실패 시:** 각 위험에 proving command 또는 명시적 containment가 생길 때까지 구현을 중단한다.

- [x] **A-06 — 테스트 우선 증명을 통한 구현**
  - **작업:** workflow 또는 문서의 주장을 확정하기 전에 JDBC 및 R2DBC migration drift 동작에 대해 RED 이후 GREEN을 확인한다.
  - **근거:** JDBC 및 R2DBC helper filter가 먼저 해결되지 않은 private helper에서 실패했고(`gradle_exit=1`), 이후 JDBC는 5개의 helper case를, R2DBC는 cancellation 이후 non-cancellable cleanup을 포함한 6개의 case를 통과했다; 전체 H2 실행은 additive convergence 및 type-change characterization을 통과했다. 일반 module 테스트에서는 drift XML이 생성되지 않았고, cached-mode 전용 실행 2회에서 두 task를 모두 실행했으며 `UP-TO-DATE` 또는 `FROM-CACHE`가 발생하지 않았다.
  - **실패 시:** 실패하는 동작으로 돌아간다; assertion을 약화하지 않는다.

- [x] **A-07 — 테스트, 사양, 계획 및 repository 위험 검증**
  - **작업:** H2 테스트 이후 PostgreSQL/MySQL 8 테스트를 순차적으로 실행하고, deterministic generation, README parity/link validation, actionlint, Detekt 및 diff check를 수행한다; 정확한 사양/계획 acceptance를 검증한다.
  - **근거:** JDBC 7/7 및 R2DBC 8/8 focused H2 테스트, 일반 module 테스트, fixed-file regeneration, invalid-selector failure, README parity, stable-manual no-diff, `actionlint`, Detekt 및 diff check가 통과했다.
  - **실패 시:** 구현으로 돌아가거나 승인된 아티팩트를 다시 연다.

- [x] **A-08 — 최종 PR 전 검토 수렴**
  - **작업:** Kotlin/document/workflow checklist와 6개의 implemented-diff 검토 관점 및 통합을 실행한다.
  - **근거:** 최종 검토 아티팩트에 6개의 관점과 main 통합 모두 P0=0/P1=0/P2=0/P3=0으로 기록되었다; 갱신된 테스트가 통과했다.
  - **실패 시:** 수정이 완료될 때까지 PR 생성을 차단한다.

- [x] **A-09 — 지속 가능한 학습 커밋**
  - **작업:** fixed filename과 build-time plugin 대 programmatic migration API 경계에 대한 lesson을 커밋한다.
  - **근거:** 추적 중인 migration-drift lesson 및 후보 Lore 커밋.
  - **실패 시:** PR 생성은 계속 차단된다.

- [ ] **A-10 — live CI 및 검토를 통한 PR 전달**
  - **작업:** 정확히 승인된 head를 push하고 `develop`을 대상으로 PR을 생성하며 issue metadata를 미러링하고 검토를 다시 실행한 뒤 필수 check를 기다린다.
  - **근거:** Live PR metadata/body/head, CI 결론, 검토 결과 및 미해결 thread 수.
  - **실패 시:** 전달을 pending 상태로 유지하거나 diagnosis/fix로 돌아간다.

- [ ] **A-11 — 지식 확보 및 merge 준비 상태 보고**
  - **작업:** 연구를 보존하고 knowledge index를 갱신하며 live PR head에 연결된 완전한 Type A DoD를 렌더링한다.
  - **근거:** Wiki/index validation, 조정된 checklist count, 정확한 PR/head/CI/review 상태 및 CG-16 pending.
  - **실패 시:** 근거가 조정될 때까지 merge 승인을 요청하지 않는다.

- [ ] **A-12 — 새로운 merge 승인 이후 마무리**
  - **작업:** 새로운 정확한 head 승인을 받은 뒤 merge하고 merge SHA를 검증하며 `develop`을 동기화하고 통합된 branch/worktree 상태를 정리한다.
  - **근거:** 사용자 승인, live merged PR, merge SHA, 정리되고 동기화된 local 상태 및 cleanup 결과.
  - **실패 시:** 상태를 보존하고 pending 또는 실패한 closeout 행을 보고한다.
## 조건부 Kotlin 및 문서화 게이트

- [x] **KT-01 — 트리거된 Kotlin 가이드 로드**
  - **작업:** 새 JDBC/R2DBC 테스트에 Exposed 및 Kotlin 테스트 가이드를 적용한다.
  - **증거:** 계획 및 최종 검토의 트리거-참조 매핑.
  - **실패:** Kotlin 구현 또는 검토를 차단한다.

- [x] **KT-02 — 영향 범위 및 재사용 점검**
  - **작업:** 인프라를 추가하는 대신 기존 데이터베이스 선택기, 트랜잭션,
    Testcontainers 실행기 및 단언을 재사용한다.
  - **증거:** 정확한 픽스처 앵커 및 raw-fallback 근거.
  - **실패:** 중복되거나 안전하지 않은 인프라를 제거한다.

- [x] **KT-03 — Kotlin 및 Exposed 계약 강제**
  - **작업:** 트랜잭션 유형, 정리, 방언 중립적 단언,
    코루틴 동작 및 public API 영향이 없음을 검증한다.
  - **증거:** P0=0/P1=0인 현재 파일 검토.
  - **실패:** 검증 전에 수정한다.

- [x] **KT-04 — Kotlin 검증으로 동작 입증**
  - **작업:** 진단, 대상 컴파일/테스트, 순차 컨테이너
    테스트 및 diff 검사를 실행한다.
  - **증거:** 최신 명령과 결과.
  - **실패:** Kotlin 판정이 pending 상태로 남는다.

- [x] **KT-05 — 최종 Kotlin 체크리스트 렌더링**
  - **작업:** 수치가 포함된 Kotlin 및 테스트 참조 행을 완료한다.
  - **증거:** 체크리스트 합계 및 차단 요소 0건.
  - **실패:** 완료를 주장하는 대신 확인되지 않은 행을 드러낸다.

- [x] **DOC-01 — 이중 언어 현재 문서 동등성 유지**
  - **작업:** 집중형 동등성 검증기와 self-test를 사용하여 동등한 영어 및 한국어 README 마이그레이션
    섹션을 추가하고 검증한다. 안정적인 1.11 매뉴얼과 manifest는 변경하지 않고 유지한다. 정확한 ref와
    commit 이후 릴리스 마무리가 담당하는 별도의 pending 1.12 매뉴얼
    승격 추적기를 생성한다.
  - **증거:** README 제목/명령/경고/매트릭스/링크 동등성 스크립트와
    self-test, 승격 추적기 및 매뉴얼 메타데이터가 변경되지 않았다는 증명.
  - **실패:** 누락되거나 드리프트된 로케일로 인해 PR 준비 상태가 차단된다.

- [x] **CI-01 — 전용 워크플로 확장 검증**
  - **작업:** 독립적인 빠른 H2 검사를 경로 범위로 제한하고, 태그된
    드리프트 테스트를 재시도되는 대량 테스트에서 제외하며, 실제 DB 드리프트 검사를 하나의
    예약/수동 무재시도 순차 작업에서 실행하고, 방언별 아티팩트를 유지하며,
    정확한 smoke/full 이벤트 조건을 검증하고, `actionlint`로 YAML을 검증한다.
  - **증거:** 워크플로 diff, 구문 결과 및 이벤트 조건 검토.
  - **실패:** PR 전에 워크플로 변경을 되돌리거나 수정한다.

## 체크리스트 계약 수정

설계 사양은 이 체크리스트 파일보다 먼저 생성되었다. 이는 CL-01
순서 지정 누락이다. 수정은 지금 체크리스트를 인스턴스화하고 사양 자체 검토부터
모든 종속 증명을 다시 실행하는 것이다. 수정 전에 구현, 계획, commit,
push, PR, merge 또는 외부 데이터베이스 변경은 발생하지 않았다.
