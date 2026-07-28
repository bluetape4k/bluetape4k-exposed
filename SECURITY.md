# 보안 정책

## 지원 버전

보안 업데이트는 다음 버전에 제공합니다:

| Version | Supported          |
|---------|--------------------|
| 1.8.x   | Yes                |
| < 1.8   | No                 |

## 취약점 보고

이 project에서 보안 취약점을 발견했다면 public GitHub issue를 **열지 마십시오**.

대신 다음 channel 중 하나로 보고해 주십시오:

1. **GitHub Security Advisories**: 이 repository의 Security tab에서 [Report a vulnerability](../../security/advisories/new) button을 사용하십시오.
2. **Email**: 제목을 `[SECURITY] bluetape4k-exposed vulnerability report`로 지정해 세부 내용을 [sunghyouk.bae@gmail.com](mailto:sunghyouk.bae@gmail.com)으로 보내십시오.

보고에는 다음 내용을 포함해 주십시오:

- 취약점 설명과 잠재 영향
- 재현 절차
- 영향받는 버전
- 알고 있는 경우 제안하는 수정 또는 완화 방안

### 응답 기준

- **접수 확인**: 보고 수신 후 영업일 기준 3일 이내
- **상태 업데이트**: 초기 평가와 함께 영업일 기준 7일 이내
- **해결 일정**: critical issue는 30일 이내 fix release를 목표로 합니다

우리는 responsible disclosure 관행을 따릅니다. fix가 release되면 익명 요청이 없는 한 release notes에서 reporter를 언급합니다.

## 사용자를 위한 보안 고려사항

- 항상 최신 patch version을 사용하십시오.
- source control에 commit되는 application properties에 database credential을 노출하지 마십시오. environment variable 또는 secrets manager를 사용하십시오.
- encrypted column(`exposed-tink`)은 적절한 Tink keyset management가 필요합니다. keyset을 주기적으로 rotate하십시오.
- Redis-backed cache module은 network를 통해 data를 전송합니다. production에서는 Redis connection이 TLS를 사용하도록 보장하십시오.
