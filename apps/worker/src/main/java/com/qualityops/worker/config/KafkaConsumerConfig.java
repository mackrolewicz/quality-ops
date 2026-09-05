package com.qualityops.worker.config;

import org.apache.kafka.clients.admin.NewTopic;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.TopicBuilder;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.listener.DeadLetterPublishingRecoverer;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.util.backoff.FixedBackOff;

/**
 * Sends messages that repeatedly fail consumer processing to a
 * {@code <topic>.DLT} dead-letter topic after a short number of retries,
 * per the kafka-events dead-letter-topic rule. Spring Boot's
 * auto-configured {@code ConcurrentKafkaListenerContainerFactory} picks up
 * this bean automatically as the container factory's common error handler.
 */
@Configuration
public class KafkaConsumerConfig {

    private static final long RETRY_INTERVAL_MS = 1000L;
    private static final long RETRY_ATTEMPTS = 3L;

    @Bean
    public DefaultErrorHandler kafkaErrorHandler(KafkaTemplate<String, Object> kafkaTemplate) {
        var recoverer = new DeadLetterPublishingRecoverer(kafkaTemplate);
        return new DefaultErrorHandler(recoverer, new FixedBackOff(RETRY_INTERVAL_MS, RETRY_ATTEMPTS));
    }

    /** Declared so non-auto-create brokers still get the per-case streaming topic (ADR-005 §2.1). */
    @Bean
    public NewTopic resultsChunkTopic() {
        return TopicBuilder.name("results.chunk").build();
    }

    /** Dead-letter topic for the {@code runs.cancel} consumer (ADR-006 §5.3). */
    @Bean
    public NewTopic runsCancelDltTopic() {
        return TopicBuilder.name("runs.cancel.DLT").build();
    }
}
