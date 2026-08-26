# Issue #719 UUID 공개 ABI 충돌 제거 lesson

## 배경

`Uuid*`와 `UUID*` public repository 이름은 macOS case-insensitive
filesystem에서 동일한 JVM class 경로로 취급될 수 있었다. 결과적으로
`checkKotlinAbi`가 호스트 파일시스템에 따라 서로 다른 public descriptor를
보여 주었다.

## 결정

Kotlin 식별자는 `KotlinUuid*`, Java 식별자는 `JavaUuid*`를 canonical
이름으로 사용한다. 1.x 이름은 deprecated source-only `typealias`로만
제공하고 binary forwarding class는 만들지 않는다. 2.0 consumer는
canonical 이름으로 재컴파일해야 한다.

## 결과와 검증

- JDBC/R2DBC의 일반·soft-delete 네 계열에 canonical interface를 적용했다.
- API baseline을 실제 산출물 기준으로 갱신한 뒤 두 모듈 `checkKotlinAbi`가
  통과했다.
- JDBC H2 218개 테스트(25개 기존 skip), R2DBC H2 204개 테스트(7개 기존
  skip)가 통과했다.
- 두 모듈 Detekt, focused naming test, `git diff --check`가 통과했다.
- `jar tf`와 `javap`에서 canonical public class와 Kotlin/Java UUID generic
  계약을 확인했고 legacy binary class는 0건이었다.

## 예상 밖의 점

ABI baseline을 단순히 삭제하거나 갱신하는 방법은 macOS 충돌을 숨길 뿐이다.
실제 class 목록과 descriptor를 함께 검사해야 source/API migration이
플랫폼 독립적인지 확인할 수 있다. source-only alias는 source migration
완충재가 되지만 이미 컴파일된 consumer의 binary 호환성을 복구하지 않는다.

## 다음 보호 장치

- public 이름에 case-only 차이가 추가되지 않도록 canonical naming rule을
  migration 문서와 API review checklist에 유지한다.
- 공개 이름 변경 시 `checkKotlinAbi`, `jar tf`, `javap`, macOS와 Linux
  artifact 비교를 한 묶음으로 실행한다.
- Type A 공개 API 변경은 구현 전에 설계·계획 문서와 독립 7-Tier review를
  workflow receipt에 연결한다.

## 문서 용어 감사 예외

`bluetape-writer`의 `audit-korean-terms.mjs`를 변경된 한국어 파일에 실행한
결과, `exposed/r2dbc/README.ko.md:202`의 기존 `snapshot` 용어 1건이
검출되었다. 해당 줄은 이번 diff에 포함되지 않고 UUID migration 의미와도
무관하므로 문맥 예외로 기록하고 수정하지 않았다.
