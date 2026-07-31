# 이슈 281 Spring Modulith 로드할 수 없는 이벤트 교훈

Date: 2026-06-23
Issue: #281

## 교훈

Repository query 경로는 payload type을 로드할 수 없다는 이유만으로 durable 운영 레코드를 숨기면 안 됩니다. event publication에서는 미전달 작업이 운영자 view와 resubmission 로직에서 사라질 수 있으므로, 보이지 않는 문제는 명시적 실패보다 더 나쁩니다.

## 지침

- repository SPI가 lazy event access를 허용하면 payload deserialization과 독립적으로 publication 행을 materialize합니다.
- 운영자가 정확한 행을 복구하거나 삭제할 수 있도록 diagnostic exception에 row identifier와 listener id를 보존합니다.
- class loading 실패와 linkage 실패는 행을 filter할 사유가 아니라 전달 불가 diagnostic으로 다룹니다.
- 회귀 테스트는 의도적으로 누락된 event type을 가진 행을 삽입하고, 명시적 실패 경로를 검사하기 전에 query 가시성을 확인해야 합니다.
- 문서는 운영자에게 classpath 호환성을 복구하거나, 저장된 event 행을 migrate하거나, 수정 후 명시적으로 delete/resubmit하도록 안내해야 합니다.

## 후속 조치

Spring Modulith가 first-class 전달 불가 publication 표현을 추가하면 lazy event-access exception을 사용하는 대신 그 API로 알 수 없는 event type을 노출하도록 repository를 맞춥니다.
