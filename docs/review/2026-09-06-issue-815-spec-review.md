# #815 제공자 계약 설계 리뷰

## 범위와 검증 출처

- 대상: [설계 명세](../superpowers/specs/2026-09-06-issue-815-test-support-contracts-design.md).
- 기준 코드: `03597729429fa0fe2ae74f2548640fdf683e082a`.
- 최초 명세 SHA-256: `4e4b1af997d537c77b3719fef534fdf049e92b07fa02016102082b520d05522d`.
- 보완 명세 SHA-256: `c454d89e464800db5cbcb2c374c57dd44dd68ee1fea5dd5e0b7a460e02de4ebc`.
- 검증 방식: **inline fallback 설계 리뷰**. 독립 검증이 아니다.

성능·안정성·보안 native reviewer 생성은 각각 `agent thread limit reached`로 실패했다. 독립 결과와 부분 지적은 없었다. 같은 세션에서 확인된 용량 제한 때문에 나머지 관점의 spawn을 반복하지 않고, 사용자 지침에 따라 여섯 관점을 주 세션에서 검토했다. 모델별 또는 외부 reviewer의 검증 결과로 표현하지 않는다. machine receipt의 실패 이력도 유지한다.

## 발견 사항과 수정

행 번호는 보완 전 최초 명세 기준이다. 수정 후 근거는 명세의 해당 절과 AC로 추적한다.

| 우선순위 | 관점 | 최초 근거 | 수정과 재검토 |
|---|---|---|---|
| P1 | 안정성 | 연결과 일시 구성, 63–68행: 일시 wrapper의 종료 책임과 hook 등록 실패 후 상태가 불명확 | transaction 종료 후 provider 등록만 해제하고 외부 pool은 보존한다. 기본 wrapper 생성과 hook 등록이 모두 성공해야 캐시를 공개하도록 보완했다. AC-05/06에 생성·등록 실패와 후속 호출을 연결했다. |
| P1 | 보안 | 공개 API 38–43행과 정리 계약 79행: 임의 key/URL 로그, cascade 대상의 소유 범위 누락 | 로그의 key·URL·설정·원문 예외 메시지 노출을 금지하고 테스트 전용 table/schema 소유 조건을 명시했다. AC-09에 민감값 sentinel 검증을 추가했다. |
| P1 | 호출자 | 공개 API 40행·연결 계약 67행: callback이 매번 pool을 생성해도 종료 시 한 번만 정리하는 것으로 읽힐 수 있음 | callback은 호출자 소유 장기 연결 공급원을 참조하는 비소유 wrapper를 생성하도록 제한했다. 요청별 fixture나 callback 내부의 호출별 pool 생성은 지원하지 않는다. |
| P2 | 성능 | 연결 계약 63–64행: 첫 configure에 필요한 생성 횟수 미정 | 최초 기본+일시 wrapper 두 번, 이후 기본 캐시 재사용, 구성 callback 1회 실행을 명시하고 AC-05/06에 횟수 검증을 연결했다. 성능 개선 수치는 주장하지 않는다. |
| P2 | API | 중첩 호출 58행: 독립 coroutine이나 종료된 토큰을 중첩으로 오인할 수 있음 | 활성 진입 토큰, 독립 대기, 종료 토큰 무효화 계약을 구분하고 AC-03에 혼합 blocking/suspend·자식 coroutine 검증을 추가했다. |

## 여섯 관점의 최종 검토

| 관점 | 확인한 계약 | 결과 |
|---|---|---|
| 성능 | 인스턴스별 permit, 전역 key registry 없음, 기본 캐시와 최초 configure 생성 횟수, 독립 fixture 진행 | 설계상 미해결 P0/P1 없음 |
| 안정성 | 대기/획득 시 취소, 중첩 호출, 상태 복원, 초기화 실패, 일시 등록 해제, DDL 예외 우선순위 | 설계상 미해결 P0/P1 없음 |
| 보안 | 호출자 소유 자원, 신뢰된 테스트 코드만 사용, cascade 대상 제한, 민감값 로그 배제 | 설계상 미해결 P0/P1 없음 |
| 운영 | JVM 수명 fixture, callback 독립 정리, 실패 진단, 배포 제외, 제공자 rollback, POM 도구의 실제 위치 | 설계상 미해결 P0/P1 없음 |
| 개발자/API | 기존 JVM descriptor와 기본 인자 보존, backend별 overload, custom key와 legacy currentTestDB 구분 | 설계상 미해결 P0/P1 없음 |
| 호출자 | DB별 adapter 공유, 별도 package 소비자 검증, domain fixture 제외, DDL opt-out, unsupported scope | 설계상 미해결 P0/P1 없음 |

통합 판정: 보완 명세에 대한 **설계 리뷰 PASS**, 미해결 P0=0, P1=0, P2=0. 구현·ABI·취소 안정성을 입증한 결과는 아니다. 실제 코드와 fault-injection 테스트, publication metadata 검증은 후속 구현 계획의 필수 항목이다. 신규 API 이름과 callback 형태는 작성 명세를 사용자가 확인한 뒤 확정한다.

## 문서 검증과 남은 단계

- 명세와 이 리뷰 각각에 `SPW-01`부터 `SPW-05`를 적용했다. 독자는 유지보수자이며 한국어 기술 문서로 작성했다. 현재 소스와 제안 계약, 기존 검증과 후속 검증을 구분했다.
- 한국어 용어 audit는 두 파일 모두 findings=0이며 `git diff --check`도 통과했다. 최종 파일을 다시 읽고 원본 코드·버전·명령·예외 문자열 보존을 확인했다. SPW-01부터 SPW-05까지 명세 5/5, 리뷰 5/5 PASS다.
- 기준 JUnit 결과는 이전 단계의 JDBC 170 PASS/16 skipped와 R2DBC 21 PASS다. 이번 단계는 문서만 변경했으며 테스트를 다시 실행하지 않았다.
- 후속 단계: 작성 명세의 사용자 검토 → AC별 구현 계획·계획 리뷰 → RED/GREEN 구현 → 실제 소비자·POM·CI 검증.
- 독립 reviewer 실행 증거는 없다. 현재 fallback은 설계 리뷰에만 해당하며 후속 코드 리뷰의 출처를 대신하지 않는다.

## 재발 방지

연결 factory와 캐시를 공용 API로 분리할 때는 생성 성공뿐 아니라 첫 임시 구성, 종료 hook 등록 실패, 임시 wrapper 등록 해제, 외부 pool 소유권을 함께 정의한다. generic key의 타입 호환성을 확보해도 로그의 `toString()` 노출이나 같은 DB에 fixture를 반복 생성하는 오용까지 안전해지는 것은 아니다. 이 항목은 구현 전 AC-03/05/06/09 검증 계획에 연결하고 최종 lesson에서 구현 결과와 다시 대조한다.
