# #826 POM MIT 라이선스 수정 리뷰

## 범위와 기준

`fix/pom-license-mit`의 `develop@9f86e603` 대비 변경을 검토했다.
대상은 root/BOM publication 설정, Ruby 검사기·단위 테스트, CI·발행 workflow의
단위 테스트 호출, 변경 로그·계획·교훈이다. 기준은 #826과 저장소 LICENSE의 MIT 선언이다.
공개 Kotlin API·런타임 동작·버전·의존성은 변경하지 않는다.

## 독립 리뷰와 처리

native `code-reviewer`의 첫 검토는 `exposed/bom/build.gradle.kts:42-43`의
Apache 선언 잔존을 P1으로 지적하고 REQUEST CHANGES를 반환했다.
실제 전체 POM 검사도 같은 BOM 파일에서 실패했다.
해당 선언을 MIT로 수정한 재검토에서는 BOM audit `errors=[]`와
10개 단위 테스트 통과를 확인하고 APPROVE를 반환했다. 최종 P0=0/P1=0이다.
리뷰를 inline 검토나 외부 모델 검증으로 바꾸어 표기하지 않는다.

## 검증 근거

- Ruby POM audit: 10개 테스트, 30개 assertion, 실패·오류·누락 0건.
- Ruby metadata audit: 15개 테스트, 27개 assertion, 실패·오류·누락 0건.
- `generatePomFileForBluetapeExposedPublication exportPublicationInventory`: 성공.
- `validate_poms.rb`: failures=0 files=45 dependencies=13138 maven_models=45.
- Ruby syntax, 세 workflow의 actionlint, `git diff --check`: 통과.
- 라이프사이클·취소·DB 검증: N/A. production Kotlin 파일을 변경하지 않았다.
- LSP 진단 도구는 사용할 수 없어 Ruby syntax와 실제 Gradle/POM 검증으로 확인했다.
- CI·Full Nightly·머지·발행 완료는 이 로컬 검증으로 증명하지 않는다.

## 문서 작성 검증

계획·교훈·본 리뷰는 한국어 유지보수 문서다. 각 문서에 SPW-01~SPW-05를 적용했다.
원본은 LICENSE, 두 Gradle 설정, Ruby 소스·검증 출력, #826, 독립 리뷰 결과다.
계획은 승인·실행·복구, 교훈은 실패한 가정·수정·재발 방지, 리뷰는 위치·심각도·처리·검증 한계를 기록했다.
명령·식별자·숫자를 원본과 대조하고, 문장·표제·링크를 다시 읽었다.
KO-01~KO-06은 의미 보존·구체적 서술·용어 일치 기준으로 확인했다.
KO-07 용어 검사는 세 신규 문서에서 지적 0건을 확인했다.
CHANGELOG의 기존 2건은 이번 수정 범위 밖이며, 새 항목에는 지적이 없다.
미확인 CI를 통과로 표기하지 않는다.
