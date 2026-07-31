# 이슈 #289 JSON SQL 리터럴 이스케이프

## 배경

JSON column type은 직렬화한 JSON을 single quote 안에 `notNullValueToDB(value)`로 직접 보간해 렌더링했습니다. 이 방식은 single quote, carriage return, line feed에 대한 Exposed 문자열 리터럴 이스케이프를 우회했습니다.

## 결정

직렬화한 JSON 본문에는 Exposed `TextColumnType.nonNullValueToString`을 재사용하고, 필요한 경우에만 dialect별 JSON prefix를 추가합니다.

## 이유

- 각 JSON 모듈에서 별도의 escape table을 유지하지 않아도 됩니다.
- JSON, JSONB 기본 문자열, SQL 리터럴 렌더링을 Exposed core 문자열 리터럴 의미론에 맞춰 유지합니다.
- 이스케이프한 리터럴 주변의 `JSON ` prefix를 보존하여 기존 H2 동작을 유지합니다.

## 검증 메모

- 리터럴 렌더링이 버그 표면일 때는 column-type 계층에 회귀 테스트를 추가합니다.
- JSONB/default helper가 JSON column-type 렌더링 경로를 상속하므로 unit test 뒤에 모듈 테스트를 실행합니다.
