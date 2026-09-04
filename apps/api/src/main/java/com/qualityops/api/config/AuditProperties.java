package com.qualityops.api.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** ADR-008 §7. Bound from {@code qualityops.audit.*}. Auto-registered by
 *  @ConfigurationPropertiesScan on QualityOpsApplication. */
@ConfigurationProperties("qualityops.audit")
public record AuditProperties(boolean enabled) {}
