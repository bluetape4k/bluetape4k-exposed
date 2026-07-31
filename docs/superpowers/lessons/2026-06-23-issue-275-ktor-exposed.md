# 이슈 #275 Ktor Exposed 통합 교훈

날짜: 2026-06-23
이슈: #275
마일스톤: 1.11.0

## 결과

자체적으로 Exposed JDBC/R2DBC 리소스를 소유하는 Ktor 애플리케이션을 위한 명시적 옵트인 통합 모듈로 `bluetape4k-exposed-ktor`를 추가했습니다.

이 모듈은 소유권 경계를 좁게 유지합니다.

- `Database` 또는 `R2dbcDatabase` 생성 없음
- 메인 소스에서 디스패처, 풀 또는 연결 팩토리 생성 없음
- 전역 Micrometer 레지스트리 사용 없음
- 애플리케이션이 이미 StatusPages를 소유하는 경우 기본 StatusPages 변경 없음
- 인증, 로깅, 트레이싱, OpenAPI 또는 콘텐츠 협상 설정 없음

## 설계 참고 사항

- `installBluetape4kExposedKtor()`는 의도적으로 기본 no-op입니다. 헬스, 준비 상태, 메트릭 및 StatusPages 통합은 호출자가 옵트인한 경우에만 설치됩니다.
- JDBC 트랜잭션 헬퍼는 Exposed JDBC가 블로킹 방식으로 동작하므로 호출자가 제공한 `CoroutineDispatcher`가 필요합니다.
- R2DBC 헬퍼는 Exposed `suspendTransaction`을 래핑하며 블로킹 디스패처 경로를 추가하지 않습니다.
- 준비 상태 라우트는 허용 목록에 포함된 백엔드 키와 상태 범주만 보고합니다. 데이터베이스 예외 세부 정보는 HTTP 응답에 포함되지 않습니다.
- `bluetape4kExposedErrors()`는 Ktor 코어 오류 응답과 조합할 수 있지만, 모듈은 이미 설치된 `StatusPages` 플러그인을 다시 열지 않습니다.
- 데모 리소스는 예제에서만 생성되며 Ktor `ApplicationStopped`를 통해 닫힙니다.

## 생성된 후속 작업

이 저장소가 새 아티팩트를 게시한 후 구현에는 공유 의존성 카탈로그 별칭이 필요합니다.

- bluetape4k-dependencies 이슈 #126:
  `Add catalog alias for bluetape4k-exposed-ktor`

## 검증 근거

- `:bluetape4k-exposed-ktor:test`가 no-op 설치, StatusPages 충돌 처리, 정보 마스킹, JDBC/R2DBC 준비 상태, 메트릭, 커밋 및 롤백을 다루는 8개 테스트와 함께 통과했습니다.
- `:examples-ktor-exposed-demo:test`가 헬스, 준비 상태 및 트랜잭션 엔드포인트를 다루는 스모크 라우트와 함께 통과했습니다.
- `:bluetape4k-exposed-bom:generatePomFileForBluetapeExposedPublication` 및 `:bluetape4k-exposed-ktor:generatePomFileForBluetapeExposedPublication`이 통과했으며, 생성된 BOM POM에 `bluetape4k-exposed-ktor`가 포함되어 있습니다.
- 루트 `dependencies --configuration nmcpAggregation`에 `project ':bluetape4k-exposed-ktor'`가 포함되고 데모 모듈은 포함되지 않습니다.
- CI 및 nightly 워크플로 업데이트에 대해 `actionlint`가 통과했습니다.
- 정적 가드에서 `ktor/exposed/src/main`에 숨겨진 디스패처/실행기/전역 레지스트리/리소스 생성이 발견되지 않았습니다.
- 정적 가드에서 `ktor/exposed/src/main`에 원시 데이터베이스 예외 세부 정보 로깅이 발견되지 않았습니다.
- 문서 시크릿 검사에서 실제 자격 증명이나 구체적인 프로덕션 연결 URL이 아닌 안전한 텍스트/코드 식별자만 발견되었습니다.

## 재사용 지침

향후 웹 프레임워크 통합에서는 통합 모듈을 명시적이며 호출자가 소유하는 방식으로 우선 유지하세요. 수명 주기, 시크릿, 디스패처 또는 플러그인 소유권의 모호성을 만들지 않는 경우에만 편의 기능을 추가하세요.
