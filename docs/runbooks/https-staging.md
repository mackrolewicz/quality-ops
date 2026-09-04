# Runbook: HTTPS in staging

Realises ADR-008 §8. Scope is **config + docs**; Kubernetes/Helm ingress TLS is
Phase 5.

## 1. Recommended — terminate TLS at the load balancer / ingress

Azure Application Gateway or nginx-ingress terminates TLS with a Let's Encrypt
cert (cert-manager) and forwards plain HTTP to the gateway on the pod network.

- Set `GATEWAY_TLS_ENABLED=false`, keep `SERVER_PORT=8090`.
- HSTS is still emitted by the gateway on every response
  (`spring.cloud.gateway.filter.secure-headers.strict-transport-security:
  max-age=31536000; includeSubDomains`), so clients are pinned to HTTPS at the
  edge.
- Nothing else to do — the `staging` profile's `server.ssl.*` block stays
  disabled.

## 2. LB-less staging / local-staging parity — gateway terminates TLS

The `staging` Spring profile (`apps/gateway/src/main/resources/application-staging.yml`)
enables `server.ssl.*` from **environment variables only**. No keystore is
committed.

### Generate a keystore

Self-signed (staging):

```bash
keytool -genkeypair \
  -alias qualityops-gateway -keyalg RSA -keysize 2048 -validity 365 \
  -storetype PKCS12 -keystore keystore.p12 \
  -dname "CN=staging.qualityops.local" \
  -storepass "$GATEWAY_TLS_KEYSTORE_PASSWORD"
```

Locally-trusted (developer machine): `mkcert -pkcs12 -p12-file keystore.p12 staging.qualityops.local`.

### Run

```bash
export SPRING_PROFILES_ACTIVE=staging
export GATEWAY_TLS_ENABLED=true
export GATEWAY_TLS_KEYSTORE=file:/etc/qualityops/tls/keystore.p12   # mount the file here
export GATEWAY_TLS_KEYSTORE_PASSWORD=<the storepass used above>
export GATEWAY_TLS_KEY_ALIAS=qualityops-gateway
export SERVER_PORT=8443
```

No Dockerfile change is required — the profile is inert unless
`SPRING_PROFILES_ACTIVE=staging`.

## 3. Manual verification

```bash
# 200 over TLS
curl -vk https://localhost:8443/actuator/health

# certificate validity window
openssl s_client -connect localhost:8443 -servername staging.qualityops.local </dev/null \
  | openssl x509 -noout -dates

# HSTS header present (emitted by the gateway, not the TLS layer)
curl -skI https://localhost:8443/actuator/health | grep -i strict-transport-security
```

## 4. Test impact

The existing gateway tests run with the **default** profile (TLS off) and are
unaffected. `GatewayStagingProfileIT` boots the `staging` profile with
`GATEWAY_TLS_ENABLED=false` and only asserts the context starts.
