package com.qualityops.api.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.EnableAspectJAutoProxy;

/**
 * Belt-and-braces: Spring Boot already auto-configures AspectJ auto-proxying
 * when {@code spring-boot-starter-aop} is on the classpath. Making it explicit
 * documents the dependency for {@code AuditAspect} / {@code TimingAspect}
 * (ADR-008 &sect;7) and guards against a future auto-config exclusion.
 */
@Configuration
@EnableAspectJAutoProxy
public class AopConfig {
}
