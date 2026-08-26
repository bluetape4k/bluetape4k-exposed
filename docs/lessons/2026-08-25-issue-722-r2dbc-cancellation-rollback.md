# 이슈 #722 R2DBC cancellation·rollback lesson

## 재발 방지 규칙

1. JDBC helper의 transaction boundary를 R2DBC에 그대로 복사하지 않는다.
   R2DBC `commit()`은 드라이버에 따라 auto-commit을 켜고 transaction을 다시
   시작하지 않으므로, block DML을 검증한 뒤 되돌려야 할 때는 기존 transaction의
   savepoint를 사용한다.
2. `CancellationException`은 JVM에서 `IllegalStateException`의 하위 타입이다.
   `coInvoking.shouldThrow(IllegalStateException::class)`처럼 넓은 expected type을
   먼저 적용하면 cancellation을 정상 예외로 오인할 수 있다. 공개 helper는
   expected type이 cancellation 계열인지 먼저 판정하고, 그 외에는 cancellation을
   즉시 재전파한다.
3. cancellation 중 cleanup은 `withContext(NonCancellable)`에서 실행하되, cleanup
   failure를 primary failure의 `suppressed`에 연결한다. primary가 없으면 cleanup
   failure를 그대로 전달한다.
4. coroutine context 경계를 넘은 cleanup 예외는 stacktrace recovery로 instance가
   복제될 수 있다. primary cancellation/실패의 identity 계약과 suppressed
   cleanup의 type/message 보존 계약을 구분해 테스트한다.
5. 테스트 helper는 user-facing logging이 필요한 운영 코드가 아니다. 진단을 위해
   `println`, `System.out`, `System.err`를 추가하지 말고, 실패는 assertion과
   기존 logger/Gradle test report로 관찰한다.

## 검증 체크리스트

- [x] unexpected cancellation 원본 재전파
- [x] expected cancellation 명시 허용
- [x] savepoint rollback으로 block DML 제거
- [x] rollback/release failure의 suppressed 연결
- [x] migration fixture의 Bluetape matcher 전환
- [x] direct `bluetape4k-assertions` API dependency와 module metadata
- [x] H2 targeted/module test와 detekt
- [ ] hosted PR CI와 독립 reviewer 결과
