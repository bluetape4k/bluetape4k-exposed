# Issue #934 README 공개 타입 modifier parity

## 배경

README 문서 계약 parser는 Kotlin 공개 top-level 타입을 찾지만, `open`과
`abstract`를 modifier 목록에서 빠뜨리고 있었다. 따라서 README에
`open class` 또는 `abstract class`를 추가하면 공개 타입이 문서 계약 검사에서
누락될 수 있었다.

## 결정

Python parser의 modifier grammar를 기존 Kotlin parity checker와 맞춰
`open`과 `abstract`를 허용하고, 동일한 회귀 fixture에 두 공개 타입을 추가한다.
parser를 새로 작성하거나 공개 API 판정 범위를 넓히지는 않는다.

## 결과와 검증

- RED 단계에서 9개 테스트 중 1개가 `PublicOpen`과 `PublicAbstract` 누락으로
  실패했다.
- 최소 regex 변경 후 `python3 scripts/test_readme_documentation_contract.py -v`가
  9개 테스트 모두 통과했다.

## 향후 보호 기준

Kotlin 공개 modifier를 parity parser에 추가하거나 변경할 때는 기존 checker의
modifier 목록과 Python parser를 함께 비교하고, 각 modifier를 대표하는 공개 타입
fixture를 회귀 테스트에 유지한다.
