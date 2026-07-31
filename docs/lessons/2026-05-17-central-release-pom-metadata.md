# Central Release POM Metadata

## 배경

생성된 Maven POM이 imported BOM으로 관리되는 dependency의 version metadata를
누락해 1.8.0 Central Portal release validation이 실패했습니다.

## 결정

생성 POM에 dependency management entry가 포함되도록 release POM의 Spring
dependency-management POM customization을 유지합니다.

## 결과

생성된 publication POM에는 이제
`io.github.bluetape4k:bluetape4k-bom:1.8.0`이 포함된 `dependencyManagement`와
`SNAPSHOT` reference 부재가 보장됩니다.

shared version을 central Gradle catalog로 옮긴 뒤 1.12.0 SNAPSHOT line에서
실패가 재발했습니다. publication-facing dependency는 의도적으로 version을 생략한
local alias를 계속 사용했습니다. authoritative `bt4k` catalog는 Exposed 및 Spring
Boot BOM의 versioned alias를 이미 제공했지만 module build file이 이를 사용하지
않았습니다. 따라서 build 자체는 성공해도 Gradle은 version 없는 BOM import를
생성했습니다.

publication-facing BOM declaration은 이제 `bt4k.exposed.bom` 및
`bt4k.spring.boot4.dependencies`를 사용합니다. repository validator는 version 없는
모든 `dependencyManagement` entry와 같은 POM이 explicit dependency management 또는
versioned BOM import를 제공하지 않을 때의 unversioned regular dependency를
거부합니다. CI, SNAPSHOT publication, stable publication은 publication을 계속하기
전에 모두 validator를 실행합니다.

## 검증

- `./gradlew generatePomFileForBluetapeExposedPublication --no-daemon --no-configuration-cache --no-build-cache`
- `ruby scripts/publication/validate_poms.rb`
- 생성된 `pom-default.xml` file에 target release class의 missing dependency version과
  의도하지 않은 `SNAPSHOT` reference가 없는지 확인합니다.

## 향후 지침

성공한 Gradle dependency resolution을 publication proof로 보지 않습니다. Central
publication 전에는 모든 public POM을 생성하고 특히 imported BOM을 포함한 모든
dependency-management entry의 explicit version을 요구합니다. generated POM이 유효한
dependency management를 제공할 때만 regular dependency가 direct version을 생략할 수
있습니다. publication-facing platform을 나타내는 authoritative central catalog alias가
있으면 이를 우선합니다. 잘못된 POM이 gate 전에 repository에 도달하지 않도록 validator를
PR CI와 두 publication workflow 모두에 둡니다.
