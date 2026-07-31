# Kafka4 카탈로그 동기화

## 배경

Spring Kafka 4.1의 내장 테스트 인프라가 Kafka 4.2 테스트 ABI를 요구하므로,
`bluetape4k-dependencies`는 공유 `kafka4` 호환 버전을 `4.3.1`에서 `4.2.1`로
변경했다.

## 결정

로컬에서 더 최신 Kafka 클라이언트 패치 버전을 유지하는 대신, 로컬 관리
카탈로그를 `bluetape4k-dependencies`와 일치시킨다.

## 결과

이제 `kafka4`는 `4.2.1`로 해석되며 `spring-kafka4`는 `4.1.0`을 유지한다.

## 향후 규칙

`bluetape4k-dependencies`의 공유 별칭을 변경한 뒤에는 다운스트림 동기화 검사기를
실행하고, 이전 별칭 값을 유지하는 모든 관리 대상 라이브러리 저장소를 업데이트한다.
