# Security Policy

## Supported Versions

Security updates are provided for the following versions:

| Version | Supported          |
|---------|--------------------|
| 1.8.x   | Yes                |
| < 1.8   | No                 |

## Reporting a Vulnerability

If you discover a security vulnerability in this project, please **do not** open a public GitHub issue.

Instead, report it via one of the following channels:

1. **GitHub Security Advisories**: Use the [Report a vulnerability](../../security/advisories/new) button in the Security tab of this repository.
2. **Email**: Send details to [sunghyouk.bae@gmail.com](mailto:sunghyouk.bae@gmail.com) with the subject line `[SECURITY] bluetape4k-exposed vulnerability report`.

Please include the following in your report:

- A description of the vulnerability and its potential impact
- Steps to reproduce the issue
- Affected versions
- Any suggested fix or mitigation, if known

### What to expect

- **Acknowledgement**: Within 3 business days of receiving your report
- **Status update**: Within 7 business days with an initial assessment
- **Resolution timeline**: We aim to release a fix within 30 days for critical issues

We follow responsible disclosure practices. Once a fix is released, we will credit reporters in the release notes (unless anonymity is requested).

## Security Considerations for Users

- Always use the latest patch version
- Do not expose database credentials in application properties committed to source control — use environment variables or a secrets manager
- Encrypted columns (`exposed-tink`) require proper Tink keyset management; rotate keysets periodically
- Redis-backed cache modules transmit data over the network — ensure Redis connections use TLS in production
