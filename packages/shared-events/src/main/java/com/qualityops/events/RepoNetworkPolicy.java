package com.qualityops.events;

/** Network exposure of the framework container. {@code ISOLATED} runs with no
 *  network at all; {@code EGRESS} attaches an outbound-only bridge with no route
 *  to the platform's internal services. */
public enum RepoNetworkPolicy { ISOLATED, EGRESS }
