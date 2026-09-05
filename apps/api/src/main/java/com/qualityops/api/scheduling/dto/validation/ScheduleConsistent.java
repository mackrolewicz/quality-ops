package com.qualityops.api.scheduling.dto.validation;

import jakarta.validation.Constraint;
import jakarta.validation.Payload;

import java.lang.annotation.Documented;
import java.lang.annotation.Retention;
import java.lang.annotation.Target;

import static java.lang.annotation.ElementType.TYPE;
import static java.lang.annotation.RetentionPolicy.RUNTIME;

/** Cross-field: RECURRING => cron + tz set, fireAt null; ONE_TIME => fireAt set
 *  (future) and cron/tz null. */
@Documented
@Constraint(validatedBy = ScheduleConsistentValidator.class)
@Target(TYPE)
@Retention(RUNTIME)
public @interface ScheduleConsistent {

    String message() default "schedule fields are inconsistent for its kind";

    Class<?>[] groups() default {};

    Class<? extends Payload>[] payload() default {};
}
