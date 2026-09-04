package com.qualityops.worker.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.net.http.HttpClient;

@Configuration
public class HttpClientConfig {

    @Bean
    HttpClient executionHttpClient(WorkerExecutionProperties p) {
        return HttpClient.newBuilder()
            .connectTimeout(p.connectTimeout())
            .followRedirects(p.followRedirects()
                ? HttpClient.Redirect.NORMAL : HttpClient.Redirect.NEVER)
            .proxy(HttpClient.Builder.NO_PROXY)
            .version(HttpClient.Version.HTTP_1_1)
            .build();
    }
}
