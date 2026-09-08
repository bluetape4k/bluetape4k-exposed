# #857 스트리밍 설계 검토

## 범위와 판정

대상은 [설계 명세](../superpowers/specs/2026-09-08-issue-857-clickhouse-streaming-design.md)다. 구현 기준은 `d5fb9602491ad64811bffcda227e3b2deecf9eea`이며, 검토 중 production 코드는 변경하지 않았다.

명세 검토 결과는 `PASS`, 통합 P0=0/P1=0이다. 이는 설계 계약의 판정이며 구현·테스트·성능·머지 승인이 아니다. 작성된 명세의 사용자 검토는 `PENDING`이다.

## 관점별 수행 근거

| 관점 | 수행 방식과 실제 범위 | 최종 결과 |
|---|---|---|
| 성능 | 독립 `spec_perf857`, `code-reviewer` (`gpt-5.6-luna`, `max` 역할 설정), 명세 읽기 및 수정 후 재검토 | PASS, P0/P1 없음 |
| 안정성 | 독립 `spec_stable857`, `verifier` (`gpt-5.6-luna`, `max` 역할 설정), 명세 읽기 및 수정 후 재검토 | PASS, P0/P1 없음 |
| 보안 | 독립 `spec_security857`, `code-reviewer` (`gpt-5.6-luna`, `max` 역할 설정), 명세 읽기 및 수정 후 재검토 | PASS, P0/P1 없음 |
| 운영 | `spec_ops857` 생성 시 `agent thread limit reached`; 메인 inline fallback | PASS, 독립 검토 아님 |
| 개발자/API | 같은 세션 thread 한도로 추가 독립 요청 없이 메인 inline fallback | PASS, 독립 검토 아님 |
| 사용자/호출자 | 같은 세션 thread 한도로 추가 독립 요청 없이 메인 inline fallback | PASS, 독립 검토 아님 |
| 통합 | 메인: 원본 소스 대조, 중복·심각도 조정, 문서·증거·범위 검토 | PASS, P0=0/P1=0 |

독립 리뷰는 3개 관점만 완료했다. 나머지 3개를 독립 PASS로 계산하지 않는다. 메인 세션의 실제 모델/effort는 도구로 확인되지 않아 모델 이름을 추정하지 않는다. 독립 역할의 모델 표기는 실행 도구의 역할 설정이며 별도 공급자 실행 영수증을 확보한 것은 아니다.

## 지적과 조치

| 최초 우선순위 | 관점·지적 | 반영 및 재검토 |
|---|---|---|
| P1 | 성능: 느린 collect의 연결 장기 점유와 pool 복구 기준 누락 | 활성 collect당 연결 1개, 호출자 동시성·timeout 책임, 크기 2 pool 고갈·반환 테스트 추가. 독립 재검토 PASS |
| P2 | 성능: 메모리 판정 기준·heap 외 지표 누락 | warmup·반복·순서, 10배 행 증가 대비 live heap 3배 이하, RSS/direct/JFR 추가. 지연 SLO는 범위 밖으로 명시 |
| P3 | 성능: 매퍼의 dispatcher 점유 설명 부족 | JDBC와 매퍼가 같은 dispatcher에서 실행됨을 명시하고 짧은 변환 전제 추가 |
| P1 | 안정성: 정리 예외 우선순위 누락 | 직접 close와 Exposed 기록 정책을 구분한 원인별 표 및 AC-05/06 추가. 독립 재검토 PASS |
| P1 | 안정성: 취소 후 생산자 정리·join 순서 부족 | 채널/Job 소유권, 원인 저장, 취소·join·종료 결과 확인 순서 명시. 독립 재검토 PASS |
| P1 | 안정성: queryList 재시도 계약 누락 | 기존 suspendTransaction 정책 상속과 시도 횟수 테스트 명시. 독립 재검토 PASS |
| P2 | 안정성: next·매퍼 실행 중 취소 모호 | next 직후 및 send 전 취소 확인, 비-suspend 매퍼가 반환해야 종료됨을 명시 |
| P1 제안 | 보안: 독립 연결의 테넌트 상태와 자원 예산 | 라이브러리가 인증 계층이 아님을 명확히 하고 독립 연결용 권한·조건, pool/timeout/결과 한도의 호출자 책임을 명시. 독립 재검토에서 P0/P1 없음 확인 |
| P2/P3 | 보안: 입력 바인딩·로그·자원 종속 반환 | 바인딩/식별자 정책, 추가 민감정보 로그 금지, 외부 오류 정제 책임, detached 값 전제 명시 |

## Inline 및 통합 검토

- 운영: caller-owned pool/dispatcher를 닫지 않으며 timeout·대여·취소 후 후속 SELECT로 복구를 관측한다. 기존 API는 그대로 남겨 신규 호출을 기존 수집 경로로 되돌릴 수 있다. 배포·Full Nightly를 검증의 자동 후속 작업으로 사용하지 않는다.
- 개발자/API: 기존 3인자 JVM 메서드를 유지하고 신규 overload는 인자 수를 구분한다. 루트 API baseline, Kotlin 호출, Query 표현식 중복·별칭·nullable 매핑을 수용 기준에 포함했다. 생산 코드를 작성하기 전 공개 API 컴파일 증거가 필요하다.
- 사용자/호출자: List 즉시 수집, 기존 Flow 전체 수집, 신규 Flow 점진적 처리의 차이가 표에 드러난다. 매퍼·테넌트 상태·블로킹 취소 한계를 숨기지 않는다. README 두 언어와 중앙 매뉴얼 소유권은 AC-09 및 후속 계획에 연결된다.
- 통합: Exposed sources JAR의 `Query.kt:301–308`, `JdbcTransaction.kt:288–293`, `ResultRow.kt:169–170`, `Transactions.kt:341–412,449–463`을 직접 대조했다. 공개 ResultRow 변환과 직접 커서 접근은 소스 근거가 있지만 실제 실행 성공은 아직 입증하지 않았다.
- 메인 운영 절차 점검: heartbeat 상태의 `probe-sent` 요청이 lifecycle 상태 오류로 거부되었다. `resume-check`에서 run은 정상 running, 미완료 replacement 0개로 확인했다. 이후 native 결과를 직접 수신했다. 이 helper 오류를 리뷰 성공 근거로 사용하지 않았다.

## 문서 검증과 남은 항목

명세와 이 통합 리뷰 각각에 적용한다.

- SPW-01 PASS: 한국어 기술 명세/리뷰, 구현자·유지보수자 대상, 기준 ref와 현재 소스 고정.
- SPW-02 PASS: 명세는 API·대안·실패·호환성·AC-01~09·DoD, 리뷰는 범위·지적·조치·한계 포함.
- SPW-03 PASS: KO-01~07 의미·용어·문장·표 검토. 식별자와 불확실성 보존, 용어 검사 결과 별도 실행.
- SPW-04 PASS: 수정 계약과 원본 소스 및 각 리뷰 지적 대조. 버퍼 0개와 미전달 매핑 결과 1개를 구분.
- SPW-05 PASS: 최종 Markdown 재독 및 링크·표·상태 검토. 명세와 리뷰의 문서 검증은 구현 검증과 분리.

미완료 항목은 명세 사용자 검토, 실행 계획, 구현, 모듈/API/실서버/메모리 검증, 중앙 매뉴얼, 연구 기록 게시·검색 반영이다. 테스트 통과 수는 0으로 주장하는 대신 **아직 실행 증거 없음**으로 기록한다. 전체 #857 상태는 `PENDING`이다.
