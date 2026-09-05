package com.qualityops.worker.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicInteger;

/** Playwright-Java is NOT thread-safe: every Playwright/Browser/Context/Page call
 *  must happen on one dedicated thread. This bean is that thread. */
@Configuration
public class PlaywrightConfig {

    private static final Logger log = LoggerFactory.getLogger(PlaywrightConfig.class);

    @Bean(destroyMethod = "shutdownNow")
    ExecutorService playwrightExecutor() {
        var seq = new AtomicInteger();
        ThreadFactory tf = r -> {
            var t = new Thread(r, "playwright-" + seq.incrementAndGet());
            t.setDaemon(true);
            t.setUncaughtExceptionHandler((thr, ex) ->
                log.error("Uncaught error on {}", thr.getName(), ex));
            return t;
        };
        return Executors.newSingleThreadExecutor(tf);
    }
}
