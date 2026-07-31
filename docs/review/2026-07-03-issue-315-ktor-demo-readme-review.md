# 이슈 #315 Ktor 데모 README 리뷰

## 범위

- 이슈: #315 `docs(examples): add README pair for ktor-exposed-demo`
- 리뷰한 파일:
  - `examples/ktor-exposed-demo/README.md`
  - `examples/ktor-exposed-demo/README.ko.md`
  - `examples/ktor-exposed-demo/src/main/kotlin/io/bluetape4k/examples/exposed/ktor/KtorExposedDemoApplication.kt`
  - `examples/ktor-exposed-demo/src/main/kotlin/io/bluetape4k/examples/exposed/ktor/KtorExposedDemoResources.kt`
  - `examples/ktor-exposed-demo/src/test/kotlin/io/bluetape4k/examples/exposed/ktor/KtorExposedDemoApplicationTest.kt`
  - `ktor/exposed/README.md`
  - `ktor/exposed/README.ko.md`

## 검토 결과

- P0/P1: 없음.
- 새로운 README 쌍은 공개 계약을 소스에 근거해 설명한다. 데모가 H2 JDBC/R2DBC
  리소스를 소유하고 `installBluetape4kExposedKtor()`에 전달하며, Ktor 수명 주기에
  맞춰 리소스를 닫는다.
- 문서는 `installBluetape4kExposedKtor()`가 데이터베이스, 풀, 디스패처,
  Ktor 코어 또는 콘텐츠 협상을 생성한다고 암시하지 않는다.
- 영문과 한국어 파일은 개요, 리소스 소유권, Ktor 구성, 라우트, 실행 방법,
  모듈 README 링크를 동일하게 다룬다.

## 검증

- `git diff --check`: PASS.
- 라우트, `StatusPages`, `ApplicationStopped`, `installHealthRoutes = true`,
  `:examples-ktor-exposed-demo:test`를 대상으로 소스 참조를 확인한 결과: PASS.
- `./gradlew :examples-ktor-exposed-demo:test`: PASS, 테스트 1개 통과,
  `BUILD SUCCESSFUL in 15s`.

## 잔여 위험

- 문서만 변경했다. 프로덕션 소스와 테스트 소스는 변경하지 않았다.
