import { decodeJwt, extractRole, isExpired } from "../../../src/features/auth/jwt";
import type { JwtPayload } from "../../../src/types/auth";

function base64url(obj: unknown): string {
  return btoa(JSON.stringify(obj))
    .replace(/\+/g, "-")
    .replace(/\//g, "_")
    .replace(/=+$/, "");
}

function makeToken(payload: Record<string, unknown>): string {
  return `${base64url({ alg: "HS256", typ: "JWT" })}.${base64url(payload)}.sig`;
}

describe("decodeJwt", () => {
  it("parses a hand-built base64url token", () => {
    const token = makeToken({
      sub: "user-1",
      org_id: "org-1",
      roles: ["OWNER"],
      iat: 1000,
      exp: 2000,
    });
    const payload = decodeJwt(token);
    expect(payload).not.toBeNull();
    expect(payload?.sub).toBe("user-1");
    expect(payload?.org_id).toBe("org-1");
    expect(payload?.roles).toEqual(["OWNER"]);
    expect(payload?.exp).toBe(2000);
  });

  it("returns null for a malformed token", () => {
    expect(decodeJwt("not-a-jwt")).toBeNull();
    expect(decodeJwt("a.b")).toBeNull();
  });

  it("extractRole returns the first role", () => {
    const payload = { roles: ["ADMIN", "MEMBER"] } as JwtPayload;
    expect(extractRole(payload)).toBe("ADMIN");
  });

  it("isExpired is true when exp is in the past", () => {
    const past = { exp: Math.floor(Date.now() / 1000) - 60 } as JwtPayload;
    const future = { exp: Math.floor(Date.now() / 1000) + 60 } as JwtPayload;
    expect(isExpired(past)).toBe(true);
    expect(isExpired(future)).toBe(false);
  });
});
