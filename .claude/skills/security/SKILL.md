---
name: security
description: Use this skill when implementing or reviewing authentication, authorization, API security, TLS/HTTPS, OAuth/SSO, CORS, rate limiting, input validation, and OWASP compliance. Covers the full security model for this platform.
---

# Security patterns

This skill is the source of truth for how security is implemented in this repo.

## 1. Authentication model

### Phase 1: JWT (local auth)

Simple JWT-based auth for the MVP. Users are stored in Postgres.

```
Client                    Gateway                    API
  │                         │                         │
  ├─ POST /auth/login ─────►│────────────────────────►│
  │  { email, password }    │                         │
  │                         │                    verify password
  │                         │                    generate JWT
  │◄─ { access_token, ─────┤◄────────────────────────┤
  │    refresh_token }      │                         │
  │                         │                         │
  ├─ GET /api/v1/projects ─►│                         │
  │  Authorization: Bearer  │   validate JWT          │
  │                         │   extract orgId, roles  │
  │                         │──────────────────►      │
  │                         │   forward with          │
  │                         │   X-User-Id,            │
  │                         │   X-Org-Id headers      │
```

**JWT structure:**
```json
{
  "sub": "user-uuid",
  "org_id": "org-uuid",
  "roles": ["ADMIN"],
  "iat": 1700000000,
  "exp": 1700003600
}
```

**Token rules:**
- Access token: short-lived (15 minutes).
- Refresh token: longer-lived (7 days), stored in HttpOnly cookie.
- Refresh tokens are stored in DB and revocable.
- Never store tokens in localStorage — use HttpOnly, Secure, SameSite cookies.

### Phase 4: OAuth 2.0 / OIDC (SSO)

Replace local auth with OAuth 2.0 Authorization Code flow + PKCE.

```
Browser → QualityOps → Identity Provider (GitHub/Google/Azure AD)
  │                        │
  ├─ /auth/login ─────────►│
  │                        │
  │◄─ redirect to IdP ────┤
  │                        │
  ├─ login at IdP ────────►│  (GitHub, Google, Azure AD)
  │                        │
  │◄─ redirect back ──────┤  with authorization code
  │   /auth/callback?code= │
  │                        │
  ├─ POST /auth/token ────►│  exchange code for tokens
  │                        │
  │◄─ { access_token } ───┤  create local session
```

**Supported providers (Phase 4+):**

| Provider | Protocol | Use case |
|---|---|---|
| GitHub OAuth | OAuth 2.0 | Developer login, repo access |
| Google | OIDC | Enterprise SSO |
| Azure AD / Entra ID | OIDC | Enterprise SSO, AKS integration |
| Custom SAML | SAML 2.0 | Enterprise (later, if needed) |

**SSO + local auth:** OAuth/SSO does not replace MFA — users with 2FA enabled
still complete a second step after the IdP returns (issue a short-lived
`mfa_pending` token until OTP/TOTP is verified).

### Phase 4: Two-factor authentication (2FA / MFA)

Add a second factor after password login or after SSO. Required for
security-conscious orgs; optional per user until org policy enforces it.

```
Login (password or SSO) → mfa_pending JWT (5 min, limited scope)
  → POST /auth/mfa/challenge (send email or SMS OTP)
  → POST /auth/mfa/verify { code }
  → full access JWT + refresh token
```

**Supported methods:**

| Method | Implementation | Local dev |
|---|---|---|
| Email OTP | 6-digit code, `OtpService` + Spring Mail | Mailhog in Docker Compose |
| SMS OTP | Twilio Programmable SMS | Twilio test credentials / magic numbers |
| TOTP (optional) | RFC 6238, enroll via QR | Google Authenticator, Authy |

**Data model (add in Phase 4 migration):**

```sql
-- user_mfa_methods: user_id, method (EMAIL|SMS|TOTP), secret_or_phone, enabled, enrolled_at
-- mfa_otp_attempts: rate limit + audit (optional separate table or Redis)
```

**Security rules:**
- Never log OTP codes or TOTP secrets.
- Store only hashed OTPs if persisted (prefer generate-verify-discard in Redis with TTL).
- Rate limit: max 3 sends per 15 min, max 5 verify failures → lockout (reuse brute-force).
- Backup codes for TOTP: generate 10 one-time codes, store bcrypt hashes.
- Org policy (later): `require_mfa` flag forces enroll before full access.

**Spring Security configuration:**

```java
@Configuration
@EnableWebSecurity
public class SecurityConfig {
    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
        return http
            .csrf(csrf -> csrf.csrfTokenRepository(
                CookieCsrfTokenRepository.withHttpOnlyFalse()))
            .cors(cors -> cors.configurationSource(corsConfig()))
            .sessionManagement(session ->
                session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            .authorizeHttpRequests(auth -> auth
                .requestMatchers("/actuator/health/**").permitAll()
                .requestMatchers("/auth/**").permitAll()
                .requestMatchers("/api/**").authenticated()
            )
            .oauth2ResourceServer(oauth2 ->
                oauth2.jwt(jwt -> jwt.jwtAuthenticationConverter(jwtConverter())))
            .build();
    }
}
```

