# Issue #227 OLAP Local Testability Research

Date: 2026-06-06
Scope: `bluetape4k-exposed` backlog research for additional OLAP/data warehouse targets.

## Decision

`bluetape4k-exposed` should open follow-up implementation work only for candidates
that have a narrow, locally testable contract.

Accepted follow-up issues:

- #255 — StarRocks local-first Exposed module.
- #256 — Apache Druid query-only JDBC experiment.

Deferred candidates:

- Apache Pinot: keep as research-only for now. The JDBC client is query-only and
  explicitly does not support `INSERT`, `DELETE`, or `UPDATE`; the docs also warn
  that the driver is not fully ANSI SQL 92 compliant. This is too constrained for
  a first implementation issue unless a later spike proves metadata behavior.
- Amazon Redshift: SaaS/credential-gated. AWS recommends Redshift-specific
  drivers going forward and documents Redshift-specific JDBC behavior such as
  fetch-size guidance and lack of `maxRows` support.
- Snowflake: strong JDBC support, but no local emulator/Testcontainers lane was
  found. Keep it out of implementation scope until credentials and external test
  policy are approved.
- Databricks: JDBC requires a Databricks workspace plus cluster or SQL warehouse.
  Databricks Connect also validates against remote cluster/serverless compute, so
  it is not a local Exposed module proof.

## Candidate Matrix

| Candidate | JDBC/driver | SQL dialect fit | DDL/DML fit | Metadata | Local test strategy | CI feasibility | Credentials | Expected Exposed surface | Decision |
|---|---|---|---|---|---|---|---|---|---|
| StarRocks | Native `com.starrocks:starrocks-connector-j` driver; URL `jdbc:starrocks://...` | MySQL-like client access, but StarRocks-specific SQL must be verified | Possible, but start narrow | Official docs state `DatabaseMetaData` support | `starrocks/allin1-ubuntu` Docker quickstart on FE query port `9030` | Feasible as serial Testcontainers/nightly job after launcher proof | No default credentials beyond local root/admin setup | Connection, dialect registration, metadata smoke, query execution; DDL only after proof | Open #255 |
| Apache Druid | Apache Calcite Avatica JDBC | Druid SQL query surface only | Not suitable for broad DDL/DML/repository parity | JDBC metadata and `INFORMATION_SCHEMA` documented | Local quickstart or container with fixture datasource; Router endpoint `:8888/druid/v2/sql/avatica/` | Feasible as serial query-only smoke if container recipe is stable | None for unsecured local quickstart | Query execution and metadata discovery only | Open #256 |
| Apache Pinot | `org.apache.pinot:pinot-jdbc-client` | Query-only OLAP SQL with compliance caveats | Not suitable: no `INSERT`, `DELETE`, or `UPDATE` through JDBC | Must inspect `ConnectionMetadata` because capabilities differ | Possible local cluster, but metadata/dialect proof remains high-risk | Research spike first; implementation deferred | None for local cluster; auth possible | Query-only candidate, no implementation issue yet | Defer |
| Amazon Redshift | Redshift-specific JDBC recommended | PostgreSQL-derived, but Redshift-specific behavior matters | SaaS engine; no local-compatible Redshift proof | Driver dependent | No local proof found | Credential-gated only | AWS credentials and cluster | Research-only unless external test lane approved | Defer |
| Snowflake | JDBC type 4 driver with core JDBC API support | Strong warehouse SQL, but Snowflake-specific behavior | SaaS engine; no local emulator proof | `getMetaData()` and Snowflake extension APIs documented | No local proof found | Credential-gated only | Snowflake account/warehouse | Research-only unless external test lane approved | Defer |
| Databricks | Current JDBC driver path goes through Databricks workspace compute | Spark SQL/lakehouse, not local DB engine | Remote compute contract | Driver/compute dependent | No local JDBC proof; Databricks Connect also targets remote cluster/serverless compute | Credential-gated only | Databricks workspace, token, cluster or SQL warehouse | Research-only unless external test lane approved | Defer |

## Narrow Contracts

### StarRocks

Start with a real StarRocks smoke path, not MySQL parity claims:

- connect through the StarRocks JDBC driver;
- create or load a minimal fixture table only if the container recipe is stable;
- prove `DatabaseMetaData` catalog/schema/table/column discovery;
- execute a basic `SELECT` and one dialect-sensitive query;
- document Docker memory and port requirements;
- keep Testcontainers verification serial.

### Druid

Start query-only:

- connect through Avatica JDBC with `transparent_reconnection=true`;
- use a local fixture datasource;
- query metadata through `DatabaseMetaData` or `INFORMATION_SCHEMA`;
- execute `SELECT` queries only;
- explicitly exclude DDL, DML, DAO/repository, and migration behavior.

### Pinot

Do not open implementation yet. A future spike must first prove:

- stable local cluster startup;
- JDBC metadata behavior for the expected Exposed surface;
- generated SQL compatibility for pagination, aggregation, and prepared
  statements;
- a clear public statement that JDBC is query-only.

## Source Evidence

- Apache Druid SQL JDBC driver API: https://druid.apache.org/docs/latest/api-reference/sql-jdbc/
- Apache Pinot JDBC docs: https://docs.pinot.apache.org/build-with-pinot/connectors-clients-apis/client-libraries/jdbc
- StarRocks JDBC driver docs: https://docs.starrocks.io/docs/integrations/JDBC_driver/
- StarRocks Docker quickstart: https://docs.starrocks.io/docs/quick_start/shared-nothing/
- Amazon Redshift PostgreSQL JDBC/ODBC guidance: https://docs.aws.amazon.com/redshift/latest/dg/c_redshift-postgres-jdbc.html
- Snowflake JDBC API support: https://docs.snowflake.com/en/developer-guide/jdbc/jdbc-api
- Databricks JDBC driver docs: https://docs.databricks.com/aws/en/integrations/jdbc/
- Databricks Connect compute configuration: https://docs.databricks.com/aws/en/dev-tools/databricks-connect/cluster-config
- Existing wiki note: `bluetape4k-wiki/research/2026-05-27-exposed-cockroach-olap-bigquery.md`

## Issue #227 Closure Checklist

- [x] Candidate matrix covers JDBC driver, SQL dialect fit, DDL support, metadata
      support, local test strategy, CI feasibility, credentials, and expected
      Exposed surface.
- [x] No Snowflake implementation issue is opened.
- [x] No Databricks implementation issue is opened.
- [x] Local-first implementation candidates are proposed: #255 and #256.
- [x] README changes are not needed because no candidate is accepted as a
      user-facing module yet.
