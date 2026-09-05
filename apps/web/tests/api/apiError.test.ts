import { AxiosError, type AxiosResponse } from "axios";

import { ApiError, isApiError } from "../../src/api/ApiError";
import { toApiError } from "../../src/api/client";
import type { Envelope } from "../../src/api/types";

describe("ApiError", () => {
  it("builds from an API error envelope", () => {
    const response = {
      status: 400,
      data: {
        data: null,
        meta: null,
        error: {
          code: "VALIDATION_ERROR",
          message: "Invalid request",
          details: [{ field: "name", message: "required" }],
        },
      },
    } as AxiosResponse<Envelope<unknown>>;
    const axiosError = new AxiosError<Envelope<unknown>>("Request failed");
    axiosError.response = response;

    const result = toApiError(axiosError);
    expect(result.code).toBe("VALIDATION_ERROR");
    expect(result.message).toBe("Invalid request");
    expect(result.details).toEqual([{ field: "name", message: "required" }]);
    expect(result.status).toBe(400);
  });

  it("falls back to a generic error on a network failure", () => {
    const axiosError = new AxiosError<Envelope<unknown>>("Network Error");
    const result = toApiError(axiosError);
    expect(result.code).toBe("NETWORK_ERROR");
    expect(result.message).toBe("Network Error");
  });

  it("isApiError narrows correctly", () => {
    expect(isApiError(new ApiError("X", "y"))).toBe(true);
    expect(isApiError(new Error("plain"))).toBe(false);
    expect(isApiError("nope")).toBe(false);
  });
});
