# Task 001 — Make manual PKCS#11 middleware selection persistent (ATR→DLL learned mapping)

**Repo:** `nexu` (MAECI_CSFD) — Java 8, Maven multi-module, JavaFX 8, EUPL 1.1
**Status:** done (2026-11-04) — see [Deviations & notes](#deviations--notes)
**Priority:** P1

## Background (read first)

Nexu is a smart-card middleware launcher for e-signing built on top of ESIGN DSS. It
abstracts three token APIs via the `ScAPI` enum
(`nexu-api/src/main/java/lu/nowina/nexu/api/ScAPI.java`):

- **MSCAPI** — Windows CryptoAPI (OS-mediated, Windows-only, no library path needed).
- **PKCS#11** — vendor-supplied native library (e.g. `C:\Windows\System32\eTPKCS11.dll`
  for CNS/eToken). PKCS#11 has **no OS-level module discovery**: the application must be
  told the DLL path.
- **MOCCA** — legacy, mostly removed; do not touch its code paths.

How the PKCS#11 DLL path is resolved today — two mechanisms that should be reconciled:

1. **Static (auto) path** — a database `store.xml` mapping ATR →
   `ConnectionInfo{env, selectedApi, apiParam(=DLL path)}`.
   - Template: `nexu-standalone/src/main/resources/store.xml`
   - Bootstrap: `NexUApp.buildAPI()` (`nexu-standalone/src/main/java/lu/nowina/nexu/NexUApp.java`)
     copies the template to `nexuHome/store.xml` on first run (writable), then loads it via
     `ProductDatabaseLoader.load(SCDatabase.class, File)`
     (`nexu-model/src/main/java/lu/nowina/nexu/ProductDatabaseLoader.java`), which registers
     a `DatabaseEventHandler` that re-marshal/saves the file on every DB mutation.
   - Consumption: `InternalAPI.matchingProductAdapters()`
     (`nexu-core/src/main/java/lu/nowina/nexu/InternalAPI.java`) — when no plugin adapter
     accepts a `DetectedCard`, it looks up `myDatabase.getInfo(atr)` and returns a
     `Match` with `GenericCardAdapter` (`nexu-core/src/main/java/lu/nowina/nexu/generic/GenericCardAdapter.java`),
     which picks the `ConnectionInfo` by env match (fallback: index 0) and loads the DLL.
   - Slot selection inside the DLL is positional: the card's reader `terminalIndex` is used
     as `slotListIndex` (`nexu-core/src/main/java/lu/nowina/nexu/generic/Pkcs11SignatureTokenAdapter.java`).

2. **Dynamic (advanced) path** — when the ATR is unknown to every adapter and the DB,
   `CreateTokenOperation.createTokenAdvanced()`
   (`nexu-core/src/main/java/lu/nowina/nexu/flow/operation/CreateTokenOperation.java`) opens
   `api-selection.fxml` (user picks the API), then `pkcs11-params.fxml` (user browses the
   filesystem and picks the DLL manually), and builds the token connection.

**Persistence after manual selection already exists end-to-end**, with gaps:
`GetCertificateFlow` (`nexu-core/src/main/java/lu/nowina/nexu/flow/GetCertificateFlow.java`)
runs `SelectPrivateKeyOperation` (which *proves* the DLL works — keys found) and, only if
`ADVANCED_CREATION` is true, runs `AdvancedCreationFeedbackOperation`
(`nexu-core/src/main/java/lu/nowina/nexu/flow/operation/AdvancedCreationFeedbackOperation.java`),
which shows `store-result.fxml` (FXML lives in `nexu-standalone/src/main/resources/fxml/`)
and, on user confirmation, calls `((InternalAPI) api).store(atr, selectedApi, apiParam)` →
`SCDatabase.add(atr, connectionInfo)` (`nexu-model/src/main/java/lu/nowina/nexu/generic/SCDatabase.java`)
→ auto-save to `nexuHome/store.xml`. Next launch the same ATR is auto-resolved.

## Goal

Close the four gaps below so the learned ATR→DLL mapping is: persisted even in headless
mode, free of duplicates, resilient to stale DLL paths, and exposed through the public API.

## Work items

### 1. Persist the learned mapping unconditionally (fixes headless mode)

Today persistence only happens inside `if (isEnablePopUps())` in
`AdvancedCreationFeedbackOperation` — with pop-ups disabled (headless/server mode) the
mapping is never saved.

- Move the `store(...)` call into `GetCertificateFlow`, right after
  `SelectPrivateKeyOperation` returns SUCCESS and `ADVANCED_CREATION` is true — i.e.
  persistence no longer depends on the confirmation dialog (the key selection has already
  proven the mapping is valid).
- `AdvancedCreationFeedbackOperation` becomes purely informational (dialog only); remove its
  own `store(...)` call to avoid double persistence.
- Preserve current coverage of stored APIs: today the store dialog saves for MOCCA, MSCAPI
  (apiParam may be null — MSCAPI needs no DLL path) and PKCS#11 (apiParam = DLL path).
  Keep `store()` tolerant of a null `apiParam`.

