# 이슈 #723 Ktor assertion·null-safety lesson

## 재발 방지 규칙

1. `bluetape4k-assertions`가 이미 test dependency에 있으면 raw JUnit assertion을
   남겨 두지 말고 intent-specific matcher로 표현한다.
2. nullable Micrometer lookup은 `assertNotNull` 뒤 `!!`로 되살리지 않는다.
   `shouldNotBeNull()`의 contract 반환값을 변수 또는 체인으로 사용한다.
3. exception type 검증은 `assertFailsWith`로 통일하되, cause/message와 secret-redaction
   계약은 별도 matcher로 계속 검증한다.
4. Ktor readiness/metrics 테스트는 timeout, cancellation, executor/latch cleanup과
   metric cardinality를 assertion 표현 변경과 분리해 보존한다.
5. 테스트 진단에는 `println`, `System.out`, `System.err`를 추가하지 않는다. 실패는
   Bluetape matcher와 기존 테스트 보고서로 관찰한다.

## 검증 체크리스트

- [x] raw `org.junit.jupiter.api.Assertions`/`kotlin.test` assertion import 0건
- [x] `assertThrows` 0건
- [x] touched Ktor test source의 `!!` 0건
- [x] `compileTestKotlin`·targeted 42개·module 59개·detekt PASS
- [ ] hosted PR CI와 reviewer 결과
