---
manualId: serialization-and-encryption
title: 직렬화, 암호화, 타입 컬럼 선택
locale: ko
releaseRef: 1.12.1
---

# 직렬화, 암호화, 타입 컬럼 선택

이 모듈들은 모두 Exposed 컬럼 경계에서 Kotlin 값을 변환하지만 해결하는 문제는 다릅니다. JSON codec은 문서 형태를 저장하고, Tink는 인증 암호화로 값을 보호하며, measured 컬럼은 물리 측정값을 하나의 기준 단위로 바꿉니다. 모듈을 고른 뒤에도 각 데이터의 수명 주기는 애플리케이션이 관리해야 합니다.

## 저장 계약부터 고르기

| 요구 사항 | 모듈 | DB 표현 | 호환성 책임 |
| --- | --- | --- | --- |
| Jackson 2 JSON 생태계 | `bluetape4k-exposed-jackson2` | JSON/JSONB | 애플리케이션 JSON 모델과 Jackson 2 mapper |
| Jackson 3 JSON 생태계 | `bluetape4k-exposed-jackson3` | JSON/JSONB | 애플리케이션 JSON 모델과 Jackson 3 mapper |
| Fastjson2 JSON 생태계 | `bluetape4k-exposed-fastjson2` | JSON/JSONB | 애플리케이션 JSON 모델과 Fastjson2 serializer |
| 동등 검색이 필요 없는 민감한 필드 | `bluetape4k-exposed-tink` AEAD | 인코딩 또는 binary 암호문 | 애플리케이션/KMS의 keyset과 AAD 정책 |
| 동등 검색이 필요한 민감한 필드 | `bluetape4k-exposed-tink` DAEAD | 같은 입력에 반복되는 암호문 | 애플리케이션/KMS와 패턴 노출 수용 여부 |
| 타입으로 구분할 물리 측정값 | `bluetape4k-exposed-measured` | 기준 단위 `DOUBLE` | 스키마 소유자와 단위·정밀도 정책 |

API가 짧아 보이는 모듈부터 고르면 안 됩니다. 배포, 재시작, 이름 변경, 의존성 업그레이드, rollback 뒤에도 무엇을 읽어야 하는지부터 정하세요.

## JSON은 저장된 API다

`jackson`, `jacksonb`, `fastjson`, `fastjsonb`는 Kotlin 값을 직렬화하지만 DB에는 Kotlin 클래스와 serializer 설정이 남지 않습니다. 프로퍼티 이름, subtype id, 날짜·시간 표현, 숫자 변환, 기본값, 알 수 없는 필드 처리 방식이 실제 저장 계약입니다.

필드를 추가할 때는 기본값을 주고 예전 payload를 테스트합니다. 호환되지 않는 변경은 다음 순서로 진행하세요.

1. 운영에서 사용하는 예전 행을 대표하는 fixture를 보관합니다.
2. Writer는 그대로 둔 채 신·구 형태를 모두 읽는 reader를 먼저 배포합니다.
3. Writer를 바꾸고 복원 실패 수를 관찰합니다.
4. 중단 후 재개할 수 있는 작은 batch로 예전 행을 변환합니다.
5. 변환 범위와 rollback 조건을 충족한 뒤 호환 코드를 제거합니다.

JSONB는 DB의 저장·조회 방식을 바꾸지만 문서 버전을 관리하지 않고 인덱스도 자동으로 만들지 않습니다. `contains`, `exists`, `extract`는 운영하는 모든 Dialect에서 검증하고 실행 계획을 확인해야 합니다.

## Jackson 2에서 3으로 옮기는 일은 마이그레이션이다

두 모듈의 Exposed DSL은 비슷하지만 패키지와 mapper 생태계가 다릅니다. Jackson 2는 `io.bluetape4k.exposed.core.jackson`과 `com.fasterxml.jackson` 타입을 사용합니다. 1.11의 Jackson 3 모듈은 `io.bluetape4k.exposed.core.jackson3`과 Jackson 3 타입을 사용합니다. 함수 이름이 비슷해도 tree node, annotation, module, serializer 설정은 서로 바꿔 쓸 수 없습니다.

세 단계에서 호환성을 증명하세요.

- 소스: 애플리케이션 import와 사용자 module이 Jackson 3에서 컴파일됩니다.
- 저장 데이터: Jackson 3 reader가 지원 범위의 Jackson 2 fixture를 모두 읽습니다.
- 운영: 두 reader를 함께 배포하고 backfill, 지표, rollback을 연습합니다.

저장 JSON 형태를 바꿀 이유가 없다면 라이브러리를 옮기는 동안 그 형태를 고정하세요. 의존성 마이그레이션과 문서 재설계를 한 번에 하지 않는 편이 안전합니다.

## Tink 컬럼 매핑은 key 관리가 아니다

Tink 모듈은 이미 구성된 `TinkAead` 또는 `TinkDeterministicAead`를 받습니다. 애플리케이션이 KMS나 보호된 secret 저장소에서 영속 keyset을 불러와야 합니다. 시작할 때마다 새로 만든 process-local key는 재시작 뒤 기존 행을 복호화하지 못하고 여러 노드에서 안전하게 공유할 수도 없습니다.

