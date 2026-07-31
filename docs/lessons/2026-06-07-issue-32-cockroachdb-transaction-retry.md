# 이슈 #32 CockroachDB 트랜잭션 재시도

## 배경

#30에서 모듈을 만들고 #31에서 DDL 호환성 표면을 제한한 뒤, `exposed-cockroachdb`에는
직렬화 트랜잭션 재시도 지원이 필요했습니다.

## 결정

Exposed의 일반 트랜잭션 재시도 설정에 의존하는 대신 CockroachDB 전용 helper를
추가합니다. Exposed 1.3.0은 `SQLException`을 광범위하게 재시도하므로, helper는 내부
Exposed 트랜잭션을 한 번만 시도하게 하고 메시지가 `restart transaction`으로 시작하는
SQLSTATE `40001` 오류만 재시도합니다.

## 결과

이 모듈은 이제 다음을 제공합니다.

- `CockroachTransactionRetryOptions`
- `Throwable.isCockroachRetryableTransactionError()`
- `withCockroachTransaction(...)`

README 로캘 쌍과 CHANGELOG는 지원되는 재시도 경로를 문서화합니다. PR 리뷰 피드백에
따라 helper 이름을 `withCockroachTransaction`으로 변경하고 inline으로 만들었으며,
옵션에 `Duration` 기반 companion `invoke` 오버로드를 추가했습니다.

## 검증

- 컴파일: PASS
- 테스트: PASS, 24개 테스트
- Kover XML: PASS
- `git diff --check`: PASS
- wiki 조사 노트: 색인, 임베딩, 질의 가능 상태

## 향후 지침

재시도할 수 없는 SQL 오류를 재시도 대상 밖에 유지해야 한다면 CockroachDB 재시도 작업에
Exposed `maxAttempts`만을 유일한 해법으로 활성화하지 마세요. 이후 이슈에서
savepoint 기반 고급 재시도 프로토콜을 채택하지 않는 한, 재시도 분류는 CockroachDB가
문서화한 SQLSTATE/메시지 서명에 연결해 유지해야 합니다.