### 2. Deduplicate in `SCDatabase.add()`

`SCDatabase.add(detectedAtr, cInfo)` appends unconditionally; repeating advanced creation
with the same card + DLL accumulates identical `connectionInfo` entries in `store.xml`.

- Skip the add (and the save) when the `SCInfo` for that ATR already contains a
  `ConnectionInfo` with the same `selectedApi` + `apiParam` + `env`.
- Check whether `EnvironmentInfo` (`nexu-api/src/main/java/lu/nowina/nexu/api/EnvironmentInfo.java`)
  implements `equals`/`hashCode`; if not, add them (JAXB-annotated POJO, safe) or compare
  fields individually.
- Add unit tests in `nexu-model` (extend `SCDatabaseTest`) covering: no-duplicate case and
  that a genuinely new entry (different env or DLL) is still appended.

### 3. Self-healing for a stale PKCS#11 DLL path

The saved DLL path is absolute and machine-specific. If the middleware is reinstalled to a
different location (or the card moves to another machine), `GenericCardAdapter.connect()`
fails loading a non-existent library.

- In `GenericCardAdapter.connect()`, before instantiating `Pkcs11SignatureTokenAdapter`,
  check `new File(apiParam).isFile()`; if missing, log a warning and return `null`
  (→ `NO_TOKEN`) instead of propagating a native load failure.
- Minimal follow-up (recommended): allow the flow to fall back to advanced mode when the
  DB mapping is unusable — e.g. a `removeConnectionInfo`-style method on
  `SCDatabase`/`InternalAPI` invoked (or logged for) so the next request misses the DB
  lookup and re-enters advanced creation. If you implement removal, make it best-effort
  and non-fatal.
- Add a unit test for the stale-DLL branch (mock `File`, assert `null` connection + no throw).

### 4. Expose `store(...)` on the `NexuAPI` interface

`store(String detectedAtr, ScAPI selectedApi, String apiParam)` currently lives on
`InternalAPI` and flow code casts `(InternalAPI) this.api`.

- Add the method (with javadoc: "Persists a verified ATR→API(→DLL) mapping so the same
  card is auto-resolved on the next run; no-op when no local database is configured") to
  the `NexuAPI` interface (`nexu-api/src/main/java/lu/nowina/nexu/api/NexuAPI.java`).
- Implement it in `InternalAPI` (move the existing body), update all call sites
  (`AdvancedCreationFeedbackOperation` if still present, `GetCertificateFlow`, tests) to
  drop the cast.
- Verify no other `NexuAPI` implementations exist besides `InternalAPI` (grep
  `implements NexuAPI`); update any mocks accordingly.

### Out of scope (document only, do not implement)

- **Security hardening:** `store.xml` is user-writable and its DLL path is loaded as native
  code on the next insert. Future: store SHA-256 of the DLL at save time and verify at
  connect time; add an expiry/reconfirmation policy. If you add a hash field later, keep it
  optional in the JAXB model (existing `store.xml` files must keep loading).
- Keep the one-`ConnectionInfo`-per-OS/architecture convention already used in
  `store.xml` (32/64-bit JVMs may need different DLLs).

## Acceptance criteria

1. A successful advanced creation with a PKCS#11 DLL writes the ATR→DLL entry to
   `nexuHome/store.xml` even when `enablePopUps` is false (headless).
2. Repeating advanced creation with the same card + DLL + environment does **not**
   duplicate entries in `store.xml`.
3. Removing/renaming the DLL makes the next connection fail gracefully (`NO_TOKEN`,
   clear log line, no unhandled native exception); ideally the next run re-enters
   advanced mode.
4. `NexuAPI` exposes `store(...)`; no `(InternalAPI)` cast remains in flow operations.
5. Existing `store.xml` files (template in `nexu-standalone` and the test fixture
   `nexu-model/src/test/resources/db.xml`) still unmarshal unchanged.
6. All tests pass: `mvn clean verify` (at minimum `nexu-model`, `nexu-core`,
   `nexu-standalone`), including new tests for items 2 and 3.

## Constraints

- Java 8 (`maven.compiler.source/target = 1.8`); no newer language features.
- Preserve EUPL license headers on all modified/new files (copy from a file in the same
  package).
- Follow existing patterns: slf4j logging, JAXB annotations for model classes,
  operation/flow architecture (`OperationFactory`, `OperationResult`, `BasicOperationStatus`).
- Do not change the `store.xml` wire format in an incompatible way.
- MOCCA is legacy: leave its code paths untouched.

## Suggested execution order

1. Item 4 (interface) — small, unblocks clean call sites.
2. Item 1 (unconditional persistence in the flow).
3. Item 2 (dedup + tests).
4. Item 3 (stale-DLL fallback + test).
5. Full `mvn clean verify`, then update this file's status and note any deviations.

## Result summary (2026-11-04)

- **Item 1 — done.** `GetCertificateFlow.persistAdvancedSelection()` (private helper) is called
  right after `SelectPrivateKeyOperation` SUCCESS when `ADVANCED_CREATION` is true, with the same
  store condition as the old dialog (`MOCCA || MSCAPI || apiParam != null`).
  `AdvancedCreationFeedbackOperation` is now purely informational (dialog shown, result ignored,
  no `store`). Verified by `GetCertificateFlowTest.testHeadlessAdvancedCreationStoresMapping`
  (`enablePopUps=false`, full mocked operation chain, `verify(api).store(...)`).
- **Item 2 — done.** `SCDatabase.add()` skips the add *and* the save when an identical
  `ConnectionInfo` (same `selectedApi` enum, `apiParam` via `Objects.equals`, `env` via the new
  `EnvironmentInfo.equals/hashCode` over all 6 fields) is already stored for the ATR.
  `ConnectionInfo.getApiParam()` made null-safe (`null` stays `null`, else trimmed).
  Extended `SCDatabaseTest` (5 tests: dedup + save-handler count, different apiParam still appended,
  different env still appended, different API still appended, `removeConnectionInfo` semantics).
- **Item 3 — done.** `GenericCardAdapter.connect()` PKCS#11 branch returns `null` (→ `NO_TOKEN`,
  warning log) when the stored `apiParam` is null or the file does not exist.
  `SCDatabase.removeConnectionInfo(atr, cInfo)` (best-effort, catches `Exception`, removes the
  whole `SCInfo` when it ends up empty so the next lookup misses and re-enters advanced creation)
  plus an `InternalAPI.removeConnectionInfo(atr, api, param)` wrapper. Self-healing runs from
  `InternalAPI.matchingProductAdapters()` via `removeStaleConnectionInfos(card, info)` (snapshots
  stale env-matched PKCS#11 entries pointing to missing files, then removes them — snapshot avoids
  concurrent modification of the same list). Covered by `GenericCardAdapterTest` (3 tests) and
  three new `InternalAPITest` tests (stale removed + `db.getInfo` null, existing DLL kept,
  non-matching entries kept).