## 2. Authorization (RBAC)

### Role model

| Role | Permissions |
|---|---|
| `OWNER` | Everything, including org settings and billing |
| `ADMIN` | Manage users, projects, environments, API tokens |
| `MEMBER` | Create/run tests, view results, manage own data |
| `VIEWER` | Read-only access to dashboards and results |

### Enforcement pattern

```java
@PreAuthorize("hasRole('ADMIN') or hasRole('OWNER')")
@PostMapping
public ProjectResponse create(@Valid @RequestBody CreateProjectRequest request,
                               @AuthenticationPrincipal UserPrincipal user) {
    return service.create(request, user.orgId());
}

@PreAuthorize("hasAnyRole('MEMBER', 'ADMIN', 'OWNER')")
@GetMapping
public List<ProjectResponse> list(@AuthenticationPrincipal UserPrincipal user) {
    return service.listByOrg(user.orgId());
}
```

**Hard rules:**
- Always check both role AND org_id. A user with ADMIN role in org A cannot
  access org B's data.
- Never rely on frontend role checks alone — always enforce on the backend.
- Use `@PreAuthorize` on controller methods, not services.

### API token authentication

For CI/CD integration, users create scoped API tokens:

```
POST /api/v1/tokens
{
  "name": "GitHub Actions",
  "scopes": ["runs:write", "results:read"],
  "expires_in_days": 90
}
```

Tokens are hashed (bcrypt/argon2) in the database — never stored in plain text.
Include in requests as: `Authorization: Bearer qt_<token>`.

## 3. TLS / HTTPS

### Local development
- Frontend (Vite): HTTP on localhost is fine for dev.
- API / Gateway: HTTP internally. TLS terminates at the gateway in production.

### Production (AKS)

```
Internet → Azure Load Balancer (TLS termination)
         → Ingress Controller (HTTPS with cert-manager)
         → Gateway (HTTP internally in the cluster)
         → API / Worker (HTTP, cluster-internal only)
```

**TLS configuration:**
- Minimum TLS 1.2, prefer TLS 1.3.
- Use cert-manager with Let's Encrypt for automatic certificate renewal.
- HSTS header: `Strict-Transport-Security: max-age=31536000; includeSubDomains`.
- Internal cluster traffic: mTLS via service mesh (Istio/Linkerd, later phases).

**Spring Boot TLS (if terminating at app level):**

```yaml
server:
  ssl:
    enabled: true
    key-store: classpath:keystore.p12
    key-store-type: PKCS12
    key-store-password: ${SSL_KEYSTORE_PASSWORD}
    protocol: TLS
    enabled-protocols: TLSv1.3,TLSv1.2
```

### Certificate management

| Environment | Strategy |
|---|---|
| Local | No TLS (localhost) |
| Staging | Let's Encrypt via cert-manager |
| Production | Let's Encrypt or enterprise CA via cert-manager |

## 4. Rate limiting

### Gateway-level rate limiting

Spring Cloud Gateway rate limiter with Redis backend:

```yaml
spring:
  cloud:
    gateway:
      routes:
        - id: api-route
          uri: lb://qualityops-api
          predicates:
            - Path=/api/**
          filters:
            - name: RequestRateLimiter
              args:
                redis-rate-limiter:
                  replenishRate: 50      # requests per second
                  burstCapacity: 100     # max burst
                  requestedTokens: 1
                key-resolver: "#{@apiKeyResolver}"
```

### Rate limit tiers

| Tier | Requests/min | Burst | Who |
|---|---|---|---|
| Free | 60 | 100 | Default for all users |
| Pro | 600 | 1000 | Paid tier |
| API token | 300 | 500 | CI/CD automation |
| Internal | Unlimited | — | Service-to-service |

### Rate limit headers (returned on every response)

```
X-RateLimit-Limit: 60
X-RateLimit-Remaining: 42
X-RateLimit-Reset: 1700000060
Retry-After: 30          (only on 429)
```

### Application-level rate limiting

For expensive operations (triggering runs, AI analysis):

```java
@Service
public class RateLimitService {
    private final StringRedisTemplate redis;

    public void checkRunLimit(UUID orgId) {
        String key = "rate:runs:" + orgId;
        Long count = redis.opsForValue().increment(key);
        if (count == 1) {
            redis.expire(key, Duration.ofHours(1));
        }
        if (count > 100) {
            throw new RateLimitExceededException(
                "Run limit exceeded: 100 runs per hour per organization");
        }
    }
}
```

## 5. CORS (Cross-Origin Resource Sharing)

```java
@Bean
public CorsConfigurationSource corsConfig() {
    var config = new CorsConfiguration();
    config.setAllowedOrigins(List.of(
        "http://localhost:5173",          // Vite dev server
        "https://qualityops.example.com"  // production
    ));
    config.setAllowedMethods(List.of("GET", "POST", "PUT", "DELETE", "OPTIONS"));
    config.setAllowedHeaders(List.of("Authorization", "Content-Type", "X-Request-Id"));
    config.setAllowCredentials(true);
    config.setMaxAge(3600L);

    var source = new UrlBasedCorsConfigurationSource();
    source.registerCorsConfiguration("/api/**", config);
    return source;
}
```

