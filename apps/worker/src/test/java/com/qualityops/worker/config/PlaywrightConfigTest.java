package com.qualityops.worker.config;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.util.concurrent.ExecutorService;

import static org.assertj.core.api.Assertions.assertThat;

@Timeout(5)
class PlaywrightConfigTest {

    @Test
    void playwrightExecutor_runsEveryTaskOnTheSameConfinedThread() throws Exception {
        ExecutorService executor = new PlaywrightConfig().playwrightExecutor();
        try {
            String first = executor.submit(() -> Thread.currentThread().getName()).get();
            String second = executor.submit(() -> Thread.currentThread().getName()).get();

            assertThat(first).isEqualTo(second);
            assertThat(first).startsWith("playwright-");
        } finally {
            executor.shutdownNow();
        }
    }
}