- **Item 4 — done.** `NexuAPI.store(...)` added with the required javadoc; `InternalAPI.store`
  is now `@Override`; `AdvancedCreationFeedbackOperation` no longer casts. Only one
  `NexuAPI` implementation exists (`InternalAPI`, verified by grep). No `(InternalAPI)` cast for
  `store` remains anywhere; see deviations for two pre-existing unrelated casts.
- **AC#5 — verified.** `nexu-standalone/src/main/resources/store.xml` and
  `nexu-model/src/test/resources/db.xml` are unmodified (not in the git diff); JAXB output of
  `SCDatabaseTest.test1` is byte-identical in structure to the original format.

## Deviations & notes

1. **Reconstructed the missing private artifact `lu.nowina:nexu_for_mocca:1.22`** (build
   environment only, no code change). The upstream bundle (see `scripts/README.md`) 404s and the
   upstream GitHub repo no longer exists, so `nexu-core` could not resolve dependencies at all.
   The jar was rebuilt by merging `eu/europa/esig/dss/token/mocca/*` from the local
   `sscd-mocca-adapter-5.12.1.jar` with `at/gv/egiz/smcc/*` (incl. its `org/openecard/scio`
   resources) from the local `smcc-1.3.30.jar`, then installed into the local repo via
   `mvn org.apache.maven.plugins:maven-install-plugin:2.4:install-file`.
   5.12.1 (not 5.3) was chosen because its `MOCCASignatureTokenConnection` implements exactly the
   DSS 5.9 `SignatureTokenConnection` method set (verified by `javap`); the 5.3 adapter is
   binary-incompatible (references pre-model-split DSS 5.3 classes).
2. **Stale-DLL test uses a real non-existent file** in `java.io.tmpdir` instead of PowerMock
   `whenNew` `File` mocking — simpler and equally deterministic; the same branch
   (`apiParam == null || !new File(apiParam).isFile()`) is covered, and a positive test uses a
   real temp file to assert the DLL is still loaded when present.
3. **Pre-existing test failure, environment-dependent:** `nexu-api` `JREVendorTest.test1`
   asserts the running JVM vendor is recognized as Oracle; this machine only has BellSoft /
   OpenJDK JDKs (`java.vendor=BellSoft`), so it fails on the clean tree as well (verified with
   `git stash` before applying any changes). The final `mvn clean verify` was therefore run with
   `-Dtest=!JREVendorTest`; every other test in the reactor passes.
4. **Two pre-existing `(InternalAPI)` casts left in place** (out of scope, not `store` casts):
   `LogoutFlow.java:110` (`resetDisplayState()`, unisystems change) and
   `APIBuilder.java:82` (plugin registration). AC#4 only requires no cast for `store` in flow
   operations, which holds.
5. **Build requires JDK 8** (`C:\dev\java\jdk8u502-full`); the default JDK 21 fails with
   "Source option 7 is no longer supported" (`nexu-api` targets 1.7).
   Final verification: `mvn clean verify -pl nexu-api,nexu-model,nexu-core,nexu-standalone -am`
   → BUILD SUCCESS, all tests green (nexu-api 18, nexu-model 6, nexu-util 7, nexu-core 26,
   nexu-standalone 10), with the exclusion from note 3.
