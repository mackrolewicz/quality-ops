import type { AxiosResponse } from "axios";

import { unwrap, unwrapList } from "../../src/api/envelope";
import type { Envelope } from "../../src/api/types";

function makeResponse<T>(body: Envelope<T>): AxiosResponse<Envelope<T>> {
  return { data: body } as AxiosResponse<Envelope<T>>;
}

describe("envelope", () => {
  it("unwrap returns the data payload", () => {
    const res = makeResponse({ data: { id: "1" }, meta: null, error: null });
    expect(unwrap(res)).toEqual({ id: "1" });
  });

  it("unwrapList returns items and meta", () => {
    const res = makeResponse({
      data: [{ id: "1" }, { id: "2" }],
      meta: { page: 2, pageSize: 20, total: 42 },
      error: null,
    });
    const result = unwrapList(res);
    expect(result.items).toHaveLength(2);
    expect(result.meta).toEqual({ page: 2, pageSize: 20, total: 42 });
  });

  it("unwrapList synthesizes meta when it is null", () => {
    const res = makeResponse({
      data: [{ id: "1" }, { id: "2" }, { id: "3" }],
      meta: null,
      error: null,
    });
    expect(unwrapList(res).meta).toEqual({
      page: 1,
      pageSize: 3,
      total: 3,
    });
  });
});
