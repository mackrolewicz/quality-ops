import type { AxiosResponse } from "axios";

import type { Envelope, Meta } from "./types";

export function unwrap<T>(res: AxiosResponse<Envelope<T>>): T {
  return res.data.data;
}

export interface ListResult<T> {
  items: T[];
  meta: Meta;
}

export function unwrapList<T>(res: AxiosResponse<Envelope<T[]>>): ListResult<T> {
  const items = res.data.data ?? [];
  const meta = res.data.meta ?? {
    page: 1,
    pageSize: items.length,
    total: items.length,
  };
  return { items, meta };
}
