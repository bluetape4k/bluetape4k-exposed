# Release Catalog Guard

## 배경

AWS 0.3.0 release는 shared release workflow risk를 드러냈습니다. 오래된 GitHub
repository variable이 Gradle이 build script를 compile하기 전에 check-in된
`settings.gradle.kts` catalog default를 override할 수 있습니다.

## 결정

stable tag release는 check-in된 catalog default를 사용합니다. manual dispatch는
explicit `catalogRef` override를 사용할 수 있고 그 뒤 repository variable을
operational fallback으로 사용합니다.

## 결과

release workflow는 선택한 catalog source를 log로 남기고 Maven Central publish 전에
required catalog alias를 확인합니다.

## 검증

`actionlint`를 실행하고 local에서 catalog selection branch를 검증하며 current release
catalog에 required alias가 있는지 확인합니다.

## 향후 지침

repository catalog variable은 release train source of truth가 아닌 manual release
override로 취급합니다.
