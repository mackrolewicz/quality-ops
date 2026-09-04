import { ApiError } from "../../src/api/ApiError";
import { queryClient, retryOnceUnless4xx } from "../../src/lib/queryClient";

describe("retryOnceUnless4xx", () => {
  it("does not retry a 400 ApiError", () => {
    expect(retryOnceUnless4xx(0, new ApiError("X", "m", null, 400))).toBe(false);
  });

  it("does not retry a 403 ApiError", () => {
    expect(retryOnceUnless4xx(0, new ApiError("X", "m", null, 403))).toBe(false);
  });

  it("does not retry a 404 ApiError", () => {
    expect(retryOnceUnless4xx(0, new ApiError("X", "m", null, 404))).toBe(false);
  });

  it("retries a 500 ApiError on the first failure", () => {
    expect(retryOnceUnless4xx(0, new ApiError("X", "m", null, 500))).toBe(true);
  });

  it("stops retrying a 500 ApiError after one attempt", () => {
    expect(retryOnceUnless4xx(1, new ApiError("X", "m", null, 500))).toBe(false);
  });

  it("retries a non-ApiError once", () => {
    expect(retryOnceUnless4xx(0, new Error("boom"))).toBe(true);
  });

  it("stops retrying a non-ApiError after one attempt", () => {
    expect(retryOnceUnless4xx(1, new Error("boom"))).toBe(false);
  });

  it("retries an ApiError with a null status via the fallback path", () => {
    expect(retryOnceUnless4xx(0, new ApiError("X", "m", null, null))).toBe(true);
  });

  it("does not retry a 429 ApiError", () => {
    expect(
      retryOnceUnless4xx(0, new ApiError("RATE_LIMITED", "m", null, 429)),
    ).toBe(false);
  });

  it("does not retry a 499 ApiError but retries a 500 ApiError", () => {
    expect(retryOnceUnless4xx(0, new ApiError("X", "m", null, 499))).toBe(false);
    expect(retryOnceUnless4xx(0, new ApiError("X", "m", null, 500))).toBe(true);
  });
});

describe("queryClient defaultOptions", () => {
  it("wires retryOnceUnless4xx into the query retry option", () => {
    expect(queryClient.getDefaultOptions().queries?.retry).toBe(
      retryOnceUnless4xx,
    );
  });

  it("disables retries for mutations", () => {
    expect(queryClient.getDefaultOptions().mutations?.retry).toBe(false);
  });
});
