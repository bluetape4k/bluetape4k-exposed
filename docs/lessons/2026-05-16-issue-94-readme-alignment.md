# README.md / README.ko.md 구조 정렬

**Date**: 2026-05-16
**Issue**: #94
**Type**: Maintenance (docs)

## 요약

1.8.0 pre-release 검토 중 발견한 README.md와 README.ko.md의 구조 차이를
정렬했습니다.

## 변경 사항

1. 두 파일의 오래된 `JetBrains Exposed 0.60+` requirement를 `1.2+`로
   업데이트했습니다. standalone repository 생성 뒤 project는 Exposed 1.x를
   사용해 왔습니다.
2. README.md와 맞추기 위해 README.ko.md JDBC example에 누락된 `deleteById`
   method를 추가했습니다.

## 향후 지침

- README.md에 code example을 추가할 때는 같은 PR에서 README.ko.md에 해당 example도
  함께 추가합니다.
- Exposed를 upgrade할 때 Requirements section의 `JetBrains Exposed` version을
  확인합니다.
