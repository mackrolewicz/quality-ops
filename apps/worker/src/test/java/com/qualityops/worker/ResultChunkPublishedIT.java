package com.qualityops.worker;

import com.qualityops.events.RunRequestedEvent;
import com.qualityops.events.TestCaseSnapshotItem;
import com.qualityops.worker.support.AbstractWorkerKafkaPostgresIT;
import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.test.EmbeddedKafkaBroker;
import org.springframework.kafka.test.utils.KafkaTestUtils;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.random.RandomGenerator;

import static java.util.concurrent.TimeUnit.SECONDS;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.Mockito.when;

class ResultChunkPublishedIT extends AbstractWorkerKafkaPostgresIT {

    private static final String REQUESTED = "runs.requested";
    private static final String COMPLETED = "runs.completed";
    private static final String CHUNK = "results.chunk";

    @Autowired private KafkaTemplate<String, Object> kafkaTemplate;
    @Autowired private EmbeddedKafkaBroker broker;

    @MockBean private RandomGenerator random;

    private Consumer<String, String> observer;

    @BeforeEach
    void subscribe() {
        when(random.nextInt(100)).thenReturn(10);   // deterministic PASSED
        var props = KafkaTestUtils.consumerProps(
            broker.getBrokersAsString(), "chunk-observer-" + UUID.randomUUID(), "true");
        props.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        props.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        props.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        observer = new KafkaConsumer<>(props);
        observer.subscribe(List.of(COMPLETED, CHUNK));
    }

    @AfterEach
    void close() {
        observer.close();
    }

    @Test
    void eachCaseEmitsExactlyOneChunk_andTerminalCarriesEpochAndArtifacts() {
        var runId = UUID.randomUUID();
        var cases = List.of(
            new TestCaseSnapshotItem(UUID.randomUUID(), "case-0", 0),
            new TestCaseSnapshotItem(UUID.randomUUID(), "case-1", 1));

        kafkaTemplate.send(REQUESTED, runId.toString(), new RunRequestedEvent(
            UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), runId, UUID.randomUUID(),
            Instant.now(), RunRequestedEvent.SCHEMA_VERSION,
            UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), cases));

        var seen = new ArrayList<ConsumerRecord<String, String>>();
        await().atMost(25, SECONDS).untilAsserted(() -> {
            observer.poll(Duration.ofMillis(300)).forEach(seen::add);
            assertThat(count(seen, CHUNK, runId)).isEqualTo(2);
            assertThat(count(seen, COMPLETED, runId)).isEqualTo(1);
        });

        // No extra chunks after settle.
        await().during(3, SECONDS).atMost(9, SECONDS).untilAsserted(() -> {
            observer.poll(Duration.ofMillis(300)).forEach(seen::add);
            assertThat(count(seen, CHUNK, runId)).isEqualTo(2);
        });

        var chunks = values(seen, CHUNK, runId);
        chunks.forEach(c -> {
            assertThat(c).contains("\"attemptEpoch\":0");
            assertThat(c).contains("\"artifacts\":[]");
            assertThat(c).contains("\"verdict\":\"PASSED\"");
        });
        cases.forEach(item -> assertThat(chunks.stream().anyMatch(
            c -> c.contains("\"testCaseId\":\"" + item.testCaseId() + "\""))).isTrue());

        var terminal = values(seen, COMPLETED, runId).get(0);
        assertThat(terminal).contains("\"schemaVersion\":4");
        assertThat(terminal).contains("\"attemptEpoch\":0");
        assertThat(terminal).contains("\"artifacts\":[]");
    }

    private long count(List<ConsumerRecord<String, String>> records, String topic, UUID runId) {
        return records.stream()
            .filter(r -> r.topic().equals(topic) && runId.toString().equals(r.key()))
            .count();
    }

    private List<String> values(List<ConsumerRecord<String, String>> records, String topic, UUID runId) {
        return records.stream()
            .filter(r -> r.topic().equals(topic) && runId.toString().equals(r.key()))
            .map(ConsumerRecord::value)
            .toList();
    }
}
