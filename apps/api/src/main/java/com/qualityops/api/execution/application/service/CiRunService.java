package com.qualityops.api.execution.application.service;

import com.qualityops.api.execution.application.port.in.EnqueueRunUseCase;
import com.qualityops.api.execution.application.port.in.GetRunUseCase;
import com.qualityops.api.execution.application.port.in.SubmitCiRunUseCase;
import com.qualityops.api.execution.application.port.out.CiIdempotencyRepository;
import com.qualityops.api.execution.domain.RunPriority;
import com.qualityops.api.execution.domain.RunSource;
import com.qualityops.api.execution.dto.CreateRunRequest;
import com.qualityops.api.execution.dto.RunResponse;
import com.qualityops.api.execution.exception.IdempotencyKeyConflictException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.UUID;

/** ADR-007 §5.2. NOT class-{@code @Transactional}: one {@link TransactionTemplate}
 *  unit for the create (enqueue + insert mapping), plain reads outside. The
 *  {@code UNIQUE (org_id, idempotency_key)} constraint is the race arbiter —
 *  catch {@code DataIntegrityViolationException} and re-read. */
@Service
public class CiRunService implements SubmitCiRunUseCase {

    private static final Logger log = LoggerFactory.getLogger(CiRunService.class);

    private final EnqueueRunUseCase enqueueRunUseCase;
    private final GetRunUseCase getRunUseCase;
    private final CiIdempotencyRepository ciIdempotencyRepository;
    private final TransactionTemplate txTemplate;

    public CiRunService(EnqueueRunUseCase enqueueRunUseCase,
                        GetRunUseCase getRunUseCase,
                        CiIdempotencyRepository ciIdempotencyRepository,
                        PlatformTransactionManager transactionManager) {
        this.enqueueRunUseCase = enqueueRunUseCase;
        this.getRunUseCase = getRunUseCase;
        this.ciIdempotencyRepository = ciIdempotencyRepository;
        this.txTemplate = new TransactionTemplate(transactionManager);
    }

    @Override
    public RunResponse submit(String idempotencyKey, CreateRunRequest body, UUID orgId, UUID userId) {
        String fp = fingerprint(body);

        var hit = ciIdempotencyRepository.find(orgId, idempotencyKey).orElse(null);
        if (hit != null) {
            if (!hit.requestFingerprint().equals(fp)) {
                throw new IdempotencyKeyConflictException(idempotencyKey);
            }
            return getRunUseCase.get(hit.runId(), orgId);
        }

        try {
            return txTemplate.execute(status -> {
                var r = enqueueRunUseCase.enqueue(new EnqueueRunUseCase.EnqueueRunCommand(orgId,
                    body.projectId(), body.suiteId(), body.environmentId(), userId,
                    RunPriority.fromNullable(body.priority()), RunSource.MANUAL, null));
                ciIdempotencyRepository.insert(orgId, idempotencyKey, fp, r.runId());
                return getRunUseCase.get(r.runId(), orgId);
            });
        } catch (DataIntegrityViolationException race) {
            // A concurrent first-call committed first — the whole unit above
            // rolled back (no orphan run). Re-read and return the winner.
            log.debug("Idempotency-Key {} lost the first-call race — re-reading winner", idempotencyKey);
            var winner = ciIdempotencyRepository.find(orgId, idempotencyKey).orElseThrow();
            if (!winner.requestFingerprint().equals(fp)) {
                throw new IdempotencyKeyConflictException(idempotencyKey);
            }
            return getRunUseCase.get(winner.runId(), orgId);
        }
    }

    static String fingerprint(CreateRunRequest b) {
        String canonical = b.projectId() + "|" + b.suiteId() + "|" + b.environmentId() + "|"
            + (b.priority() == null ? "NORMAL" : b.priority());
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(md.digest(canonical.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }
}
