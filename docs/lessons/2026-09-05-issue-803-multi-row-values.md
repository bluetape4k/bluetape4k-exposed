# #803 multi-row VALUES 계약과 검증 교훈

## 반환 행 수를 입력 행 수로 가정하지 않는다

- 단계: 설계·계획·TDD. 초기에는 부분 충돌을 무시한 multi-row 삽입이 실제 삽입 행만 반환한다고 가정했다.
- 반증: Exposed 1.5.0의 PostgreSQL native JDBC/R2DBC 호출에서 신규 2행과 충돌 1행을 전달하면 반환은 3행이고 ID가 있는 행은 2행이었다. repository mapper에서는 `id is not in record set`이 발생했다.
- 결정: 2026-09-05 사용자 승인으로 repository의 `useMultiRowValues=true + ignore=true`는 빈 입력까지 iterator 생성 전에 거부한다. 기존 false 경로는 유지한다. 결과를 매핑하지 않는 Unit writer에는 같은 제한을 강제하지 않는다.
- 재발 방지: 반환형을 기준으로 repository와 writer 계약을 따로 작성하고, 부분 충돌을 native 호출부터 재현한다. 입력과 결과를 인덱스로 결합하지 않는다. upstream 내부 처리를 복제하는 해결책은 별도 승인 없이 도입하지 않는다.
- 근거: [설계](../superpowers/specs/2026-09-05-issue-803-multi-row-values-design.md), 두 `RepositoryMultiRowValuesTest`의 native 진단 및 조기 거부 테스트. 공식 소스 조사와 링크는 wiki `research/2026-09-05-exposed-150-multi-row-ignore.md`, commit `908c4bb7`에 보존했다.

## 추정 한도와 실제 DB 보장을 구분한다

- 단계: 설계·계획 리뷰. `행 수 × 전체 컬럼 수`는 upstream의 보호 추정치이지 모든 표현식·driver의 실제 bind 한도가 아니다.
- 결정: 자동 분할 없이 사전 거부하며 Sequence는 허용 행 수 + 1개까지만 수집한다. 실제 경계 성공과 다음 행 거부를 모두 검증한다.
- 재발 방지: 한도 테스트에 바인더 호출 0, 입력 소비 상한, 선행 쓰기 보존, payload 비노출을 포함한다. 성능 배수나 네트워크 왕복 감소는 SQL 로그만으로 주장하지 않는다.

## 검증 도구의 완료와 검증 성공을 구분한다

- 단계: 구현·최종 리뷰. ABI 갱신과 검사를 같은 Gradle 호출에 넣자 `Property has implicit dependency`로 실패했다. 갱신 후 별도 호출한 `checkProductionAbi`는 44개 모듈 모두 통과했다.
- 결정: ABI 갱신과 검사를 순차 호출하고 baseline 변경이 추가뿐인지 별도 확인한다. 기존 JVM 생성자는 `javap`로 확인한다. task 의존성 변경은 이 기능에 섞지 않는다.
- 재발 방지: 도구의 정상 반환이나 report 파일 존재를 PASS로 해석하지 않는다. `BUILD SUCCESSFUL`, 개별 실패, JUnit의 failures/errors/skipped, exact-head CI를 각각 읽는다. configuration cache 우회가 필요하면 사용 옵션과 범위 밖 원인을 남긴다.

## 취소 및 방언 테스트의 주장 범위를 고정한다

- 단계: 6-R 리뷰. 취소된 Deferred의 `await()`만으로 cleanup 종료를 증명할 수 없고 PostgreSQL 반환 형태를 모든 TestDB에 적용할 수 없다.
- 결정: 취소 테스트는 바인더 진입을 고정하고 `join()` 후 바인더 1회·미삽입을 확인한다. 신규 계약 테스트 provider는 승인된 H2/PostgreSQL만 선택한다. 다른 방언은 통과로 간주하지 않는다.
- 재발 방지: 서버 실행 중 취소와 pool 자원 반환은 #808에서 검증한다. 테스트에서 의도한 dialect와 exception 유형을 명시한다. 중단된/생략된 테스트를 성공 개수에 합치지 않는다.
- 독립 리뷰 보강: rendezvous와 종료 대기에 각각 10초 제한을 두어 회귀 시 테스트가 끝나게 한다. rollback 테스트는 넓은 Exception만 확인하지 말고 원인 체인의 SQLSTATE 23505까지 확인한다.

## 호환성과 협업 제약은 명시적 증거로 남긴다

- 단계: 사용자 지침·구현 리뷰. 1인 개발 저장소에서 추가 인간 reviewer 부재는 blocker가 아니다. 독립 검증과 CI 자체를 생략한다는 뜻도 아니다.
- 결정: 기존 overload와 writer 생성자를 유지하고 새로운 Boolean만 필수 인자로 추가한다. 기존 `requireLe`, TestDB, assertions를 재사용하고 새 의존성을 추가하지 않는다.
- 재발 방지: agent 수 제한이 발생하면 기존 agent 재사용을 먼저 확인한다. 대체한 리뷰와 독립 리뷰를 구분하고 API/테스트/문서의 불일치를 최종 diff에서 다시 검사한다. 머지는 항상 현재 PR head에 대한 별도 승인 후 실행한다.