**Rules:**
- Never use `*` for allowed origins in production.
- Always list specific origins.
- `allowCredentials(true)` is required for cookie-based auth.

## 6. Input validation and injection prevention

### SQL injection
- Spring Data JPA parameterized queries prevent SQL injection by default.
- Never use string concatenation for queries.
- If using native SQL, always use `@Query` with named parameters:

```java
@Query("SELECT p FROM Project p WHERE p.orgId = :orgId AND p.name LIKE :name")
List<Project> search(@Param("orgId") UUID orgId, @Param("name") String name);
```

### XSS (Cross-Site Scripting)
- React escapes output by default — safe for most cases.
- Never use `dangerouslySetInnerHTML` without sanitization.
- Set CSP header: `Content-Security-Policy: default-src 'self'; script-src 'self'`.
- Sanitize user input that will be displayed as HTML (test case descriptions, etc.).

### Request validation

```java
public record CreateProjectRequest(
    @NotBlank @Size(min = 1, max = 100)
    String name,

    @Size(max = 2000)
    String description,

    @Pattern(regexp = "^[a-z0-9-]+$", message = "Slug must be lowercase alphanumeric")
    String slug
) {}
```

Always use `@Valid` on controller parameters. Never trust client input.

## 7. Security headers

Set these headers on all responses (via gateway filter or Spring Security):

```
Strict-Transport-Security: max-age=31536000; includeSubDomains; preload
Content-Security-Policy: default-src 'self'; img-src 'self' data:; style-src 'self' 'unsafe-inline'
X-Content-Type-Options: nosniff
X-Frame-Options: DENY
X-XSS-Protection: 0              (deprecated, CSP replaces it)
Referrer-Policy: strict-origin-when-cross-origin
Permissions-Policy: camera=(), microphone=(), geolocation=()
```

## 8. Secrets management

| Environment | Strategy |
|---|---|
| Local dev | `.env` file (gitignored) |
| CI (GitHub Actions) | GitHub Secrets |
| Staging / Prod (AKS) | Kubernetes Secrets + Azure Key Vault |

**Hard rules:**
- Never commit `.env` files, credentials, or API keys.
- Never log tokens, passwords, or secrets.
- Never hardcode secrets in application config — use `${ENV_VAR}`.
- Rotate API tokens and secrets on a schedule.
- Use bcrypt or argon2 for password hashing — never MD5 or SHA.

### Spring Boot secrets pattern

```yaml
# application-prod.yml
spring:
  datasource:
    url: ${DATABASE_URL}
    username: ${DATABASE_USER}
    password: ${DATABASE_PASSWORD}
  security:
    oauth2:
      client:
        registration:
          github:
            client-id: ${GITHUB_CLIENT_ID}
            client-secret: ${GITHUB_CLIENT_SECRET}
```

## 9. Audit logging

Track security-relevant events:

```java
public record AuditEvent(
    UUID id,
    UUID orgId,
    UUID userId,
    String action,       // "LOGIN", "CREATE_PROJECT", "TRIGGER_RUN", "DELETE_USER"
    String resourceType, // "project", "run", "user"
    UUID resourceId,
    String ipAddress,
    Instant timestamp
) {}
```

**What to audit:**
- Login / logout / failed login attempts
- RBAC changes (role assignments)
- Project / environment creation / deletion
- Test run triggers
- API token creation / revocation
- Any admin-level action

Store audit logs in a separate table. Never delete them. Index by `orgId + timestamp`.

## 10. OWASP Top 10 checklist

Apply to every feature:

- [ ] **A01: Broken Access Control** — org_id enforced? RBAC checked? IDOR prevented?
- [ ] **A02: Cryptographic Failures** — passwords hashed? secrets encrypted? TLS enforced?
- [ ] **A03: Injection** — parameterized queries? no eval()? input validated?
- [ ] **A04: Insecure Design** — threat model considered? rate limits in place?
- [ ] **A05: Security Misconfiguration** — default credentials removed? debug off in prod?
- [ ] **A06: Vulnerable Components** — dependencies scanned? no known CVEs?
- [ ] **A07: Authentication Failures** — brute force protection? session management correct?
- [ ] **A08: Data Integrity Failures** — deserialization safe? CI/CD pipeline secure?
- [ ] **A09: Logging Failures** — security events logged? no secrets in logs?
- [ ] **A10: SSRF** — internal URLs blocked? webhook URLs validated?

## 11. Security testing

| Test type | Tool | When |
|---|---|---|
| Dependency scanning | `mvn dependency-check:check` / `npm audit` | Every CI run |
| Container scanning | Trivy | On Docker build |
| SAST (static analysis) | SpotBugs + Find Security Bugs | CI pipeline |
| DAST (dynamic analysis) | OWASP ZAP | Staging environment |
| Penetration testing | Manual / Burp Suite | Before production launch |
| Auth testing | Integration tests | Every PR |
