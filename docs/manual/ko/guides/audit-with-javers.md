---
title: "JaVers로 감사 이력 만들기"
locale: "ko"
releaseRef: "1.12.1"
---

# JaVers로 감사 이력 만들기

Exposed와 JaVers가 맡는 문제는 다릅니다. Exposed 저장소는 현재의 관계형 상태를 읽고 씁니다. JaVers는 객체 snapshot과 변경 내용, commit 메타데이터, 이력 조회를 담당합니다. 운영용 전달 테이블이나 영속성 저장소가 의도치 않게 감사 모델까지 떠맡지 않도록 책임을 분리하세요.

## 책임 경계

| 필요한 기능 | 소유자 |
| --- | --- |
| 현재 row, query, join, 트랜잭션 쓰기 | Exposed 저장소 |
| 도메인 수준 snapshot과 변경 이력 | JaVers |
| 누가·언제·왜 바꿨는지 나타내는 메타데이터 | JaVers commit property와 author context |
| 이벤트 publication 전달과 재시도 상태 | Spring Modulith publication 저장소 |
| 장기 보고서나 분석 sink | 명시적인 후속 파이프라인 |

![Spring Modulith publication 생명주기](../../assets/spring/modulith-publication.png)

Spring Modulith publication 저장소는 listener 전달이 끝났는지 알려 줍니다. 완료된 publication을 update, delete, archive할 수 있지만, 이 장부는 도메인 감사 이력이 아니므로 그렇게 소개해서는 안 됩니다.

## 연동 구조

현재 상태를 쓰는 작업에 비즈니스 트랜잭션을 둡니다. JaVers commit을 언제 만들고 실패를 어떻게 복구할지 명시적으로 정하세요. 두 저장소가 하나의 로컬 트랜잭션에 참여한다면 실제 설정으로 이를 증명해야 합니다. 같은 트랜잭션을 쓸 수 없다면 outbox 같은 영속 전달 경계를 두고 재시도와 중복 전달을 고려합니다.

감사 식별자는 데이터베이스를 바꿀 때 달라질 구현 세부 값이 아니라 안정적인 도메인 식별자여야 합니다. 민감한 필드는 snapshot에서 제외하거나, 저장 전에 JaVers 저장소가 지원하는 가림·매핑 정책을 적용하세요.

## 확인할 테스트

1. 상태 변경에 성공하면 현재 row와 감사 commit이 함께 기대한 모습으로 남습니다.
2. 비즈니스 트랜잭션이 거부되면 상태 변경도, 오해를 부르는 감사 기록도 보이지 않습니다.
3. 부분 실패 뒤 재시도는 멱등하거나, 두 번째 commit이 생기는 이유가 명확합니다.
4. 정책에서 요구하는 author, 상관관계 식별자, 변경 이유, 시간이 기록됩니다.
5. 스키마가 바뀐 뒤에도 이전 snapshot을 읽을 수 있거나 문서화된 전환 경로가 있습니다.

## 학습 경로

관계형 영속성 경계는 이 Exposed 매뉴얼을 기준으로 삼으세요. 감사 저장소 설정, commit 메타데이터, 객체 매핑, diff 조회, 이력 예제는 [`bluetape4k-javers`](https://github.com/bluetape4k/bluetape4k-javers)에서 이어집니다. 여러 라이브러리를 조합한 큰 예제가 필요하면 이곳에 문서를 복제하지 말고 [`bluetape4k-workshop`](https://github.com/bluetape4k/bluetape4k-workshop)의 영속성·감사 단계를 연결하세요.

## 근거 자료

- [Spring Modulith 연동](../../../../spring-boot/spring-modulith/src/main/kotlin/io/bluetape4k/spring/modulith/exposed/ExposedEventPublicationRepository.kt)
- [Bluetape4k JaVers 저장소](https://github.com/bluetape4k/bluetape4k-javers)
- [JaVers 문서](https://javers.org/documentation/)
