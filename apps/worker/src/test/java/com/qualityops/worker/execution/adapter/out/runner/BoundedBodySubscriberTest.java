package com.qualityops.worker.execution.adapter.out.runner;

import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;
import java.util.List;
import java.util.concurrent.Flow;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Proves the subscriber never accumulates more than the cap even when fed far
 * more data than would fit in memory if buffered whole — the same 1 MiB buffer
 * is re-offered 200 times (≈200 MiB of logical body) without ever allocating it.
 */
class BoundedBodySubscriberTest {

    private static final int CAP = 1_048_576;

    @Test
    void retainsAtMostCap_marksTruncated_andCancelsUpstream_whenBodyExceedsCap() {
        var sub = new BoundedBodySubscriber(CAP);
        var cancelled = new AtomicBoolean(false);
        var requested = new AtomicLong(0);
        sub.onSubscribe(new Flow.Subscription() {
            @Override public void request(long n) { requested.addAndGet(n); }
            @Override public void cancel() { cancelled.set(true); }
        });

        byte[] oneMiB = new byte[CAP];
        for (int i = 0; i < 200 && !cancelled.get(); i++) {
            sub.onNext(List.of(ByteBuffer.wrap(oneMiB)));
        }

        var body = sub.getBody().toCompletableFuture().getNow(null);
        assertThat(body).isNotNull();
        assertThat(body.retained().length).isLessThanOrEqualTo(CAP);
        assertThat(body.truncated()).isTrue();
        assertThat(cancelled).as("upstream transfer cancelled once cap reached").isTrue();
        assertThat(requested.get()).isPositive();
    }

    @Test
    void retainsWholeBody_exactCount_notTruncated_whenUnderCap() {
        var sub = new BoundedBodySubscriber(CAP);
        sub.onSubscribe(noopSubscription());

        sub.onNext(List.of(ByteBuffer.wrap(new byte[10]), ByteBuffer.wrap(new byte[5])));
        sub.onComplete();

        var body = sub.getBody().toCompletableFuture().getNow(null);
        assertThat(body.retained().length).isEqualTo(15);
        assertThat(body.totalBytes()).isEqualTo(15L);
        assertThat(body.truncated()).isFalse();
    }

    @Test
    void onError_propagatesToBodyStage() {
        var sub = new BoundedBodySubscriber(CAP);
        sub.onSubscribe(noopSubscription());

        sub.onError(new java.io.IOException("boom"));

        assertThat(sub.getBody().toCompletableFuture()).isCompletedExceptionally();
    }

    private static Flow.Subscription noopSubscription() {
        return new Flow.Subscription() {
            @Override public void request(long n) { }
            @Override public void cancel() { }
        };
    }
}
