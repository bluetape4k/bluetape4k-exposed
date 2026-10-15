# Issue #910 커버리지 CI 리뷰

## 범위와 기준

[`Issue #910`](https://github.com/bluetape4k/bluetape4k-exposed/issues/910)의 완료 조건과 `develop` 기준선 `5bb90aad40bae7a8f7a4b26a0c7ff4cf3663b042`부터 [`PR #911`](https://github.com/bluetape4k/bluetape4k-exposed/pull/911)에 포함된 변경을 검토했다. 코드 검토 범위는 head `34440fd4e4a5bcd2b7433ec8a356b5cfdf0b4455`의 Kover 커버리지 선택 계약과 0바이트 XML 회귀 테스트다. 이 문서는 변경을 작성한 주 세션의 검토 기록이며 독립 검토자의 판정을 대신하지 않는다. 최신 head의 CI와 독립 리뷰 판정은 PR #911의 live 상태 및 해당 실행 기록에서 확인한다.

## 결과

확인된 코드 결함은 없다. [`ci.yml`](../../.github/workflows/ci.yml)의 `coverage-enabled`는 Kover XML을 생성하는 테스트 경로와 `workflow_dispatch`를 반영한다. 선택된 생성 작업이 없으면 커버리지 집계를 건너뛰고 `CI Status`에 `N/A`를 남기며, 변경 감지 실패는 기존 실패 판정에 전달한다. [커버리지 선택 검증기](../../scripts/ci/validate_ci_matrix_contract.py)와 [CI matrix 계약 테스트](../../scripts/ci/validate_ci_matrix_contract_test.py)는 이 분기를 검사한다. [집계기 회귀 테스트](../../.github/scripts/test_aggregate_kover_coverage.py)는 생성 작업이 선택된 경우 보고서가 누락되거나 0바이트이거나 손상되면 집계가 실패하는 계약을 확인한다. 다중 변경 파일에서 제외 패턴을 평가하는 테스트는 [dorny/paths-filter 공식 README](https://github.com/dorny/paths-filter/blob/master/README.md)의 `some-with-excludes` 정의에 근거한다.

7-Tier 분류:

| 영역 | 판정 | 근거 |
|---|---|---|
| 보안 | N/A | 권한, 비밀, 입력 신뢰 경계 변경 없음 |
| 정확성·도메인 | PASS | 설정 전용 경로와 보고서 생성 경로를 분리하고 두 경로를 계약 테스트로 확인 |
| 복원력·수명주기 | N/A | 제품 실행 및 자원 수명주기 변경 없음 |
| 공개 API·ABI | N/A | JVM 공개 선언, Maven artifact 좌표, 의존성 변경 없음 |
| 테스트·호출자 | PASS | 0바이트 XML 거부 회귀와 CI matrix 계약 테스트 추가 |
| 성능 | N/A | 제품 실행 경로 변경 없음 |
| 문서·CI·배포 | PASS | 변경 head의 CI와 설정 전용 경로의 별도 호스티드 CI에서 각각 기대된 producer 선택 및 `N/A` 동작을 확인 |

## 검증과 남은 증거

- `.github/scripts/test_aggregate_kover_coverage.py`: 5개 테스트 통과
- `scripts/ci/validate_ci_matrix_contract_test.py`: 24개 테스트 통과
- `scripts/ci/validate_required_jobs_test.py`: 6개 테스트 통과
- CI matrix validator, `actionlint`, Ruff 검사·포맷 검사, `git diff --check`: 통과
- PR #911의 exact-head 실행 [38696808398](https://github.com/bluetape4k/bluetape4k-exposed/actions/runs/38696808398)은 head `34440fd4e4a5bcd2b7433ec8a356b5cfdf0b4455`에서 40개 job 중 39개 성공, 1개 skip, 0개 실패로 끝났다. 29개 `Test /` job과 `Coverage Report`, `CI Status`가 성공했다. 건너뛴 job은 `Docs-only validation`이다.
- 설정 전용 임시 PR #912의 exact-head 실행 [38705050851](https://github.com/bluetape4k/bluetape4k-exposed/actions/runs/38705050851)은 head `3b57a2db3bd20733c3deb2432ad00c0881fe6d3a`에서 40개 job 중 8개 성공, 32개 skip, 0개 실패로 끝났다. 29개 모듈 `Test /` job과 `Coverage Report`는 건너뛰었고 `CI Status`는 coverage `N/A`를 기록하며 성공했다. 따라서 이 실행은 설정 경로 결과를 검증하지만 제품 테스트 성공을 뜻하지 않는다. 임시 PR은 병합하지 않고 닫았다.
- 위 두 실행은 각각 기록된 SHA의 증거다. 머지 준비 상태는 최신 PR head의 CI와 독립 리뷰 결과를 실시간으로 재확인해 판단한다.
- 사용자가 요청한 모델은 `gpt-5.6-luna max`다. 인라인 및 독립 검토자의 실제 실행 모델은 확인되지 않았다.

인라인 코드 판정: PASS; 코드 P0=0, P1=0. 독립 검토는 lesson과 review의 오래된 CI 주장을 P2로 지적했고, 이 변경에서 바로잡았다. 새 head의 판정은 PR #911의 독립 리뷰 결과에서 확인한다.

### 독립 코드 검토

- 이전 독립 `code-reviewer` 검토는 head `e81c2cdb037b53aa49a79c87816ac4e839075c89`와 `8667239591dbf6186d1b2da022b8ff03f0a883c4`를 대상으로 코드와 CI 계약을 확인했다. 이후 PR #911의 head `34440fd4e4a5bcd2b7433ec8a356b5cfdf0b4455`에 대한 독립 검토는 코드 P0/P1을 찾지 못했지만, lesson과 review에 남은 오래된 CI 상태를 P2로 지적했다. 이 수정에서 두 기록을 갱신했다.
- 독립 검토자가 지적한 P2 기록 불일치는 이 변경에서 바로잡았다. 이 검토와 최신 head의 판정은 PR #911의 live review 결과에서 구분한다.
- 독립 검토자의 실제 실행 모델은 확인되지 않았다. 요청 모델은 `gpt-5.6-luna max`다.
