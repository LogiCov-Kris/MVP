# Operator tools

The maintenance utilities are a separate Scala build with public dependencies. They can bootstrap the application's reviewed private dependencies without first resolving the application build. Java 25 and sbt 2.0.9 are required.

Build once after changing the tools:

```bash
./deploy/tools.sh build
```

GitHub Actions runs the maintenance regression suite, stages the tools, installs the pinned dependency bundle, and then builds and verifies the application. It also rebuilds all three reference records from newly trimmed fixtures and independently checks their financial values against retained raw sources.

| Command after `./deploy/tools.sh` | Purpose |
| --- | --- |
| `prepare-dependencies [repository]` | Export reachable local binary dependencies to `target/build-dependencies`. Set `LOGICOV_IVY_LOCAL` to override `~/.ivy2/local`. Source, documentation and test artifacts are excluded. |
| `install-dependencies archive sha256 destination` | Validate the archive digest, safe paths and every payload hash before installing into `destination/local`. |
| `smoke` | Check a disposable running application's authentication, frontend and statement import. Requires `LOGICOV_TEST_URL`, `LOGICOV_TEST_EMAIL` and `LOGICOV_TEST_PASSWORD`. |
| `capture-mail` | Capture test mail on loopback port 18424 to `target/email-smoke/message.json` with owner-only permissions. Never use for live mail. |
| `verify-restore` | Read-only verification of an isolated restored database: original source hashes, saved approvals/PDFs and cross-tenant denial. Requires `LOGICOV_RESTORE_URL`, `LOGICOV_TEST_EMAIL`, `LOGICOV_OTHER_EMAIL` and their shared test-only `LOGICOV_TEST_PASSWORD`. |
| `verify-artifacts` | Read-only verification of already-generated KMI, WLK and OXY memos, bundle hashes, approved snapshots and source links. Requires the same URL/email/password variables as `smoke`. Downloads bundles to `LOGICOV_TEST_OUTPUT`, default `poc/out/workspace-verification`. It does not approve or generate memos. |
| `trim-pack source destination repository` | Create a reduced reference fixture. The destination must be new and separate from the source. Review the output before replacing retained fixtures. |
| `compare-records first.json second.json` | Require identical parsed deal records, including values and citations. |
| `verify-grid --records directory --packs directory --output report.json` | Independently reconcile the three reference records and peer grids against raw SEC facts and filing instances. Optional `--pack-suffix` supports alternate pack names. Unresolved values are written to the report and fail the command. |

Use dedicated test accounts for smoke checks and restore drills. Verification is evidence of the checked state, not client acceptance or proof of arbitrary document-layout coverage. Keep credentials, private dependency archives, source packs and generated review artifacts out of version control.
