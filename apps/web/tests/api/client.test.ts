import axios, {
  AxiosError,
  AxiosHeaders,
  type AxiosResponse,
  type InternalAxiosRequestConfig,
} from "axios";
import { type MockInstance } from "vitest";

import { isApiError } from "../../src/api/ApiError";
import { authBridge } from "../../src/api/authBridge";
import { api } from "../../src/api/client";
import { tokenStore } from "../../src/api/tokenStore";

type TestConfig = InternalAxiosRequestConfig & { __isRetry?: boolean };
type Handler = (cfg: TestConfig) => AxiosResponse;

const refreshOk = {
  data: { data: { accessToken: "new", refreshToken: "new-r" } },
};

const ok = (cfg: TestConfig, data: unknown): AxiosResponse => ({
  status: 200,
  statusText: "OK",
  data,
  headers: {},
  config: cfg,
});

const httpError = (cfg: TestConfig, status: number, data: unknown): never => {
  throw new AxiosError(
    `Request failed with status code ${status}`,
    status >= 500 ? "ERR_BAD_RESPONSE" : "ERR_BAD_REQUEST",
    cfg,
    null,
    { status, data, statusText: "", headers: {}, config: cfg } as AxiosResponse,
  );
};

const networkError = (cfg: TestConfig): never => {
  throw new AxiosError("Network Error", "ERR_NETWORK", cfg, null);
};

const authHeader = (cfg: TestConfig): string =>
  String((cfg.headers as AxiosHeaders).get("Authorization"));

function setAdapter(handler: Handler): void {
  api.defaults.adapter = async (cfg) => handler(cfg as TestConfig);
}

