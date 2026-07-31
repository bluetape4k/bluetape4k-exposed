# 이슈 #326 Ktor R2DBC 캐시 및 DDD 데모 제공 체크리스트

범위: PostgreSQL R2DBC, Caffeine 기반 리포지토리, Spring과 무관한 도메인
이벤트, 이중 언어 문서, Architecture/Sequence Diagram 에셋을 사용하는 Order Confirmation
시나리오로 `examples/ktor-exposed-demo`를 확장합니다.

`.bluetape` 상태 루트가 있는 작업공간에서의 기계 판독 실행:
`20260716T161309Z-91be30b9`. 대상 브랜치:
`origin/develop`에서 분기한 `feat/issue-326-ktor-r2dbc-ddd-demo`.

## 라우터

- [x] **WF-01 — 분류**
  - **작업:** 읽기 전용 탐색을 수행하고 Type A/B/C/D/E/P/F 중 하나를 선택합니다.
  - **증거:** Type A Full Feature입니다. 변경 범위가 Ktor 라우트, PostgreSQL
    R2DBC 리소스, 캐시 기반 리포지토리, DDD 이벤트, 테스트, 이중 언어
    문서, 두 개의 다이어그램에 걸쳐 있습니다. 프로덕션 라이브러리 API나 새 모듈은 계획되어 있지 않습니다.
  - **실패:** 중지합니다. 모호한 유형으로 실행 경로를 계획하지 않습니다.
- [x] **WF-02 — 첫 번째 구체적인 계획 작성**
  - **작업:** 모든 단계에 `Action`과 `Expected DoD`를 부여합니다.
  - **증거:** 활성 스레드에서 탐색, 설계, 계획,
    구현, 검증, PR, 병합 게이트 단계와 명시적인 다이어그램 및 예제 시나리오 결과를 제시했습니다.
  - **실패:** 변경 또는 영속 아티팩트 생성 전에 중지합니다.
- [x] **WF-03 — 첫 번째 계획 승인 획득**
  - **작업:** 첫 번째 구체적인 계획에 대한 명시적 승인을 기다립니다.
  - **증거:** 사용자가 Type A 경로를 승인하고, Order
    Confirmation을 선택했으며, Architecture B를 선택하고, PostgreSQL 변형을 승인했습니다.
  - **실패:** 읽기 전용 상태를 유지합니다.
- [x] **WF-04 — 실행 계약 로드**
  - **작업:** 첫 번째 변경 전에 선택한 leaf skill, 공통 게이트 및 트리거된 참조만 읽습니다.
  - **증거:** `bluetape-full-feature`, `bluetape-kotlin-patterns`,
    `bluetape-writer`, `bluetape-diagram`, `brainstorming`,
    `using-git-worktrees`, checklist/common-gate 계약, review
    관점, model routing, topology 계약, `writing-plans`를 로드했습니다.
  - **실패:** 편집 전에 중지합니다.
- [x] **WF-04A — 기계 판독 가능한 증거 초기화**
  - **작업:** `bluetape-flow.py`를 사용하여 워크플로 유형, 리포지토리 루트 및 승인된 토폴로지 컴포넌트를 스냅샷합니다.
  - **증거:** `20260716T161309Z-91be30b9` 실행, manifest 기반 Type A,
    `design`, `plan`, `implementation`, `docs-diagrams`,
    `verification`, `delivery` 컴포넌트, 상태가 `running`으로 전환되었습니다.
  - **실패:** 문서화된 체크리스트 경로를 유지하고 누락된 런타임 표면을 보고합니다.
- [x] **WF-05 — 의존성 순서에 따라 게이트 실행**
  - **작업:** 공통 게이트 및 Type A 게이트의 물리적 행 순서를 따릅니다.
  - **증거:** 설계 및 계획 검토가 구현보다 먼저 수행되었습니다. 단위, 라우트,
    라이프사이클, PostgreSQL, Compose, 다이어그램, 문서화 및 최종 검토
    게이트가 의존성 순서에 따라 실행되었습니다.
  - **실패:** FAIL/PENDING으로 표시하고 후속 단계를 차단합니다.
