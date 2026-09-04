export interface Meta {
  page: number;
  pageSize: number;
  total: number;
}

export interface ApiErrorDetail {
  field: string;
  message: string;
}

export interface ApiErrorBody {
  code: string;
  message: string;
  details: ApiErrorDetail[] | null;
}

export interface Envelope<T> {
  data: T;
  meta: Meta | null;
  error: ApiErrorBody | null;
}

export type EnvironmentType = "DEV" | "STAGING" | "PRODUCTION";
export type EnvironmentStatus = "ACTIVE" | "INACTIVE";
export type SuiteType = "API" | "UI" | "PERFORMANCE";
export type RunStatus =
  | "PENDING"
  | "RUNNING"
  | "PASSED"
  | "FAILED"
  | "CANCELLED";
export type ResultStatus = "PASSED" | "FAILED" | "SKIPPED" | "FLAKY";

// --- Repository-owned framework execution (ADR-009, Phase 2F) ---
export type RepositoryProvider = "GITHUB" | "GITLAB";
export type RepoFramework = "PLAYWRIGHT" | "JUNIT" | "PYTEST" | "CYPRESS" | "K6";
export type RepoReportFormat = "JUNIT_XML" | "K6_SUMMARY_JSON";
export type RepoResourceProfile = "SMALL" | "MEDIUM" | "LARGE";
export type RepoNetworkPolicy = "ISOLATED" | "EGRESS";
export type RepoRefType = "BRANCH" | "TAG" | "COMMIT";
export type RepositoryRunState =
  | "PENDING"
  | "RUNNING"
  | "COMPLETED"
  | "FAILED"
  | "CANCELLED";
export type RepositoryItemStatus = "PASSED" | "FAILED" | "SKIPPED" | "ERROR";

export interface AuthTokenResponse {
  accessToken: string;
  tokenType: "Bearer";
  expiresIn: number;
  refreshToken: string;
}

export interface ProjectResponse {
  id: string;
  name: string;
  description: string | null;
  slug: string;
  createdBy: string;
  createdAt: string;
  updatedAt: string;
}

export interface EnvironmentResponse {
  id: string;
  projectId: string;
  name: string;
  baseUrl: string;
  type: EnvironmentType;
  status: EnvironmentStatus;
  createdAt: string;
  updatedAt: string;
}

export interface TestSuiteResponse {
  id: string;
  projectId: string;
  name: string;
  description: string | null;
  type: SuiteType;
  createdAt: string;
  updatedAt: string;
}

export interface RepoEnvVar {
  name: string;
  value: string;
}

export interface RepoSecretVar {
  name: string;
  secretRef: string;
}

/** ADR-009 §11 — mirrors `RepoTestPayload` (apps/api testsuite module). */
export interface RepoTestPayload {
  repositoryConnectionId: string;
  requestedRef: string;
  framework: RepoFramework;
  workingDir?: string | null;
  command: string[];
  reportFormat: RepoReportFormat;
  reportPaths?: string[] | null;
  artifactGlobs?: string[] | null;
  environmentVars?: RepoEnvVar[] | null;
  secretVars?: RepoSecretVar[] | null;
  resourceProfile?: RepoResourceProfile | null;
  networkPolicy?: RepoNetworkPolicy | null;
  timeoutSeconds?: number | null;
}

export interface TestCaseResponse {
  id: string;
  suiteId: string;
  name: string;
  description: string | null;
  orderIndex: number;
  createdAt: string;
  updatedAt: string;
  repoTest?: RepoTestPayload | null;
}

/** ADR-009 §11 — additive-nullable block on `GET /api/v1/runs/{id}`. */
export interface RepositoryRunResponse {
  provider: RepositoryProvider;
  repoPath: string;
  requestedRef: string;
  commitSha: string;
  refType: RepoRefType;
  framework: RepoFramework;
  state: RepositoryRunState;
  runnerImageDigest: string | null;
  containerExitCode: number | null;
  itemsTotal: number | null;
  itemsPassed: number | null;
  itemsFailed: number | null;
  itemsSkipped: number | null;
  checkoutAt: string | null;
  startedAt: string | null;
  finishedAt: string | null;
}

export interface RunResponse {
  id: string;
  projectId: string;
  suiteId: string;
  environmentId: string;
  status: RunStatus;
  triggeredBy: string;
  startedAt: string | null;
  completedAt: string | null;
  createdAt: string;
  repositoryRun?: RepositoryRunResponse | null;
}

export interface TestResultResponse {
  id: string;
  runId: string;
  testCaseId: string;
  status: ResultStatus;
  durationMs: number | null;
  errorMessage: string | null;
  retryCount: number;
  createdAt: string;
}

/** ADR-009 §11 — a parsed per-test row on the results payload's `meta.repositoryItems`. */
export interface RepositoryTestItemResponse {
  suite: string | null;
  name: string;
  status: RepositoryItemStatus;
  durationMs: number | null;
  failureType: string | null;
  failureMessage: string | null;
}

/** ADR-009 §11 — a repository connection (GitHub/GitLab), never carries a token. */
export interface RepositoryConnectionResponse {
  id: string;
  projectId: string;
  provider: RepositoryProvider;
  host: string;
  ownerPath: string;
  repoName: string;
  defaultRef: string;
  credentialRef: string | null;
  createdAt: string;
  updatedAt: string;
}

export interface RegisterRepositoryConnectionRequest {
  provider: RepositoryProvider;
  host?: string;
  ownerPath: string;
  repoName: string;
  defaultRef?: string;
  credentialRef?: string;
}

export interface UpdateRepositoryConnectionRequest {
  host?: string;
  ownerPath: string;
  repoName: string;
  defaultRef: string;
  credentialRef?: string;
}

export interface TestConnectionResponse {
  ok: boolean;
  defaultBranch: string | null;
  resolvedHost: string | null;
  latencyMs: number;
  error: string | null;
}

export interface ProjectAnalyticsResponse {
  projectId: string;
  totalRuns: number;
  passedRuns: number;
  failedRuns: number;
  passRatePercent: number;
  periodStart: string;
  periodEnd: string;
}

export interface CreateProjectRequest {
  name: string;
  description?: string;
  slug: string;
}

export interface UpdateProjectRequest {
  name: string;
  description?: string;
}

export interface CreateEnvironmentRequest {
  name: string;
  baseUrl: string;
  type: EnvironmentType;
}

export interface UpdateEnvironmentRequest {
  name: string;
  baseUrl: string;
  type: EnvironmentType;
  status: EnvironmentStatus;
}

export interface CreateSuiteRequest {
  name: string;
  description?: string;
  type: SuiteType;
}

export interface UpdateSuiteRequest {
  name: string;
  description?: string;
  type: SuiteType;
}

export interface CreateCaseRequest {
  name: string;
  description?: string;
  orderIndex?: number;
  repoTest?: RepoTestPayload;
}

export interface UpdateCaseRequest {
  name: string;
  description?: string;
  orderIndex: number;
  repoTest?: RepoTestPayload;
}

export interface CreateRunRequest {
  projectId: string;
  suiteId: string;
  environmentId: string;
}