describe("api client 401 refresh interceptor", () => {
  let emitted: number[];
  let unsub: () => void;
  let postSpy: MockInstance<typeof axios.post>;

  beforeEach(() => {
    tokenStore.set({ accessToken: "old", refreshToken: "r0" });
    emitted = [];
    unsub = authBridge.onUnauthenticated(() => emitted.push(1));
    postSpy = vi.spyOn(axios, "post").mockResolvedValue(refreshOk as never);
    vi.spyOn(tokenStore, "clear");
  });

  afterEach(() => {
    unsub();
    vi.restoreAllMocks();
    api.defaults.adapter = undefined;
  });

  it("refreshes once and replays the request with the new token", async () => {
    const retryAuth: string[] = [];
    setAdapter((cfg) => {
      if (cfg.url === "/api/v1/projects") {
        if (cfg.__isRetry) {
          retryAuth.push(authHeader(cfg));
          return ok(cfg, { data: [] });
        }
        return httpError(cfg, 401, { error: { code: "UNAUTHORIZED", message: "x" } });
      }
      return httpError(cfg, 500, {});
    });

    const res = await api.get("/api/v1/projects");

    expect(res.data).toEqual({ data: [] });
    expect(postSpy).toHaveBeenCalledTimes(1);
    expect(String(postSpy.mock.calls[0][0])).toMatch(/\/auth\/refresh$/);
    expect(retryAuth).toEqual(["Bearer new"]);
    expect(tokenStore.getAccessToken()).toBe("new");
  });

  it("collapses concurrent 401s into a single refresh", async () => {
    postSpy.mockImplementation(async () => {
      await new Promise((r) => setTimeout(r, 0));
      return refreshOk as never;
    });
    const retryAuth: string[] = [];
    setAdapter((cfg) => {
      if (cfg.__isRetry) {
        retryAuth.push(authHeader(cfg));
        return ok(cfg, { data: cfg.url });
      }
      return httpError(cfg, 401, { error: { code: "UNAUTHORIZED", message: "x" } });
    });

    const results = await Promise.all([
      api.get("/api/v1/projects"),
      api.get("/api/v1/runs"),
      api.get("/api/v1/suites"),
    ]);

    expect(results.map((r) => r.data)).toEqual([
      { data: "/api/v1/projects" },
      { data: "/api/v1/runs" },
      { data: "/api/v1/suites" },
    ]);
    expect(postSpy).toHaveBeenCalledTimes(1);
    expect(retryAuth).toEqual(["Bearer new", "Bearer new", "Bearer new"]);
  });

  it("clears the session and emits when refresh fails", async () => {
    postSpy.mockRejectedValueOnce(
      new AxiosError("refresh failed", "ERR_BAD_REQUEST", undefined, null, {
        status: 401,
        data: {},
        statusText: "",
        headers: {},
        config: {} as InternalAxiosRequestConfig,
      } as AxiosResponse),
    );
    setAdapter((cfg) =>
      httpError(cfg, 401, { error: { code: "UNAUTHORIZED", message: "x" } }),
    );

    const err = await api.get("/api/v1/projects").catch((e) => e);

    expect(isApiError(err)).toBe(true);
    expect(tokenStore.clear).toHaveBeenCalledTimes(1);
    expect(emitted).toHaveLength(1);
  });

  it("does not refresh on a 401 from an /auth/ endpoint", async () => {
    setAdapter((cfg) =>
      httpError(cfg, 401, { error: { code: "UNAUTHORIZED", message: "bad" } }),
    );

    const err = await api.post("/auth/login", {}).catch((e) => e);

    expect(isApiError(err)).toBe(true);
    expect(err.status).toBe(401);
    expect(postSpy).not.toHaveBeenCalled();
  });

  it("gives up without a second refresh when the replay still 401s", async () => {
    setAdapter((cfg) =>
      httpError(cfg, 401, { error: { code: "UNAUTHORIZED", message: "x" } }),
    );

    const err = await api.get("/api/v1/projects").catch((e) => e);

    expect(isApiError(err)).toBe(true);
    expect(postSpy).toHaveBeenCalledTimes(1);
    expect(tokenStore.clear).toHaveBeenCalledTimes(1);
    expect(emitted).toHaveLength(1);
  });

  it("passes non-401 errors through as ApiError without refreshing", async () => {
    setAdapter((cfg) => {
      if (cfg.url === "/api/v1/boom") {
        return httpError(cfg, 500, { error: { code: "BOOM", message: "x" } });
      }
      return networkError(cfg);
    });

    const serverErr = await api.get("/api/v1/boom").catch((e) => e);
    expect(isApiError(serverErr)).toBe(true);
    expect(serverErr.code).toBe("BOOM");
    expect(serverErr.status).toBe(500);

    const netErr = await api.get("/api/v1/offline").catch((e) => e);
    expect(isApiError(netErr)).toBe(true);
    expect(netErr.code).toBe("NETWORK_ERROR");
    expect(netErr.status).toBeNull();

    expect(postSpy).not.toHaveBeenCalled();
  });

  it("resets the single-flight promise so a later 401 can refresh again", async () => {
    setAdapter((cfg) => {
      if (cfg.__isRetry) {
        return ok(cfg, { data: cfg.url });
      }
      return httpError(cfg, 401, { error: { code: "UNAUTHORIZED", message: "x" } });
    });

    const first = await api.get("/api/v1/projects");
    expect(first.data).toEqual({ data: "/api/v1/projects" });

    const second = await api.get("/api/v1/runs");
    expect(second.data).toEqual({ data: "/api/v1/runs" });

    expect(postSpy).toHaveBeenCalledTimes(2);
  });

  it("clears the session and never calls /auth/refresh when no refresh token is stored", async () => {
    tokenStore.set({ accessToken: "old", refreshToken: "" });
    setAdapter((cfg) =>
      httpError(cfg, 401, { error: { code: "UNAUTHORIZED", message: "x" } }),
    );

    const err = await api.get("/api/v1/projects").catch((e) => e);

    expect(isApiError(err)).toBe(true);
    expect(postSpy).not.toHaveBeenCalled();
    expect(tokenStore.clear).toHaveBeenCalledTimes(1);
    expect(emitted).toHaveLength(1);
  });

  it("rejects every concurrent caller and attempts the shared refresh only once when refresh fails", async () => {
    postSpy.mockRejectedValue(
      new AxiosError("refresh failed", "ERR_BAD_REQUEST", undefined, null, {
        status: 401,
        data: {},
        statusText: "",
        headers: {},
        config: {} as InternalAxiosRequestConfig,
      } as AxiosResponse),
    );
    setAdapter((cfg) =>
      httpError(cfg, 401, { error: { code: "UNAUTHORIZED", message: "x" } }),
    );

    const results = await Promise.allSettled([
      api.get("/api/v1/projects"),
      api.get("/api/v1/runs"),
      api.get("/api/v1/suites"),
    ]);

    expect(results.every((r) => r.status === "rejected")).toBe(true);
    expect(
      results.every((r) => r.status === "rejected" && isApiError(r.reason)),
    ).toBe(true);
    expect(postSpy).toHaveBeenCalledTimes(1);
    expect(emitted).toHaveLength(1);
    expect(tokenStore.clear).toHaveBeenCalledTimes(1);
  });

  it("does not attempt a refresh for a 401 whose config is missing", async () => {
    setAdapter(() => {
      throw new AxiosError("x", "ERR_BAD_REQUEST", undefined, null, {
        status: 401,
        data: {},
        statusText: "",
        headers: {},
        config: {} as InternalAxiosRequestConfig,
      } as AxiosResponse);
    });

    const err = await api.get("/api/v1/projects").catch((e) => e);

    expect(isApiError(err)).toBe(true);
    expect(postSpy).not.toHaveBeenCalled();
    expect(emitted).toHaveLength(0);
  });

  describe("request interceptor", () => {
    it("attaches the stored access token as a Bearer header on outgoing requests", async () => {
      tokenStore.set({ accessToken: "tok-1", refreshToken: "r0" });
      setAdapter((cfg) => ok(cfg, authHeader(cfg)));

      const res = await api.get("/api/v1/projects");

      expect(res.data).toBe("Bearer tok-1");
    });
  });
});
