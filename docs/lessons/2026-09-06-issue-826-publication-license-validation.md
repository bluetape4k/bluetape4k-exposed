# #826 POM 라이선스는 전체 publication 생성 결과로 검증한다

## 배경과 잘못된 가정

#815 fixture를 포함한 개발 버전 발행 준비 중 `develop@9f86e603`에서
LICENSE는 MIT지만 45개 POM은 Apache-2.0을 선언하는 불일치를 발견했다.
기존 `Publication::PomAudit`는 의존성 버전만 검사했다. 검사 성공을 라이선스 일치의
근거로 사용할 수 없었다. 수정 범위는 [#826](https://github.com/bluetape4k/bluetape4k-exposed/issues/826)으로 분리했다.

## 재현과 수정

TDD에서 누락·Apache·잘못된 URL·복수 선언 4종이 기존 검사기를 통과함을 확인했다.
루트 POM에 MIT 이름과 canonical URL이 정확히 하나씩 선언되도록 검사한다.
단위 테스트는 Maven 기본 XML namespace와 기존 의존성 검사도 유지한다.

공통 Gradle 설정만 바꾸면 모든 publication이 바뀐다는 첫 구현의 가정도 틀렸다.
전체 검사와 독립 리뷰에서 `exposed/bom/build.gradle.kts:42`의 별도 선언이 남아
P1으로 보고되었다. BOM 설정까지 수정하고 전체 검증을 다시 실행했다.

## 결과와 재발 방지

- `publication_pom_audit_test.rb`: RED 4건 → GREEN 10개 테스트, 30개 assertion.
- `validate_poms.rb`: 45개 파일, 13,138개 의존성, 45개 Maven 모델, 실패 0건.
- 독립 재리뷰: 기존 P1 해소, P0=0/P1=0.
- CI·개발 버전 발행·정식 발행 workflow 모두 동일한 단위 테스트와 POM 검사를 실행한다.

다음 메타데이터 수정에서는 먼저 모든 `licenses` 설정을 검색하고 BOM을 포함한
publication inventory 전체를 재생성한다. 설정 파일 한 곳이나 단위 테스트만 확인하고
전체 산출물이 수정되었다고 결론 내리지 않는다.

이 변경은 저장소 LICENSE나 이미 발행한 artifact를 수정하지 않는다.
수정 후 head가 바뀌므로 이전 head의 발행 승인을 재사용하지 않는다.
일반 CI 성공과 Full Nightly·실제 발행 완료도 구분한다.
