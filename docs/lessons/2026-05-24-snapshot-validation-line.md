# Snapshot Validation Line

## 배경

이전 release 뒤 matching upstream bluetape4k snapshot을 소비하면서 다음 development
line에서 repository를 다시 열어 snapshot validation이 필요했습니다.

## 결정

`baseVersion=1.9.2`로 설정하고 `snapshotVersion=`은 비워 두며
`bluetape4k-bom:1.9.2-SNAPSHOT`을 소비합니다.

## 결과

repository는 snapshot suffix를 `gradle.properties`에 check-in하지 않고
`publish-snapshot.yml`로 `1.9.2-SNAPSHOT`을 publication할 수 있습니다.

## 검증

snapshot validation train에서 보류 중입니다.
