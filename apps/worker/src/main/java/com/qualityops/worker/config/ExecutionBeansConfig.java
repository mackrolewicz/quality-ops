package com.qualityops.worker.config;

import com.qualityops.worker.execution.application.service.Sleeper;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.Random;
import java.util.random.RandomGenerator;

/**
 * Supplies the two seams the execution simulation needs — a random source and a
 * sleep primitive — as beans so tests can substitute deterministic stand-ins.
 */
@Configuration
public class ExecutionBeansConfig {

    @Bean
    RandomGenerator executionRandomGenerator() {
        return new Random();
    }

    @Bean
    Sleeper sleeper() {
        return Thread::sleep;
    }
}
