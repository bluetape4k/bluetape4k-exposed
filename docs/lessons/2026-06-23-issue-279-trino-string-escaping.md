# Lessons Learned - Trino String Literal Escaping (2026-06-23)

Issue: #279
Module: `:bluetape4k-exposed-trino`

## L1: Do not hand-build quoted SQL literals

### Problem

Trino function overrides manually emitted `'`, appended caller-provided strings, then emitted the closing `'`. This looked safe for simple separators and substrings, but quote-containing values broke the literal boundary.

### Lesson

When a dialect override needs a string literal, route the value through Exposed literal rendering such as `stringLiteral` or an equivalent column-type argument path. Tests should include both a quote and control-like text (`)`, `--`, `;`) so regressions cannot hide behind ordinary happy-path values.