- [x] **WF-06 — 누락되었거나 취약한 게이트 수정**
  - **작업:** 누락된 체크리스트 항목을 재구성하고 영향을 받은 증명을 다시 실행합니다.
  - **증거:** 고정 단위 다이어그램 마커, 누락 글리프 화살표,
    generic-audit zero-label 대체 처리, Gradle source-set deprecation,
    Colima Ryuk 시작, walkthrough readiness-schema 가정을 수정했으며 영향을 받은 게이트를 다시 실행했습니다.
  - **실패:** 복구 가능한 수정 항목은 PENDING으로 유지합니다. DONE으로 보고하지 않습니다.
## Type A 전체 기능

- [x] **A-01 — 요구사항 격리 및 확인**
  - **수행:** worktree를 생성하고, 관련 없는 변경 사항을 보존하며, issue
    #326을 검토하고, 결과, 경계, 호환성, 부작용 및 중지 조건을 정의한다.
  - **근거:** 위의 격리된 worktree와 branch; base
    `53ffe54f0b88a2886bdd3e2f467527741642acfc`; issue #326은 milestone
    `1.12.0`에서 열려 있다. 제외 사항: production API, module, publishing aggregation,
    Spring/Modulith dependency, catalog upgrade 또는 issue #322 작업은 수행하지 않는다.
  - **실패 시:** research 또는 artifact 작성 전에 중지한다.
- [x] **A-02 — 현재 근거에 기반한 설계**
  - **수행:** 현재 repository 패턴, issue 이력, 로컬 API, 문서, 다이어그램 및
    PostgreSQL/Testcontainers 관례를 검토한다.
  - **근거:** 설계 사양에는 현재 Ktor demo, R2DBC transaction,
    UUID 저장소, read-through/write-through, 캐시 준비 상태, aggregate
    event-buffer, README/diagram, CI Docker, Testcontainers 및 stable-manual
    anchor가 기록되어 있다. 기존 API를 채택하고, UUID repository 및
    PostgreSQL container 패턴을 차용하며, route-owned orchestration,
    publisher decorator, write-behind, H2 compatibility mode 및 runtime
    Testcontainers를 배제한다.
  - **실패 시:** 기억에 의존하여 설계하지 않는다.
- [x] **A-03 — 설계 사양 승인 및 검토**
  - **수행:** 승인된 PostgreSQL Architecture B 사양을 작성하고, 여섯 개의 독립적인
    검토 관점과 main-session integration을 수렴시킨다.
  - **근거:**
    `docs/superpowers/specs/2026-07-17-issue-326-ktor-r2dbc-ddd-demo-design.md`
    에 승인된 PostgreSQL Architecture B, 대안, failure mode, 정확한
    lifecycle/HTTP/test/docs/diagram contract 및 최종 검토 표가 기록되어 있다.
    Performance, stability, security, Ops, developer/API, user/caller 및
    main integration이 모두 P0=0, P1=0, P2=0, P3=0으로 수렴했다.
  - **실패 시:** 중요한 설계 변경 사항은 수정하고 다시 승인받는다.
- [x] **A-04 — 구현 계획 승인 및 검토**
  - **수행:** 순서가 있는 실행 가능한 TDD 계획을 작성하고, 모든 계획 검토 관점을
    수렴시킨다.
  - **근거:**
    `docs/superpowers/plans/2026-07-17-issue-326-ktor-r2dbc-ddd-demo-plan.md`
    에 순서가 지정된 TDD task, 정확한 command, Lore commit, risk
    traceability 및 최종 검토 표가 포함되어 있다. Performance, stability,
    security, Ops, developer/API, user/caller/docs/diagrams 및 main
    integration이 모두 P0=0, P1=0, P2=0, P3=0으로 수렴했다.
  - **실패 시:** 순서, 증명, 소유권 또는 hazard coverage를 수정한다.
- [x] **A-05 — 유발된 위험 예측**
  - **수행:** cache consistency, coroutine cancellation, resource lifecycle,
    real PostgreSQL, event handoff 및 diagram 위험을 signal, mitigation 및
    rerun point와 함께 기록한다.
  - **근거:** 계획에는 dirty write-through cache state, cancellation,
    프로세스 전역 R2DBC 기본 소유권, 비영속 event handoff,
    Docker-task isolation, startup/shutdown diagnostics 및 diagram
    readability를 earliest signal, prevention/proof, implementation task 및
    rerun point에 매핑했다.
  - **실패 시:** 구현을 시작하지 않는다.
