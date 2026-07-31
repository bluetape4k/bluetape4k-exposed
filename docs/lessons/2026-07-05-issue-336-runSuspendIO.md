# 교훈 — 이슈 #336 runSuspendIO 마이그레이션

## 결정

실제 IO, 컨테이너, 캐시 클라이언트 또는 저장소/데이터베이스 경계를 다루는 Exposed DB/cache/R2DBC/JDBC suspend 테스트에는 `runSuspendIO`를 사용한다. 순수 코루틴 의미론, 가상 시간 동작, 취소/단위 목, 비 IO 코덱 테스트에는 `runTest`를 유지한다.

## 이유

`runTest`는 가상 시간 기반 코루틴 테스트에 최적화되어 있다. DB/cache/Testcontainers 경로가 실수로 가상 시간이나 테스트 스케줄러 동작에 의존하지 않으려면 실제 디스패처와 타임아웃 동작이 필요하다.

## 향후 준수 사항

Exposed 모듈에 suspend 테스트를 추가할 때는 다음을 따른다.

1. Exposed DB/R2DBC, Redis/Redisson/Lettuce, 캐시 저장소, Spring 저장소 또는 Testcontainers 기반 도우미를 호출한다면 `runSuspendIO`를 사용한다.
2. 코루틴 전용 취소, 가상 시간 지연, 재시도 타이밍 또는 순수 인메모리 코덱 동작을 검증한다면 `runTest`를 유지할 수 있지만, 테스트 안에 명확한 근거를 추가하거나 보존한다.
3. Testcontainers 또는 공유 캐시 서비스가 관련된 경우 영향받는 모듈을 `--no-parallel`로 직렬 실행한다.
