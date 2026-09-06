# #804 명세·계획 검토

## 범위와 출처

`2026-09-06-issue-804-crypt-contracts-design.md`와 같은 이름의 plan을 upstream 1.5.0 소스·로컬 JAR/POM·기존 fixture와 대조했다. security native lane은 제한 시간 내 사용할 결과를 제공하지 않아 중단했다. stability/performance native lane은 `agent thread limit reached`로 실행되지 않았다. 다른 독립 lane도 같은 제한으로 사용할 수 없어 아래는 모두 main의 **inline fallback review**다. 독립 또는 특정 모델의 검증으로 표시하지 않는다.

## 명세 검토와 계획 검토

| 관점 | 명세 판정 | 계획 판정 | 근거·처리 |
|---|---|---|---|
| 성능 | PASS | PASS | 테스트 비용을 production 권장값과 분리한다. production 해시 비용·dispatcher 정책은 호출자 책임이며 성능 개선을 주장하지 않는다. |
| 안정성 | PASS | PASS | 기존 withTables의 수명·종료 경계를 재사용한다. backend별 순차 실행과 고유 테이블을 사용하고 전역 로거를 변경하지 않는다. |
| 보안 | PASS | PASS | column-bound hash, raw 저장값, null, 재저장, 잘못된 인증 시 미갱신, 오류·로거 평문 미노출을 구분한다. custom hasher까지 자동 redaction된다는 주장을 금지한다. |
| 운영 | PASS | PASS | 새 production 의존성 없이 opt-in Crypto/BouncyCastle 요구를 명시한다. 배포·catalog·로그 정책은 자동 변경하지 않는다. |
| API | PASS | PASS | upstream public API로 충분하다. 기존 artifact·API·ABI를 유지하고 test scope만 확장한다. |
| 호출자 | PASS | PASS | 두 README locale, nullable·rehash 예제, 알고리즘별 길이·직접 encodedValue 로깅 금지를 포함한다. |

## 통합 판정

명세의 검증 계약 1–5는 계획 작업 2, 계약 6은 작업 1·3·4에 연결된다. 별도 adapter나 알고리즘 구현은 없다. public API·다중 계층 설계 변경은 없지만 보안 의존성 선택과 두 backend 검증이므로 승인된 Type A를 유지한다.

P0=0, P1=0. **명세·계획 PASS**, 구현·보안 테스트·최종 리뷰·CI는 아직 대기다. 작성 문서의 SPW-01–05는 출처, 수용 기준, 책임 경계, 한국어 기술 용어, 최종 read-back을 확인했다. 용어 검사 2개 파일 PASS. 이후 테스트에서 반증되는 가정은 이 문서와 명세에 반영하고 해당 검토를 다시 수행한다.
