# LogiCov

Credit memo pipeline: deal package ingestion, the Golden Grid, deterministic spreading, analyst approval, policy overlay, and a cited PDF memo.

## Layout

| Path | What |
|---|---|
| `core/` | Shared models and pure logic (grid, ratios, rating, covenant, stress), built for the JVM (`core`) and Scala.js (`coreJs`) |
| `server/` | Spice server, LightDB on PostgreSQL, EDGAR ingestion, PDF parsing |
| `app/` | EveryUI web client (Scala.js) |
| `deploy/` | Local Postgres, container packaging, verification and GCP infrastructure |
| `poc/samples/` | Input and memo PDFs for the proof of concept (not committed) |

## Commands

```bash
./deploy/dev-postgres.sh                     # local Postgres 17 on 127.0.0.1:25432
sbt server/run                               # API on :8300 (GET /api/health)
sbt app/webBundle                            # web client into app/out
sbt "server/runMain logicov.poc.PdfDumpApp"  # poc/samples/*.pdf -> poc/out/*.txt (text with page and position)
sbt "server/runMain logicov.poc.DealApp <pack> poc/out --fetch --officers"  # source pack -> <ticker>-deal.json (--peers refreshes peer data only; --ratings reads the credit ratings each peer discloses in its 10-K)
sbt "server/runMain logicov.poc.MemoApp poc/out/<ticker>-deal.json poc/out --narratives"  # deal record -> memo HTML and PDF
```

A source pack is a folder per deal: `deal.json` (the analyst's judgments), `call-script.md` (the discovery call), and `sec/`, which `--fetch` fills from EDGAR (latest 10-K and its XBRL, the 10-Q after it, the Q4 earnings releases, and companyfacts for the borrower and its peers; set `EDGAR_USER_AGENT`). `--officers` reads the executive officers from the 10-K, or from the proxy statement when the 10-K incorporates them by reference, into `sec/officers.json`; every name, title, age and background sentence is checked verbatim against the filing. Everything in the deal record is extracted from the pack except the judgments in `deal.json`. Narratives and `--officers` need `ANTHROPIC_API_KEY` and `LOGICOV_NARRATIVE_MODEL` (the provider/model id from the Sigil model registry).

Requires Java 25, sbt 2.0.9, and OUTR snapshots (`~/.ivy2/local` or GitHub Packages with `GITHUB_TOKEN`).

Configuration is Profig under `logicov` (`server/src/main/resources/config.json` holds local defaults only). Secrets come from environment variables or system properties, for example `-Dlogicov.db.password=...`.

## Application and deployment

The application provides provisioned-account login, persistent tenant membership and sessions, and an encrypted deal workspace. Analysts can import and retain statements, correct cited values, complete credit profiles, review policy rules, approve inputs and generate cited memo bundles. Build the frontend and backend together with `./deploy/package.sh`, provision an account with `logicov.auth.ProvisionApp`, and run `target/runtime/bin/logicov-server`. The image includes the frontend and Chromium.

See [deployment instructions](deploy/README.md) for local verification, the private binary dependency bundle used by CI, Cloud Run/Cloud SQL preparation, account provisioning and release/rollback. See [workspace operations](deploy/WORKSPACE.md) for tenant isolation, encryption keys, schema migration, source-pack onboarding, approval/recovery and backup verification.

## Memo review output

Generate a memo and its linked evidence companion from a retained source pack:

```bash
sbt "server/runMain logicov.poc.DealApp poc/samples/packs/oxy poc/out/round3"
sbt "server/runMain logicov.poc.MemoApp poc/out/round3/oxy-deal.json poc/out/round3 /usr/bin/chromium --narratives --source-pack=poc/samples/packs/oxy"
sbt "server/runMain logicov.poc.BundleApp poc/out/round3 [readme.txt]"  # zip the memos, audits and sources with checksums
```

The output includes the PDF, HTML, an audit HTML file and a copy of the source pack. Keep these together when sharing so evidence links resolve. `context/*.md` in a pack supplies retained source context, such as the full discovery transcript; do not put a generated memo there. Narrative drafts are cached by a hash of the model instructions and facts, and re-checked against the record whenever they are reused. A draft that still fails validation after retries stops generation rather than becoming a release PDF. Figures found only in the discovery transcript are listed in the audit companion for the analyst to confirm. `narrativeNotes` in `deal.json` gives the analyst's direction for a section, keyed by section title (for example, the order of the key risks). Numeric matching is not a factual or credit approval check.

Each `stressScenarios` entry may specify an `ebitdaChange` such as `-0.2`, `-0.3` or `-0.4`. An optional `revenueChange` requires `costFlex`: the fraction of lost revenue offset by lower costs, from 0 (fixed costs) to 1 (fully offset). Revenue and EBITDA shocks cannot be combined. `addedDebt` and `equityChange` are explicit changes in $ millions. EBITDA-only scenarios do not automatically change capitalization. A scenario resulting in nonpositive EBITDA shows leverage and coverage as n.m. (not meaningful); loss-case forecasting is outside this simple sensitivity model.

`ratingReview`, when present in analyst inputs, prevents an unconditional approval recommendation. If a methodology correction changes the calculated risk rating, a prior override must be reviewed rather than silently reapplied. `agencyUpdates` adds an agency's outlook or rating date that the call states outside its facts table, with its source. A peer's `ratingsSource` cites where analyst-entered agency ratings come from; without one, the memo uses the ratings the peer discloses in its 10-K, each verified against the filing text, and `cik` pins the registrant when a ticker maps to more than one. `commodityModifier` records the commodity class behind the Risk Rating Guidance's over-85% modifier, and `concentration.label` says what the largest-concentration measure is (for example, a geographic share of volumes).
