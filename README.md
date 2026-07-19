# Package Name Guardian — Typosquat Detection Service

[![SilicaProxy Logo](https://raw.githubusercontent.com/Silica-Proxy/Silica-Proxy/main/logo.png)](https://github.com/Silica-Proxy/Silica-Proxy)

Package Name Guardian is a **standalone typosquat-detection service** for npm, PyPI, and Maven packages. It flags package names that are suspiciously close to one of the most popular packages in their ecosystem — the classic `lodahs` vs `lodash`, `crossenv` vs `cross-env` pattern — using a majority vote across three string-similarity algorithms.

It's designed to be called synchronously by [SilicaProxy](https://github.com/Silica-Proxy/Silica-Proxy) (or any other tool) as an **External Validation Service**, but has no dependency on it and can be run and queried standalone.

---

## Why packagenameguardian?

| Problem | How packagenameguardian helps |
|---|---|
| Typosquatting (`lodahs`, `expres`, `reqeusts`) | Flags names within a length-scaled edit distance of a known-popular package |
| False positives on legitimate sibling packages | Same-namespace exemption: a candidate sharing a real Maven `groupId` / npm `@scope` with an already-popular package is never flagged |

---

## How it works

### 1. Reference data: "top 10,000 packages per ecosystem"

Every request is checked against a list of the most popular npm, PyPI, and Maven packages, ranked by distinct dependent-package count (sourced from Google's public [deps.dev](https://deps.dev) BigQuery dataset).

That data lives entirely in the `reference_package` Postgres table, seeded by a Flyway **repeatable migration** committed in the repo: `src/main/resources/db/migration/R__reference_data_seed.sql`. Flyway re-applies it automatically whenever its content (checksum) changes — no version bump needed. The running application **never talks to BigQuery and has no BigQuery dependency on its classpath at all**: at startup it simply loads every row of `reference_package` into an in-memory snapshot (see [Architecture](#architecture)).

The seed file is refreshed monthly by a scheduled GitHub Actions workflow (`.github/workflows/refresh-reference-data.yml`) that runs a live BigQuery query and opens a **pull request** with the regenerated file, rather than publishing it anywhere unreviewed: an update to "what counts as a popular package" goes through the same code review as any other change, so a compromised hosting/CDN can't silently poison what this service treats as trustworthy.

All BigQuery-touching code lives in a separate Gradle subproject, `refdata-tool/` — its own `build.gradle`, its own `com.google.cloud:google-cloud-bigquery` dependency, never referenced by the main app's build, so those dependencies never end up in `packagenameguardian.jar`. Maintainers (or the CI workflow above) regenerate the seed file via:

```bash
./gradlew :refdata-tool:generateReferenceDataSql
```

By default this authenticates via Application Default Credentials — Workload Identity Federation in CI (`.github/workflows/refresh-reference-data.yml` uses `google-github-actions/auth`, no static key ever stored in GitHub Secrets), or a maintainer's own `gcloud auth application-default login` locally. Pass `-PcredentialsPath=/path/to/bigquery-sa.json` instead to authenticate with a downloaded service-account key file.

This overwrites `src/main/resources/db/migration/R__reference_data_seed.sql` in place with a fresh `DELETE FROM reference_package;` followed by batched `INSERT` statements — review the diff like any other code change, merge it, and Flyway re-applies it on the next deploy/restart.

### 2. Similarity algorithm

A candidate popular-package name is **flagged** iff:
- **Levenshtein distance** (length-scaled threshold, `clamp(2, floor(len×0.20), 4)`) crosses its threshold, **AND**
- **at least one** of Jaro-Winkler (fixed threshold `0.92`) or FuzzyScore (normalized, threshold `0.60`) also crosses its threshold.

#### Levenshtein distance — `clamp(2, floor(len×0.20), 4)`

Minimum number of single-character edits (insert/delete/substitute) to turn one name into the other. The **threshold** (not the distance itself) scales with the candidate's own length, since a fixed distance is wrong at both ends of the range:

| Candidate length | `floor(len×0.20)` | Threshold applied |
|---|---|---|
| 3–9 | 0–1 | **2** (floor) |
| 10 | 2 | 2 |
| 15 | 3 | 3 |
| 20 | 4 | 4 |
| 25+ | 5+ | **4** (ceiling) |

- **Floor of 2**: without it, a 5-char name would get a threshold of 1 — too strict, a single-letter difference on a short name is common between two genuinely unrelated packages.
- **Ceiling of 4**: without it, a 30-char name would get a threshold of 6 — too loose, at that point names no longer meaningfully resemble each other.

Computation is bounded (`new LevenshteinDistance(4)`): it abandons early and returns `-1` once the running distance exceeds 4, so candidates that are obviously too far never pay for the full edit-distance computation.

#### Jaro-Winkler — fixed threshold `0.92`

A 0–1 similarity based on matching characters within a proximity window and transpositions, **plus a bonus for a shared prefix** (up to 4 characters) — the Winkler modification, which is what makes it prefix-sensitive. `0.92` sits at the low end of the 0.90–0.95 "high confidence" band commonly cited in record-linkage literature; the high end of that range is used deliberately to keep false positives down, since a `BLOCKED` verdict here fails closed on a real install.

#### FuzzyScore (normalized) — threshold `0.60`

Apache Commons Text's `FuzzyScore` comes from fuzzy-search/autocomplete ranking: it checks that the input's characters appear **in order** within the candidate, with a bonus for consecutive matches or matches right after a separator. The raw score isn't bounded to `[0, 1]`, so it's normalized here as `fuzzyScore(candidate, input) / fuzzyScore(candidate, candidate)`. `0.60` is lower than Jaro-Winkler's `0.92` because the two metrics don't live on the same scale: FuzzyScore drops sharply as soon as characters are transposed or interleaved (see `reqeusts`/`requests` below), so a Jaro-Winkler-sized threshold would be far too strict for it.

#### Why Levenshtein is mandatory, in measured numbers

Real values from `commons-text` 1.14.0 (the exact classes and version this service ships) — not "any 2 of 3", specifically:

| Pair | Levenshtein (threshold) | Jaro-Winkler (≥0.92?) | FuzzyScore norm. (≥0.60?) | Flagged |
|---|---|---|---|---|
| `lodahs` / `lodash` | 2 (≤2) | 0.9667 ✅ | 0.6875 ✅ | ✅ |
| `reqeusts` / `requests` | 2 (≤2) | 0.9708 ✅ | 0.3636 ❌ | ✅ (via JW) |
| `spring-context` / `spring-aop` | 6 (>2) ❌ | 0.8743 ❌ | 0.6786 ✅ | ❌ |
| `commons-text` / `commons-io` | 4 (>2) ❌ | 0.8933 ❌ | 0.7857 ✅ | ❌ |

`reqeusts`/`requests` shows the **OR**: FuzzyScore misses (the transposed `e`/`q` breaks character order), but Jaro-Winkler still crosses `0.92`, so the typosquat is still caught. `spring-context`/`spring-aop` and `commons-text`/`commons-io` show why Levenshtein is **mandatory**: their shared prefix (`spring-`, `commons-`) pushes FuzzyScore *above* its own `0.60` threshold on its own — if the OR ran unguarded, these legitimate sibling packages would be blocked. Their Levenshtein distance (6 and 4) is well past their threshold (2), so `flagged=false` regardless of what the other two legs say. Levenshtein is a whole-name edit-distance measure, not prefix-biased, so requiring it filters out exactly this false-positive shape without weakening detection of real typosquats (which resemble the *entire* name, not just the start).

The request itself is `BLOCKED` iff **any** candidate in the ecosystem's reference set is flagged; there's no allowlist semantics — a name that resembles nothing popular is simply `ALLOWED`.

Candidates are pre-filtered by length before running any algorithm (Levenshtein distance is always ≥ `|len(a) − len(b)|`, so this is a safe prune, not an approximation), keeping a scan against 10,000 real candidates in the low milliseconds.

### 3. Same-namespace exemption

A candidate whose **Maven `groupId`** or **npm `@scope`** already has at least one popular package in the reference set is never flagged — registries enforce namespace ownership (Maven Central, npm organizations), so publishing under an existing namespace means the same real publisher, not a typosquatter. Configurable independently per ecosystem:

- `packagenameguardian.similarity.maven-same-group-id-exemption-enabled` (default `true`)
- `packagenameguardian.similarity.npm-same-scope-exemption-enabled` (default `true`)

PyPI has no namespace concept and has no equivalent setting.

### Known limitation

Some real, legitimately different packages are as edit-distance-close to each other as a genuine typosquat, with no namespace signal to fall back on (`react-dom`/`react-dnd`, `eslint-plugin-import`/`eslint-plugin-import-x`, `djangorestframework`/`django-rest-framework` — each differs by only 1–2 characters). No threshold can separate these from a real attack without also missing real typosquats at the same distance. This is validated against the real bundled dataset in `RealDataSimilarityScannerTest` and documented there rather than silently tuned away.

---

## Architecture

### Package structure

```
com.silicaproxy.packagenameguardian          (main app -- no BigQuery dependency)
├── controller/            REST endpoints (check, monitoring)
├── service/
│   ├── check/              PackageCheckService — orchestrates the verdict
│   ├── similarity/          SimilarityScanner, PackageNameNormalizer, PackageNamespaceExtractor
│   ├── sync/                ReferenceDataCache, ReferenceSnapshot, startup loader
│   └── monitoring/          Health checks
├── dao/
│   └── repository/           SQL — reference_package, health/Flyway-history introspection
├── config/                 Metrics, health indicators
├── model/
│   ├── dto/                  Check request/response
│   └── entity/                ReferencePackage
└── properties/              PackageNameGuardianProperties (typed config)

refdata-tool/                                (separate Gradle subproject -- BigQuery lives only here)
└── com.silicaproxy.packagenameguardian.refdatatool
    ├── DepsDevBigQueryClient        2-job partition-pruned BigQuery query
    ├── BigQueryServiceFactory / ApplicationDefaultBigQueryServiceFactory (default) /
    │       CredentialsFileBigQueryServiceFactory (key-file fallback)
    └── ReferenceDataSqlGenerator    maintainer-only CLI -> R__reference_data_seed.sql
```

### BigQuery cost

Querying `bigquery-public-data.deps_dev_v1.DependentsLatest` (a view filtering on `SnapshotAt = (SELECT MAX(Time) FROM Snapshots)`, a dynamic subquery) scans close to its entire 169-day, ~79 TB history — BigQuery's planner can't prune partitions against a value it doesn't know until execution. `DepsDevBigQueryClient` (in `refdata-tool/`) instead resolves the latest snapshot timestamp first, then binds it as a genuine query parameter into a query against `Dependents` (the base table, DAY-partitioned on `SnapshotAt`, clustered on `System`/`Name`/`Version`) — letting BigQuery prune to a single day's partition. Measured cost: **~417 GB per run** (vs ~49 TB unoptimized), comfortably under the 1 TB `maximumBytesBilled` safety cap.

---

## API

### `POST /v1/check`

```
Request:  { "packageName": "lodahs", "version": "1.0.0", "ecosystem": "npm" }
Response: { "verdict": "ALLOWED" | "BLOCKED", "reason": "<string, present if BLOCKED>" }
```

- `ecosystem` is lowercase (`npm`/`pypi`/`maven`).
- Maven `packageName` arrives as `groupId:artifactId`.
- `version` is accepted but ignored — this service does name-similarity checks only.
- Protected by `Authorization: Bearer {key}` when `packagenameguardian.security.enabled=true` (the default) — see [Configuration](#configuration).

### `GET /api/monitoring/health`

Also exposed as standard `HealthIndicator` beans under `GET /actuator/health` (`database`, `referenceDataFreshness` components). `referenceDataFreshness` is `DOWN` if `reference_package` is empty or any ecosystem has fewer rows than `packagenameguardian.reference-data.min-acceptable-count-per-ecosystem`; `DEGRADED` if no Flyway history is found for the seed migration, or it was last applied more than `freshness-max-age-hours` ago; `UP` otherwise.

```json
{
  "status": "UP",
  "components": {
    "database": { "status": "UP", "details": { "message": "Database connection OK" } },
    "referenceDataFreshness": { "status": "UP", "details": { "packageCounts": {"NPM": 10000, "PYPI": 10000, "MAVEN": 10000}, "lastSeedAppliedAt": "...", "hoursSinceLastSeedApplied": 0 } }
  }
}
```

---

## Deployment

### Development

**Prerequisites:** Java 25+, Docker

```bash
docker compose up -d          # Postgres only
./gradlew bootRun             # listens on port 8100, connects to localhost:5433
```

No GCP setup needed — the app has no BigQuery dependency at all. Flyway seeds `reference_package` from the committed `R__reference_data_seed.sql` on first startup.

```bash
curl -X POST http://localhost:8100/v1/check \
  -H "Content-Type: application/json" \
  -H "Authorization: Bearer $PACKAGENAMEGUARDIAN_API_KEY" \
  -d '{"packageName":"lodahs","version":"1.0.0","ecosystem":"npm"}'
```

> Set `PACKAGENAMEGUARDIAN_SECURITY_ENABLED=false` to disable the API-key check for local testing (never in production).

### Production — JAR

```bash
./gradlew bootJar
# Output: build/libs/packagenameguardian.jar
```

Minimum required configuration:

```bash
java -jar packagenameguardian.jar \
  --spring.datasource.url=jdbc:postgresql://your-db:5432/packagenameguardian \
  --spring.datasource.username=prod_user \
  --spring.datasource.password=prod_password \
  --packagenameguardian.security.api-key=your-generated-key
```

### Production — Docker

```bash
docker build -t packagenameguardian .
```

The included multi-stage `Dockerfile` builds `bootJar` with the Gradle wrapper, then `jlink`s a minimal JRE (only the modules `jdeps` finds actually in use, plus `jdk.crypto.ec`) into a non-root `alpine` runtime image — no Docker Hub image is published, build locally or in your own CI. All configuration is passed as environment variables:

```bash
docker run -d \
  -p 8100:8100 \
  -e SPRING_DATASOURCE_URL=jdbc:postgresql://your-db:5432/packagenameguardian \
  -e SPRING_DATASOURCE_USERNAME=prod_user \
  -e SPRING_DATASOURCE_PASSWORD=prod_password \
  -e PACKAGENAMEGUARDIAN_API_KEY=your-generated-key \
  packagenameguardian
```

### Refreshing the reference data seed

Regenerating `R__reference_data_seed.sql` from a live BigQuery query is a maintainer-only, out-of-band operation — never something the running app does. Requires a GCP service account with `roles/bigquery.jobUser`.

#### Setup (one-time)

Authenticate locally via Application Default Credentials:

```bash
gcloud auth application-default login
```

This creates a short-lived token that the BigQuery SDK will use automatically — no static keys stored on disk.

#### Dry-run first (recommended)

Always validate the query cost before incurring it:

```bash
./gradlew :refdata-tool:generateReferenceDataSql -PdryRun=true
```

BigQuery will validate the query syntax and report the bytes that would be processed (~417 GB), without executing or billing:

```
DRY RUN: query validated against BigQuery, 417000000000 bytes would be processed 
(nothing executed, nothing billed, R__reference_data_seed.sql not written)
```

#### Run for real

Once validated:

```bash
./gradlew :refdata-tool:generateReferenceDataSql
```

This executes the partition-pruned query (~417 GB scan, ~$2–3 cost) and overwrites `R__reference_data_seed.sql` with the top 10,000 packages per ecosystem, ranked by dependent-package count.

#### Alternative: service-account key file

If Application Default Credentials is not available, pass a key file:

```bash
./gradlew :refdata-tool:generateReferenceDataSql \
  -PcredentialsPath=/path/to/bigquery-sa.json
```

Or in dry-run mode:

```bash
./gradlew :refdata-tool:generateReferenceDataSql \
  -PcredentialsPath=/path/to/bigquery-sa.json \
  -PdryRun=true
```

#### After regeneration

Commit the regenerated file through a normal reviewed PR (the scheduled `refresh-reference-data.yml` workflow does exactly this monthly) so Flyway picks it up on the next deploy.

---

## Configuration

### YAML / CLI properties

| Category | YAML property | Default | Description |
|---|---|---|---|
| **Reference data** | `packagenameguardian.reference-data.min-acceptable-count-per-ecosystem` | `5000` | Health-check floor — fewer rows than this for any ecosystem reports `DOWN` |
| | `packagenameguardian.reference-data.freshness-max-age-hours` | `1512` (63 days) | How stale the seed migration's last apply can get before reporting `DEGRADED` |
| **Security** | `packagenameguardian.security.enabled` | `true` | Require `Authorization: Bearer {api-key}` on `POST /v1/check` |
| | `packagenameguardian.security.api-key` | _(empty)_ | Fails closed if enabled with no key set |
| **Similarity** | `packagenameguardian.similarity.npm-same-scope-exemption-enabled` | `true` | Exempt candidates sharing a known npm `@scope` |
| | `packagenameguardian.similarity.maven-same-group-id-exemption-enabled` | `true` | Exempt candidates sharing a known Maven `groupId` |
| **Database** | `spring.datasource.url` | `jdbc:postgresql://localhost:5433/packagenameguardian` | |
| | `spring.datasource.username` / `password` | `postgres` / `postgres` | |
| **Server** | `server.port` | `8100` | |

### Docker environment variables

Pass these as `-e` flags to `docker run`, or use in a `docker-compose.yml` `environment:` block. Spring Boot relaxed binding applies: dots/hyphens → `_`, uppercase (e.g. `packagenameguardian.reference-data.min-acceptable-count-per-ecosystem` → `PACKAGENAMEGUARDIAN_REFERENCE_DATA_MIN_ACCEPTABLE_COUNT_PER_ECOSYSTEM`).

| Category | Environment variable | Default | Description |
|---|---|---|---|
| **Reference data** | `PACKAGENAMEGUARDIAN_REFERENCE_DATA_MIN_ACCEPTABLE_COUNT_PER_ECOSYSTEM` | `5000` | Health-check floor — fewer rows than this for any ecosystem reports `DOWN` |
| | `PACKAGENAMEGUARDIAN_REFERENCE_DATA_FRESHNESS_MAX_AGE_HOURS` | `1512` (63 days) | How stale the seed migration's last apply can get before reporting `DEGRADED` |
| **Security** | `PACKAGENAMEGUARDIAN_SECURITY_ENABLED` | `true` | Require `Authorization: Bearer {api-key}` on `POST /v1/check` |
| | `PACKAGENAMEGUARDIAN_SECURITY_API_KEY` | _(empty)_ | Fails closed if enabled with no key set |
| **Similarity** | `PACKAGENAMEGUARDIAN_SIMILARITY_NPM_SAME_SCOPE_EXEMPTION_ENABLED` | `true` | Exempt candidates sharing a known npm `@scope` |
| | `PACKAGENAMEGUARDIAN_SIMILARITY_MAVEN_SAME_GROUP_ID_EXEMPTION_ENABLED` | `true` | Exempt candidates sharing a known Maven `groupId` |
| **Database** | `SPRING_DATASOURCE_URL` | `jdbc:postgresql://localhost:5433/packagenameguardian` | |
| | `SPRING_DATASOURCE_USERNAME` / `SPRING_DATASOURCE_PASSWORD` | `postgres` / `postgres` | |
| **Server** | `SERVER_PORT` | `8100` | |

---

## Observability

- **Prometheus metrics** at `/actuator/prometheus`.
- Metric names centralized in `com.silicaproxy.packagenameguardian.config.Metrics`.

| Metric | Type | Tags | Description |
|---|---|---|---|
| `packagenameguardian.check.verdicts` | Counter | `ecosystem`, `verdict` (`ALLOWED`/`BLOCKED`) | Every `/v1/check` verdict |

Reference-data freshness is exposed via the `referenceDataFreshness` health component (see [API](#api)) rather than a separate metric, since it changes only when a new seed migration is deployed, not continuously at runtime.

Debug-level logs (`com.silicaproxy.packagenameguardian`) include per-request algorithm scores (Levenshtein distance, Jaro-Winkler similarity, normalized FuzzyScore) for the winning flagged candidate only — never for every candidate scanned, to keep logs proportional to the prefilter's whole purpose.

---

## Testing

```bash
./gradlew test                                        # unit + Testcontainers integration, no GCP credentials needed
./gradlew :refdata-tool:bigQueryLiveTest               # opt-in, requires real GCP credentials
./gradlew checkstyleMain pmdMain spotbugsMain          # static analysis gate on the main app
./gradlew :refdata-tool:checkstyleMain :refdata-tool:pmdMain :refdata-tool:spotbugsMain   # same gate on refdata-tool
```

`RealDataSimilarityScannerTest` validates the similarity algorithm and the same-namespace exemption against a real top-10,000-per-ecosystem snapshot fixture, not just a handful of curated names.

---

## Disclaimer

packagenameguardian is provided "as is", without warranty of any kind, express or implied, including but not limited to the warranties of merchantability, fitness for a particular purpose, and non-infringement. Name-similarity detection is inherently probabilistic: validate the default thresholds against your own risk tolerance before relying on this service in a purely automated blocking mode.

## License

[Apache License 2.0](LICENSE)
