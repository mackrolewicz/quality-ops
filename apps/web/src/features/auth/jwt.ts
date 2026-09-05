import type { JwtPayload, Role } from "../../types/auth";

const ROLES: readonly Role[] = ["OWNER", "ADMIN", "MEMBER", "VIEWER"];

function base64UrlDecode(segment: string): string {
  const padded = segment.replace(/-/g, "+").replace(/_/g, "/");
  const pad = padded.length % 4 === 0 ? "" : "=".repeat(4 - (padded.length % 4));
  return atob(padded + pad);
}

export function decodeJwt(token: string): JwtPayload | null {
  const parts = token.split(".");
  if (parts.length !== 3) return null;
  try {
    const json = base64UrlDecode(parts[1]);
    const parsed = JSON.parse(json) as Partial<JwtPayload>;
    if (
      typeof parsed.sub !== "string" ||
      typeof parsed.org_id !== "string" ||
      !Array.isArray(parsed.roles) ||
      typeof parsed.exp !== "number"
    ) {
      return null;
    }
    return {
      sub: parsed.sub,
      org_id: parsed.org_id,
      roles: parsed.roles,
      iat: typeof parsed.iat === "number" ? parsed.iat : 0,
      exp: parsed.exp,
    };
  } catch {
    return null;
  }
}

export function extractRole(payload: JwtPayload): Role {
  const first = payload.roles[0];
  return ROLES.includes(first as Role) ? (first as Role) : "VIEWER";
}

export function isExpired(payload: JwtPayload, skewSeconds = 0): boolean {
  return payload.exp * 1000 <= Date.now() + skewSeconds * 1000;
}
