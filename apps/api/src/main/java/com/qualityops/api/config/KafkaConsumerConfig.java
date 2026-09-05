package com.qualityops.api.config;

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

    /** Declared so non-auto-create brokers have the per-case streaming topic and
     *  its dead-letter topic (ADR-005 §2.1). The {@code DeadLetterPublishingRecoverer}
     *  routes failures to {@code results.chunk.DLT} by its default resolver. */
    @Bean
    public NewTopic resultsChunkTopic() {
        return TopicBuilder.name("results.chunk").build();
    }

    @Bean
    public NewTopic resultsChunkDltTopic() {
        return TopicBuilder.name("results.chunk.DLT").build();
    }

    /** Cancel command topic (ADR-006 §5.3). The API produces here; the Worker
     *  consumes (group {@code worker-execution}) into its CancellationRegistry. */
    @Bean
    public NewTopic runsCancelTopic() {
        return TopicBuilder.name("runs.cancel").build();
    }

    @Bean
    public NewTopic runsCancelDltTopic() {
        return TopicBuilder.name("runs.cancel.DLT").build();
    }
}
