# #751 R2DBC Redisson 예외 로그 비노출

## 배경

`R2dbcEntityMapLoader`, `R2dbcEntityMapWriter`, `R2dbcExposedEntityMapLoader`가
실패 로그에 `Throwable`을 직접 전달하고 있었다. Logback은 이 인자를 stack trace와
예외 message로 렌더링하므로, DB 오류에 포함된 credential·token·SQL 또는 caller-owned
payload가 운영 로그로 전파될 수 있었다. 이 작업은 #611의 generic payload redaction
계약을 R2DBC Redisson의 예외 경로까지 재확인하는 후속 조치다.

## 실패한 가정/판단

- 실패한 가정/판단: 이미 메시지 본문에 ID나 payload가 없으므로 예외를 logger 인자로
  전달해도 안전하다고 판단했다.
- 발견 증거 또는 교정: Logback `throwableProxy`가 원시 예외 message와 stack trace를
  별도로 렌더링하며, 민감한 문자열을 주입한 RED 테스트가 이를 재현했다.
- 수정 결정: 모든 loader/writer 오류 로그는 예외 객체를 전달하지 않고 작업명,
  bounded timeout, `errorType`만 lazy message로 기록한다. `CancellationException`은
  기존 계약대로 로그 없이 재전파한다.
- 향후 예방 확인: `RecordingLogAppender` 회귀 테스트에서 관련 event의
  `throwableProxy == null`과 민감 문자열 부재를 함께 검증한다.

## 구현 및 검증

- `load` 단건 실패, `loadAllKeys` producer 실패/timeout, `write`, `delete`, Exposed
  loader DB 실패를 민감 문자열과 함께 주입했다.
- RED 단계에서 기존 직접 throwable 로깅과 timeout 메시지 누락이 실패함을 확인했다.
- GREEN 단계에서 모든 대상 로그가 `errorType`만 포함하고 throwable을 첨부하지 않음을
  확인했다.
- 취소 경로는 오류 로그를 남기지 않고 cancellation을 재전파하는 기존 lifecycle
  계약을 고정했다.
- `:bluetape4k-exposed-r2dbc-redisson:test`를 no-cache/no-configuration-cache로
  두 번 실행했다: 각 248 tests, 0 failures, 3 existing dialect skips.
- `git diff --check`와 #611 영향 범위의 raw `log.error/log.warn` 인자 스캔을 통과했다.

## 남은 경계

예외 자체는 caller에게 계속 재전파하므로 애플리케이션이 별도 logger에서 원시 예외를
기록하면 이 모듈의 redaction 경계를 우회할 수 있다. 이 모듈의 운영 로그는
작업명·안전한 타입·bounded count/timeout만 유지한다.
