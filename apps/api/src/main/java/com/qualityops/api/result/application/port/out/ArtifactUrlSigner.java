package com.qualityops.api.result.application.port.out;

import java.time.Duration;
import java.time.Instant;

/** Mints a short-TTL presigned GET URL for one object key. The only object-store
 *  capability the API needs — it never writes and never proxies bytes. Azure
 *  "user delegation SAS" replaces the S3 presign call behind this port later. */
public interface ArtifactUrlSigner {

    PresignedUrl sign(String storageKey, Duration ttl);

    record PresignedUrl(String url, Instant expiresAt) {}
}
