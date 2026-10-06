# LogiCov

Credit memo pipeline: deal package ingestion, the Golden Grid, deterministic spreading, analyst approval, policy overlay, and a cited PDF memo.

## Layout

| Path | What |
|---|---|
| `shared/` | Wire types compiled into both `server` and `app` |
| `server/` | `logicov.*`: spice server, LightDB on PostgreSQL, PDF parsing |
| `app/` | EveryUI web client (Scala.js) |
| `deploy/` | Local Postgres and, later, deployment |
| `poc/samples/` | Input and memo PDFs for the proof of concept (not committed) |

## Commands

```bash
./deploy/dev-postgres.sh                     # local Postgres 17 on 127.0.0.1:25432
sbt server/run                               # API on :8300 (GET /api/health)
sbt app/webBundle                            # web client into app/out
sbt "server/runMain logicov.poc.PdfDumpApp"  # poc/samples/*.pdf -> poc/out/*.txt (text with page and position)
```

Requires Java 25, sbt 2.0.9, and OUTR snapshots (`~/.ivy2/local` or GitHub Packages with `GITHUB_TOKEN`).

Configuration is Profig under `logicov` (`server/src/main/resources/config.json` holds local defaults only). Secrets come from environment variables or system properties, for example `-Dlogicov.db.password=...`.
