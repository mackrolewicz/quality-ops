package com.qualityops.worker.execution.adapter.out.runner;

import java.io.ByteArrayOutputStream;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Flow;

/**
 * Streaming {@link HttpResponse.BodySubscriber} that retains at most
 * {@code maxRetainBytes} of the response body and then cancels the transfer.
 * The complete response is never held in memory: each delivered buffer is
 * copied into a bounded buffer up to the cap, counted, and discarded. Once the
 * cap is reached the upstream {@link Flow.Subscription} is cancelled so the
 * remainder is not pulled over the socket.
 *
 * <p>{@link BoundedBody#truncated()} is {@code true} when the body exceeded the
 * cap; {@link BoundedBody#totalBytes()} is the exact size when not truncated, or
 * the number of bytes seen before cancellation when truncated (i.e. a lower
 * bound).
 */
final class BoundedBodySubscriber implements HttpResponse.BodySubscriber<BoundedBodySubscriber.BoundedBody> {

    record BoundedBody(byte[] retained, long totalBytes, boolean truncated) {}

    private final int maxRetainBytes;
    private final ByteArrayOutputStream retained;
    private final CompletableFuture<BoundedBody> result = new CompletableFuture<>();

    private Flow.Subscription subscription;
    private long totalBytes;
    private boolean truncated;

    BoundedBodySubscriber(long maxRetainBytes) {
        this.maxRetainBytes = (int) Math.max(0L, Math.min(maxRetainBytes, Integer.MAX_VALUE));
        this.retained = new ByteArrayOutputStream(Math.min(this.maxRetainBytes, 8 * 1024));
    }

    @Override
    public CompletionStage<BoundedBody> getBody() {
        return result;
    }

    @Override
    public void onSubscribe(Flow.Subscription s) {
        this.subscription = s;
        s.request(Long.MAX_VALUE);
    }

    @Override
    public void onNext(List<ByteBuffer> buffers) {
        for (ByteBuffer buf : buffers) {
            int available = buf.remaining();
            totalBytes += available;
            int room = maxRetainBytes - retained.size();
            if (room > 0) {
                int take = Math.min(room, available);
                byte[] chunk = new byte[take];
                buf.get(chunk);
                retained.writeBytes(chunk);
                if (take < available) {
                    truncated = true;
                }
            } else {
                truncated = true;
            }
        }
        if (truncated) {
            subscription.cancel();          // stop pulling the rest over the wire
            complete();
        }
    }

    @Override
    public void onError(Throwable t) {
        result.completeExceptionally(t);
    }

    @Override
    public void onComplete() {
        complete();
    }

    private void complete() {
        // CompletableFuture.complete is idempotent — safe after cancel() + onComplete race.
        result.complete(new BoundedBody(retained.toByteArray(), totalBytes, truncated));
    }
}
