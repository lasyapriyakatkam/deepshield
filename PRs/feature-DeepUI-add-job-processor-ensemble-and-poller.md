Title: feat(backend): add background job processor, optional Python ensemble client, RestTemplate config, and robust pollers

Summary

This PR implements the remaining backend pieces required to run end-to-end scans automatically and reliably during local development and testing. It adds a scheduled/async ScanJob processor to pick up PENDING jobs and run the existing analysis pipeline, an optional Python ensemble client (guarded by configuration), a RestTemplate configuration with sensible timeouts, a build-time Java 17 enforcer, and simple uploader/poller scripts that use jq or Python requests.

Why

Before this change uploads created PENDING jobs but there was no worker to process them. Developers also encountered startup issues when DJL/PyTorch native binaries were unavailable for their platform. This PR:
- Ensures PENDING jobs are processed automatically (no manual test endpoint required).
- Adds an opt-in Python ensemble call with safe fallbacks and timeouts.
- Provides small scripts to run reliable local E2E tests.
- Adds a maven-enforcer rule to avoid running the build with Java < 17.

Changes (high level)

- Enabled scheduling and async processing in the Spring Boot application.
  - File: `backend/src/main/java/com/deepshield/backend/BackendApplication.java`

- Background job processor that runs the full pipeline for PENDING jobs.
  - File added: `backend/src/main/java/com/deepshield/backend/service/ScanJobProcessor.java`
  - Behavior: polls for the oldest PENDING job, marks PROCESSING, extracts frames, detects faces, runs metadata analysis and ML predictions, aggregates results, optionally calls the Python ensemble, generates an explanation, updates job status to COMPLETE and persists results. It also exposes an async `processJobById` so `ScanService` can trigger immediate processing after upload.

- Trigger async processing on upload
  - File modified: `backend/src/main/java/com/deepshield/backend/service/ScanService.java`
  - Behavior: after saving an uploaded job, it calls `scanJobProcessor.processJobById(saved.getId())` (non-blocking) so the job starts processing immediately.

- Optional Python ensemble client and RestTemplate
  - File added: `backend/src/main/java/com/deepshield/backend/service/PythonEnsembleService.java`
  - File added: `backend/src/main/java/com/deepshield/backend/config/RestConfig.java`
  - Behavior: `PythonEnsembleService` calls the configured `python.service.url` when `python.enabled=true` and returns an Optional<Double> ensemble score; failures are logged and ignored.

- Repository helper method
  - File modified: `backend/src/main/java/com/deepshield/backend/repository/ScanJobRepository.java`
  - Added: `findTopByStatusOrderByCreatedAtAsc(ScanStatus)` to support queue-like selection.

- Build enforcement
  - File modified: `backend/pom.xml`
  - Added Maven Enforcer plugin to require Java 17+ at build time.

- Local dev poller scripts
  - Added: `backend/scripts/poll_jq.sh` (uses `jq`) and `backend/scripts/poll.py` (uses `requests`) for robust upload + polling.

Files changed/added

- Added
  - backend/src/main/java/com/deepshield/backend/service/ScanJobProcessor.java
  - backend/src/main/java/com/deepshield/backend/service/PythonEnsembleService.java
  - backend/src/main/java/com/deepshield/backend/config/RestConfig.java
  - backend/scripts/poll_jq.sh
  - backend/scripts/poll.py
  - PRs/feature-DeepUI-add-job-processor-ensemble-and-poller.md (this file)

- Modified
  - backend/src/main/java/com/deepshield/backend/BackendApplication.java
  - backend/src/main/java/com/deepshield/backend/service/ScanService.java
  - backend/src/main/java/com/deepshield/backend/repository/ScanJobRepository.java
  - backend/pom.xml

Testing done

- Verified `mvn -DskipTests clean package` compiles successfully on this branch.
- Started the backend using Java 17 with `--ml.enabled=false` and ran the `backend/scripts/poll_jq.sh` script. Observed the scheduled processor pick up the job and transition it to COMPLETE with aggregated verdict and explanation.
- Confirmed the face-detection service logs warnings for the tiny 1x1 test PNG (expected) and the pipeline proceeds using fallback logic.

How to run locally

1. Build:

```bash
cd backend
mvn -DskipTests clean package
```

2. Start backend (ensure Java 17 is used):

```bash
export JAVA_HOME=/usr/local/opt/openjdk@17
export PATH="$JAVA_HOME/bin:$PATH"
java -jar target/backend-0.0.1-SNAPSHOT.jar --ml.enabled=false &> backend/backend.log & echo $! > backend/backend.pid
```

3. Create a sample image (if you need a tiny test image):

```bash
base64 -d > /tmp/deepshield_sample.png <<< 'iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR4nGNgYAAAAAMAASsJTYQAAAAASUVORK5CYII='
```

4. Run the poller (requires `jq`):

```bash
brew install jq
chmod +x backend/scripts/poll_jq.sh
backend/scripts/poll_jq.sh
```

Notes & caveats

- The ML model remains opt-in (`ml.enabled` property). Setting `ml.enabled=true` will attempt to load DJL/PyTorch native binaries and may fail on unsupported platforms — keep `ml.enabled=false` in local dev unless you have the native libs available.
- The Python ensemble client is optional and disabled by default (`python.enabled=false`). If enabled, ensure the service at `python.service.url` returns a JSON map with key `ensembleScore`.
- The scheduled processor uses a simple database selection mechanism. For production or multi-node deployments consider adding optimistic locking or job claiming to avoid duplicated processing by multiple instances.

Rollback

- Revert the commit(s) on this branch or revert the PR if needed. The changes are additive and guarded, so they should be low-risk for the local dev environment when `ml.enabled=false` and `python.enabled=false`.

Appendix: Suggested follow-ups

- Add optimistic locking (version) on `ScanJob` and a claim/update pattern to make the processor safe in multi-instance deployments.
- Move heavy ML loading into a lazy initializer with a clear `mlAvailable` flag to avoid per-request exceptions when `ml.enabled=true` but the environment is unsupported.
- Add unit/integration tests for `ScanJobProcessor`, `ResultAggregator`, and `PythonEnsembleService` (mock the RestTemplate).

---
