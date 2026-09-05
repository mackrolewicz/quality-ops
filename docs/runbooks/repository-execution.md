# Repository execution runbook (ADR-009 / Phase 2F)

Operational notes for repository-owned framework execution: the two-phase
(checkout → framework) Docker runner the Worker drives for a `repoTest` case.
See `docs/architecture/decisions/009-repository-owned-framework-execution.md`
for the full design.

## Compose network topology (WP9)

`infra/compose/docker-compose.yml` defines two networks in addition to the
implicit `default`:

| Network | `internal` | Members | Purpose |
|---|---|---|---|
| `qualityops-internal` | `true` (no route out) | `postgres`, `redis`, `kafka`, `minio`, `minio-bootstrap`, `docker-proxy`, and (dev overlay) `api`/`api-2`/`worker`/`gateway` | The platform's own control plane. A repository run's sibling containers are **never** members. |
| `qualityops-runner-egress` | `false` (plain bridge) | (dev overlay) `worker` — so compose actually creates the network for docker-java to attach to by name; an `EGRESS`-policy checkout/framework container joins it **only at container-create time**, never via this compose file | Outbound-only egress for `git fetch` and an `EGRESS`-policy framework run. No route to `qualityops-internal`. |

An `ISOLATED`-policy framework container (`RepoNetworkPolicy.ISOLATED`, the
default) joins **neither** network — Docker `NetworkMode.NONE` — so it cannot
resolve or reach anything, including the platform's own data services, by
construction. Validate the topology without starting anything:

```
docker compose -f infra/compose/docker-compose.yml \
               -f infra/compose/docker-compose.dev.yml config
```

To prove isolation with a throwaway probe (never against the live named
stack):

```
docker network create --internal qo-probe-internal
docker run --rm --network qo-probe-internal alpine ping -c1 -W2 1.1.1.1   # fails — no route out
docker network rm qo-probe-internal
```

## `docker-proxy` — verb-allowlisted broker in front of the host socket

The Worker never binds `/var/run/docker.sock` directly in compose/staging. It
talks to `tecnativa/docker-socket-proxy` over `tcp://docker-proxy:2375`, which
allowlists only `containers` create/start/wait/kill/logs/inspect, `images`
inspect/pull, and `networks` — and denies `EXEC`, `COMMIT`, `BUILD`, `VOLUMES`,
`SWARM`, `SYSTEM`. A compromised repo-run command therefore cannot escalate
through the Worker into `docker exec` on another container, image builds, host
volume mounts, or Swarm — the daemon connection is real but verb-capped.

`qualityops.repo-exec.docker.require-proxy` (env `REPO_EXEC_REQUIRE_PROXY`)
fails Worker startup if `DOCKER_HOST` resolves to a raw `unix://` / `npipe://`
/ `fd://` socket instead of `tcp://docker-proxy:2375`. Compose sets both
`DOCKER_HOST=tcp://docker-proxy:2375` and `REPO_EXEC_REQUIRE_PROXY=true` on the
`worker` service and makes it `depends_on: docker-proxy: condition:
service_healthy`.

## Docker endpoint

`qualityops.repo-exec.docker.host` (env `DOCKER_HOST`).

| Environment | Value | `require-proxy` |
|---|---|---|
| Local dev (Linux/macOS) | `unix:///var/run/docker.sock` | `false` (loud WARN) |
| Local dev (Windows Docker Desktop) | `npipe:////./pipe/docker_engine` | `false` (loud WARN) |
| Compose / staging | `tcp://docker-proxy:2375` | `true` |

`DockerContainerRunnerConfig` fails Worker startup if `require-proxy=true` and the
host resolves to a raw `unix://` / `npipe://` / `fd://` socket. The Worker
process holding raw daemon access is equivalent to host root — acceptable only
for local dev.

## Disk quota — `withStorageOpt` is best-effort

`HostConfig.withStorageOpt({"size": "<N>m"})` requires the `overlay2` storage
driver on **xfs with `pquota`**. On Docker Desktop (overlay2 without pquota — the
common dev/CI case) the daemon rejects or silently ignores it.

`DockerContainerRunner` handles this: it attempts `withStorageOpt` once, and on a
rejection that mentions `storage-opt` / `pquota` / `size … not supported` it
logs a single WARN, sets `storageOptSupported=false`, and recreates the
container without it. From then on the **`du` watchdog**
(`RepositoryExecutionRunner`, `container.workspace-watchdog` PT5S — WP8) that
`killContainer`s a workspace exceeding `container.max-workspace-mb` is the disk
bound of record. `tmpfs` on `/tmp` is always size-capped regardless.

## Orphan containers

`RepoContainerSweeper` (boot `ApplicationRunner` + `@Scheduled` every
`container-sweep-interval` PT10M) enumerates `label=com.qualityops.managed=true`
containers, subtracts those whose `worker.execution_attempt` is COMPLETED, and
calls `ContainerRunnerPort.sweepOrphans(<possibly-live>)`, which additionally
drops any managed container older than `max-run-timeout + 10m`. Manual cleanup:

```
docker ps -a --filter label=com.qualityops.managed=true
docker rm -f $(docker ps -aq --filter label=com.qualityops.managed=true)
```

## Kill switch

`qualityops.repo-exec.enabled=false` removes the `DockerClient`, the pre-puller,
the sweeper, and the `RepositoryExecutionRunner` from the context — a
browser/API-only Worker. `ExecutionRunnerResolver` falls back to the
`BlockedRepositoryRunner` sentinel for any `repoTest` case that still arrives
(`BLOCKED "repository execution unavailable"`, never an NPE or a simulated
run) — see ADR-009 §1's rolling-deploy skew guard.

## `worker` / `worker-repo` deployment split (blast-radius mitigation)

A deployment that wants repository runs off the browser/API Worker can run a
second deployment of the **same image**:

- `worker` (browser + API): `qualityops.repo-exec.enabled=false`.
- `worker-repo` (repository only): `WORKER_EXECUTION_MODE` unchanged, but the
  browser/API `ExecutionRunner` beans are left enabled too (there is no
  equivalent kill switch for them in 2F) — the practical mitigation is
  **capacity + failure isolation**, not a hard runner ban: point repository-run
  traffic at `worker-repo` by giving it its own consumer group / partition
  assignment strategy, or, simplest, run `worker-repo` as the only replica
  with `qualityops.repo-exec.enabled=true` and set every other replica's flag
  to `false`. This is a topology change, not a code change — see ADR-009 §1.

## Digest-pin rotation

Runner-image digests are **version-controlled and `CODEOWNERS`-guarded**
(`infra/compose/runner-images.env` — the single source of truth for the
`apps/api` and `apps/worker` `application.yml` defaults and for the CI Trivy
scan matrix). Never hand-edit a digest string. To bump a preset image or
pick up a security patch:

1. Decide the new tag (e.g. `python:3.12-slim` after a base-image patch, or a
   version bump like `grafana/k6:0.55.0`).
2. Resolve its real digest the same way `AbstractDockerRunnerIT.bootOnce()`
   resolves `alpine/git`'s:
   ```
   docker pull python:3.12-slim
   docker inspect python:3.12-slim --format '{{json .RepoDigests}}'
   ```
3. Update the one line in `infra/compose/runner-images.env`, and the matching
   default in both `apps/api/src/main/resources/application.yml` and (for
   `checkout`, worker-only) `apps/worker/src/main/resources/application.yml`.
4. Open a PR — `CODEOWNERS` requires review on `infra/compose/runner-images.env`
   the same as `.trivyignore` / the Dependency-Check suppressions file.
5. CI's `runner-image-scan` job (Trivy, matrix over the six refs) and
   `backend-it-docker` (pre-pulls all six, runs the worker's `@Tag("docker")`
   batch for real on Linux) both pick up the new pin automatically.

A digest that doesn't byte-match what `DockerContainerRunner` pulls is refused
before any container starts (`DigestMismatchException` → case `BLOCKED
{reason=digest_mismatch}`) — a rotation is only "live" once the pinned digest
in config matches what the registry actually serves for that tag.

## Planted-secret check

Mirrors `docs/runbooks/security-scanning.md`'s planted-vulnerable-dependency
exit check: verify the redaction path actually works end to end, not just
that the code compiles.

1. Author a repo test case with a `secretVars` entry (`SecretEnvVar`) pointing
   at a `secretRef` key resolvable in the Worker's `EnvFileSecretResolver`
   (e.g. `QUALITYOPS_SECRET_DEMO_PASSWORD`, already seeded by compose).
2. Point the framework command at a fixture that deliberately echoes the
   resolved env var to stdout and into its JUnit `<failure>` message (this is
   exactly what `SecretNotLeakedIT` — `@Tag("docker")` — automates).
3. Run it, then check: the streamed console log / staged `CONSOLE_LOG`
   artifact, `RepositoryTestItem.failureMessage`, `results.chunk`, the v5
   terminal, and `repository_run.error_detail` must all show the masked
   placeholder, never the plaintext. The raw Docker daemon log (fetched
   independently, bypassing the Worker's `LogSink`) SHOULD still show the
   plaintext — that's what proves the fixture genuinely leaked it and the
   masking is a Worker-side control, not an artifact of a no-op test.
4. With `qualityops.repo-exec.upload-secret-run-artifacts=false` (default),
   confirm the raw report/console artifact reference comes back
   `UNAVAILABLE:suppressed-secret-run` rather than a real object key.

If any of the above shows the plaintext, treat it as a `P0` — the mask set is
built once per execution (`Redactor.forExecution`) from every resolved secret
value plus the checkout token; a gap almost always means a new secret-bearing
field was added without adding it to that mask set.