- [x] **A-06 — 테스트 우선 증명으로 구현**
  - **수행:** 각 route, repository, event, lifecycle 및 failure behavior에 대해
    RED/GREEN을 따르고, 승인된 범위의 diff만 통합한다.
  - **근거:** 집중된 commit이 test-task isolation, domain invariants,
    발행 전 영속화, UUID 쓰기, 안정적인 HTTP 오류,
    lifecycle restoration, PostgreSQL behavior, runnable Compose, diagram 및
    bilingual guidance를 고정한다. 최종 diff는 승인된 example, docs 및
    evidence 범위 안에 유지된다.
  - **실패 시:** 실패한 behavior 또는 위반된 boundary로 돌아간다.
- [x] **A-07 — 테스트, 사양, 계획 및 repository hazard 검증**
  - **수행:** serialized Testcontainers PostgreSQL verification 및 diagram audit를
    포함하여 targeted check 후 비례적인 broader check를 실행한다.
  - **근거:** 32개의 Docker-free test와 4개의 PostgreSQL test가 통과했으며, 실제
    curl walkthrough와 Compose reset도 통과했다. diagram XML/render/audit/count,
    README command/link parity, forbidden-scope scan 및 diff check도 통과했다.
    Module detekt는 없고 root detekt는 `NO-SOURCE`로 성공하며, 이는 알려진
    verification gap으로 기록되어 있다.
  - **실패 시:** 구현으로 돌아가거나 artifact를 다시 연다.
- [x] **A-08 — 최종 PR 전 검토 수렴**
  - **수행:** 최종 checklist와 여섯 개의 code-review 관점을 실행하고, blocker를
    수정한 뒤 영향을 받은 proof를 다시 실행한다.
  - **근거:**
    `docs/review/2026-07-17-issue-326-ktor-r2dbc-ddd-demo-review.md`가 모든
    acceptance criterion을 매핑하고, 일곱 개 lane 모두 P0=0/P1=0임을 기록한다.
    비어 있는 code-review graph를 직접 source/runtime proof로 대체한다.
  - **실패 시:** PR 생성을 차단한 상태로 유지한다.
- [x] **A-09 — 지속 가능한 학습 기록**
  - **수행:** PR 생성 전에 context, decision, outcome, proof, miss 및 future guard를
    포함한 lesson을 commit한다.
  - **근거:**
    `docs/lessons/2026-07-17-issue-326-ktor-r2dbc-write-through-event-handoff.md`
    에 context, decision, outcome, proof, miss 및 future guard가 기록되어 있으며,
    최종 evidence validation 후 commit이 이어진다.
  - **실패 시:** delivery 전에 lesson evidence를 수정한다.
- [ ] **A-10 — 승인된 PR delivery를 live CI 및 review를 통해 완료**
  - **수행:** 정확히 승인된 head와 live PR에 대해 common gate CG-11부터 CG-14까지를
    완료한다.
  - **근거:** 일치하는 remote head, 검증된 PR metadata/DoD, green required
    check, review convergence 및 diagram inspection artifact.
  - **실패 시:** common gate가 요구하는 대로 delivery를 PENDING 또는 FAIL 상태로 유지한다.
- [ ] **A-11 — 지식 기록 및 merge readiness 보고**
  - **수행:** 지속 가능한 지식을 기록하고, 조정된 count가 포함된 정확한
    merge-ready report를 생성한다.
  - **근거:** knowledge result, 정확한 PR/head 및 확인되지 않은 CG-16부터
    CG-18까지.
  - **실패 시:** DONE을 주장하거나 merge approval을 요청하지 않는다.
- [ ] **A-12 — 새로운 merge approval 이후에만 종료**
  - **수행:** 정확한 merge-ready PR/head에 대한 새로운 승인을 받은 후 merge하고,
    검증하고, `develop`을 sync하며, proven-safe cleanup을 수행한다.
  - **근거:** approval, merge result/SHA, local/upstream parity 및 cleanup.
  - **실패 시:** CG-16에서 대기하는 것은 정상적인 PENDING 상태이며, state를 보존한다.
## 현재 합계

- 필수 검사: 16/19
- 해당 없음: 0
- 차단됨: 0
- 보류 중: `A-10` through `A-12`
