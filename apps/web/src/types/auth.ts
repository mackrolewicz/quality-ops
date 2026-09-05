export type Role = "OWNER" | "ADMIN" | "MEMBER" | "VIEWER";

export interface JwtPayload {
  sub: string;
  org_id: string;
  roles: string[];
  iat: number;
  exp: number;
}

export interface AuthTokens {
  accessToken: string;
  refreshToken: string;
}

export interface AuthUser {
  userId: string;
  orgId: string;
  role: Role;
  email: string;
}
