package com.qualityops.api.scheduling.dto.validation;

import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;
import org.springframework.scheduling.support.CronExpression;

import java.time.DateTimeException;
import java.time.Instant;
import java.time.ZoneId;

public class ScheduleConsistentValidator
        implements ConstraintValidator<ScheduleConsistent, ScheduleRequestView> {

    @Override
    public boolean isValid(ScheduleRequestView r, ConstraintValidatorContext ctx) {
        if (r == null || r.kind() == null) {
            return true; // @NotNull on kind reports the real problem
        }
        ctx.disableDefaultConstraintViolation();
        return switch (r.kind()) {
            case "RECURRING" -> validRecurring(r, ctx);
            case "ONE_TIME" -> validOneTime(r, ctx);
            default -> violate(ctx, "kind", "kind must be RECURRING or ONE_TIME");
        };
    }

    private boolean validRecurring(ScheduleRequestView r, ConstraintValidatorContext ctx) {
        boolean ok = true;
        if (r.fireAt() != null) {
            ok = violate(ctx, "fireAt", "fireAt must be null for a RECURRING schedule");
        }
        if (isBlank(r.cronExpression()) || !parses(r.cronExpression())) {
            ok = violate(ctx, "cronExpression", "cronExpression must be a valid 6-field Spring cron");
        }
        if (isBlank(r.timeZone()) || !zoneOk(r.timeZone())) {
            ok = violate(ctx, "timeZone", "timeZone must be a valid IANA zone id");
        }
        return ok;
    }

    private boolean validOneTime(ScheduleRequestView r, ConstraintValidatorContext ctx) {
        boolean ok = true;
        if (r.cronExpression() != null || r.timeZone() != null) {
            ok = violate(ctx, "cronExpression",
                "cronExpression/timeZone must be null for a ONE_TIME schedule");
        }
        if (r.fireAt() == null || !r.fireAt().isAfter(Instant.now())) {
            ok = violate(ctx, "fireAt", "fireAt must be set and in the future for a ONE_TIME schedule");
        }
        return ok;
    }

    private static boolean parses(String cron) {
        try {
            CronExpression.parse(cron);
            return true;
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    private static boolean zoneOk(String z) {
        try {
            ZoneId.of(z);
            return true;
        } catch (DateTimeException e) {
            return false;
        }
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }

    private static boolean violate(ConstraintValidatorContext c, String field, String msg) {
        c.buildConstraintViolationWithTemplate(msg).addPropertyNode(field).addConstraintViolation();
        return false;
    }
}
