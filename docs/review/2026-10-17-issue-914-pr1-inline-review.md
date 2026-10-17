# PR #914 보상 PR1 인라인 코드 리뷰

## DoD Status

- 검토 범위: PR1의 R2DBC Caffeine 변경 원복, 보상 lesson, 이전 이슈 #913 lesson 대체
- 검토 기준: 사용자가 선택한 2-PR 절차, PR #914의 부모 트리, Kotlin 공개 계약, 기존 테스트·CI 실패 기록
- 독립 코드 리뷰 lane: 최종 재검토가 제한된 응답 시간을 넘겨 중단됨. 앞선 독립 리뷰는 `APPROVE`였고 벽시계 만료 경쟁 조건을 P2로 남겼다. 이 문서는 인라인 대체 검토이며 독립 리뷰로 간주하지 않는다.
Inline verdict: PASS; P0=0, P1=0.
- 병합 상태: `PENDING` — 아키텍처 `WATCH`, PR1 exact-head CI, 같은 SHA의 단일 실행 receipt, 재시도 선행 실패 확인, fresh merge approval이 남았다.

## Findings

| ID | 심각도 | 위치 | 판정과 처리 |
|---|---|---|---|
| F-01 | P2 | `exposed/r2dbc-caffeine/src/test/kotlin/io/bluetape4k/exposed/r2dbc/caffeine/snapshot/R2dbcCaffeineSnapshotCacheTest.kt:251-254` | `expireAfterWrite = 2ms`로 두 항목을 쓴 직후 하나가 남는다고 단정한다. 스케줄링 지연이 2ms를 넘으면 둘 다 만료되어 assertion이 실패할 수 있다. 이는 PR #914에서 해결한 알려진 간헐 실패를 PR1이 잠시 복구하는 의도된 위험이다. PR1 exact-head CI와 동일 SHA의 단일 실행 receipt를 요구하고, 재시도 중 한 번이라도 실패하면 최종 녹색이어도 중단한다. 위험은 수용 가능하되 제거된 것은 아니다. |

## 검토 결과

- PR1은 원래 PR #914가 바꾼 구현·테스트 두 파일을 되돌린다. 세 대상 파일은 PR #914 부모 `66df99b76d538725f110908c97a4c4d504c6f99d`의 내용과 일치함을 확인했다.
- 제거된 `Ticker` 경로는 `internal` 테스트 생성 overload, private 생성자 인자, private `buildCache` 인자뿐이다. 공개 `r2dbcCaffeineSnapshotCache(...)` factory 시그니처와 공개 애노테이션은 바뀌지 않는다. 이 diff에 대해 별도 binary compatibility 검사는 실행하지 않았으므로 internal JVM ABI 호환성까지 증명하지는 않는다.
- 새 동시성, 리소스 소유, 취소, 데이터 직렬화, 권한 경로는 추가되지 않았다. 보안·수명주기 관련 P0/P1 finding은 없다.
- 대체 lesson은 원래 실패 근거(CI 실행 `38788902610`, `121 tests, 1 failed, 2 skipped`, 로컬 `Expected <0> to equal to <1>`)를 보존하고, PR1 직후 CG-17A 동기화와 PR2 이후 cleanup/index 순서를 기록한다.
- 일반 `test-r2dbc-caffeine` job은 최대 5회 재시도한다. 최종 aggregate 상태만으로는 첫 시도의 실패를 알 수 없으므로, 단일 실행 receipt와 개별 시도 로그를 함께 확인한다.

## 검증과 미해결 조건

- 현재 diff의 `git diff --check`: 통과.
- 이전 로컬 검증: 변경 원복 후 타깃 테스트 14개 통과, `detekt` 통과. `detektTest`는 기존 7건(`WriteBehindCacheTest.kt`의 `UseCheckOrError` 4건, 변경하지 않은 `R2dbcCaffeineSnapshotCacheTest.kt`의 `LongParameterList` 1건, `R2dbcSnapshotTransactionTest.kt`의 `InjectDispatcher` 2건)으로 실패했다. 이 문서 보강 뒤에는 테스트를 다시 실행하지 않았다.
- PR1 exact-head CI와 단일 실행 receipt: 아직 없음.
- merge approval: 아직 없음. 두 증거 중 하나라도 SHA가 다르거나, CI 시도가 실패했거나, check·review·thread가 unresolved이면 병합하지 않는다.

## 작성 검증

- SPW-01~05: PASS — 독자·목적·근거를 고정했고, finding·처리·미해결 조건을 담았으며, 소스와 수치를 대조하고 최종 Markdown을 읽었다.
- KO-01~07: PASS — 코드·명령·URL·오류 문자열을 보존했고, 한국어 기술 문체와 용어를 확인했다. 최종 terminology audit는 두 문서에서 finding 0건이었다.
