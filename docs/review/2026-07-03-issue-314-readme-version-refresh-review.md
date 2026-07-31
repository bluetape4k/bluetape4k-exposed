# 이슈 #314 README 버전 갱신 리뷰

## 범위

- 이슈: #314 `docs: refresh README dependency versions before 1.12.0`
- 리뷰한 파일:
  - `README.md`
  - `README.ko.md`
  - `spring-boot/spring-modulith/README.md`
  - `spring-boot/spring-modulith/README.ko.md`
  - `exposed/postgresql/README.md`
  - `exposed/postgresql/README.ko.md`

## 검토 결과

- P0/P1: 없음.
- 가장 최근에 게시된 GitHub 릴리스는 2026-06-27에 게시된 `1.11.0`이다.
- 루트 README의 의존성 예시는 이미 `1.11.0`을 일관되게 사용하며
  `1.12.0`을 안내하지 않는다.
- `spring-boot/spring-modulith`는 여전히 `1.10.0`을 사용하고 있었다.
  이제 두 로케일 파일 모두 가장 최근에 게시된 안정 버전 `1.11.0`을 사용한다.
- `exposed/postgresql`은 여전히 `1.9.2`를 사용하고 있었다. 이제 두 로케일 파일 모두
  향후 패치 버전이 오래된 값으로 남지 않도록 모듈 README의 플레이스홀더 형식
  `${version}`을 사용한다.

## 검증

- `git diff --check`: PASS.
- README 의존성에서 `io.github.bluetape4k.exposed:*:1.9.x`, `1.10.x`,
  `1.12.x`를 검색한 결과: PASS, 일치 항목 없음.
- README 의존성에서 명시적인 `1.11.0`을 검색한 결과: PASS, 루트 README와
  Spring Modulith README 쌍에만 존재한다.

## 잔여 위험

- 문서만 변경했다. 프로덕션 소스, 테스트 소스, 빌드 로직은 변경하지 않았다.