검색이 필요 없으면 무작위 AEAD를 기본으로 선택하세요. Deterministic AEAD는 평문과 associated data가 같을 때 같은 암호문을 만들므로 동등 검색이 되지만 반복값 패턴을 노출합니다. 범위·prefix·정렬·부분 문자열 검색은 지원하지 않습니다.

테이블 DSL은 기본적으로 암호문을 `bluetape4k-exposed-tink:v1:<table>:<column>`에 묶습니다. 다른 컬럼으로 암호문을 복사해도 풀리지 않는 장점이 있지만, 테이블명이나 컬럼명 변경은 암호학적 데이터 마이그레이션이 됩니다. 전환 중에는 예전 associated data로도 읽고, 새 binding으로 행을 다시 쓴 다음 완료를 증명하고 호환 코드를 제거해야 합니다.

Key를 회전할 때는 예전 복호화 key를 유지한 상태에서 새 primary key로 쓰고, 행을 backfill하고, 변환 수와 실패 지표를 검증한 뒤 정책에 따라 예전 key를 폐기합니다. 회전 설계에 필요하면 암호문 밖에 key/version 정보를 보관하세요.

## 측정 컬럼에는 숫자만 저장된다

Measured 컬럼은 입력값을 선언한 기준 단위로 변환해 `DOUBLE`로 저장합니다. `length`는 metre, `energy`는 joule, `temperature`는 Kelvin이며 다른 편의 DSL도 각자 기준이 고정돼 있습니다. 처음 입력한 단위는 복원할 수 없습니다.

행을 변환하지 않고 기준 단위를 바꾸면 같은 숫자의 의미가 조용히 달라집니다. 데이터 마이그레이션으로 처리하고 컬럼명, 마이그레이션 문서, export, 대시보드, alert에 단위를 표시하세요. 이진 부동소수점을 쓰므로 허용 오차를 정하고, 도메인에서 허용하지 않는 `NaN`, 무한대, 비현실적인 값은 애플리케이션 경계에서 막아야 합니다.

## 검증표

| 변경 | 필요한 증거 |
| --- | --- |
| JSON 필드 추가 | 예전 fixture가 문서화한 기본값으로 읽히고 새 fixture가 round-trip 됨 |
| JSON 필드·타입·subtype 변경 | 두 reader, backfill, 지표, rollback |
| Jackson 2 → 3 | 컴파일·import 확인과 버전 간 저장 fixture |
| JSON → JSONB | DDL·데이터 변환, Dialect별 조건·인덱스·실행 계획 |
| Tink key 회전 | 재시작·다중 노드 key 공급, 예전 decrypt/새 encrypt, backfill 범위 |
| Tink 사용 컬럼명 변경 | 신·구 AAD 호환과 재암호화 증거 |
| Measured 기준 단위 변경 | 숫자 backfill과 DB 원시값 assertion |
| 숫자 정밀도 정책 변경 | 경계·허용 오차 테스트와 영향받는 값의 변환 |

변환 실패를 조사한다고 문서 본문, 평문, 암호문, key material을 로그에 남기면 안 됩니다. 테이블, 컬럼, 레코드 id, 노출해도 되는 codec/key version, 예외 종류만 기록하세요.

## 학습 경로

1. 이 가이드에서 저장 계약을 고릅니다.
2. 해당 모듈 문서를 읽습니다: [Jackson 2](../modules/bluetape4k-exposed-jackson2.md), [Jackson 3](../modules/bluetape4k-exposed-jackson3.md), [Fastjson2](../modules/bluetape4k-exposed-fastjson2.md), [Tink](../modules/bluetape4k-exposed-tink.md), [measured](../modules/bluetape4k-exposed-measured.md).
3. 모듈 테스트의 샘플을 운영과 비슷한 fixture로 바꿔 실행합니다.
4. [트랜잭션 경계](./transaction-boundaries.md)에 맞춰 변환을 repository 경로에 넣습니다.
5. 이미 쓰는 컬럼 계약을 바꾸기 전에 마이그레이션과 rollback 증거를 작성합니다.

## 근거 자료

- [Jackson 2 컬럼](../../../../exposed/jackson2/src/main/kotlin/io/bluetape4k/exposed/core/jackson/JacksonColumnType.kt)
- [Jackson 3 컬럼](../../../../exposed/jackson3/src/main/kotlin/io/bluetape4k/exposed/core/jackson3/JacksonColumnType.kt)
- [Fastjson2 컬럼](../../../../exposed/fastjson2/src/main/kotlin/io/bluetape4k/exposed/core/fastjson2/FastjsonColumnType.kt)
- [Tink 테이블 DSL](../../../../exposed/tink/src/main/kotlin/io/bluetape4k/exposed/core/tink/Tables.kt)
- [Tink associated data](../../../../exposed/tink/src/main/kotlin/io/bluetape4k/exposed/core/tink/TinkColumnAssociatedDataProvider.kt)
- [Measured 컬럼](../../../../exposed/measured/src/main/kotlin/io/bluetape4k/exposed/core/measured/MeasuredColumnTypes.kt)
