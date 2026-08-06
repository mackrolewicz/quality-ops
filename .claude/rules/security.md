---
paths:
  - "**/security/**/*"
  - "**/auth/**/*"
  - "**/identity/**/*"
  - "**/config/Security*"
  - "**/filter/**/*"
  - "**/SecurityConfig*"
  - "**/JwtFilter*"
  - "**/OAuth*"
  - "**/Mfa*"
  - "**/mfa/**/*"
  - "**/token*"
---
# Security Rules

- JWT access tokens: 15-minute expiry. Refresh tokens: 7 days, HttpOnly cookie.
- Never store tokens in localStorage. Use HttpOnly, Secure, SameSite cookies.
- Passwords hashed with bcrypt or argon2. Never MD5 or SHA.
- RBAC: Owner > Admin > Member > Viewer. Check both role AND org_id.
- `orgId` ALWAYS comes from the JWT, never from request body or URL.
- API tokens: hashed in DB (never plain text), scoped, revocable, prefixed `qt_`.
- Never log tokens, passwords, secrets, or full request bodies.
- Set security headers: HSTS, CSP, X-Content-Type-Options, X-Frame-Options.
- CORS: explicit origins only, never wildcard `*` in production.
- Input validation: `@Valid`, `@NotBlank`, `@Size` on all inputs.
- Parameterized queries only. No string concatenation in SQL.
- Failed login: generic error message, never reveal if email exists.
- Audit log all auth events: login, logout, failed attempts, role changes.
- MFA (Phase 4): never log OTP codes; rate-limit send/verify; SSO still requires MFA if enabled.
