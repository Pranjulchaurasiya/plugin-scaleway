package io.kestra.plugin.scaleway.genai;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.kestra.core.http.HttpRequest;
import io.kestra.core.http.HttpResponse;
import io.kestra.core.http.client.HttpClient;
import io.kestra.core.http.client.HttpClientResponseException;
import io.kestra.core.http.client.configurations.HttpConfiguration;
import io.kestra.core.http.client.configurations.TimeoutConfiguration;
import io.kestra.core.models.annotations.PluginProperty;
import io.kestra.core.models.property.Property;
import io.kestra.core.runners.RunContext;
import io.kestra.core.serializers.JacksonMapper;
import io.kestra.plugin.scaleway.AbstractScalewayConnection;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Builder;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.ToString;
import lombok.experimental.SuperBuilder;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

@SuperBuilder
@ToString
@EqualsAndHashCode
@Getter
@NoArgsConstructor
public abstract class AbstractGenAiTask extends AbstractScalewayConnection {
    protected static final ObjectMapper MAPPER = JacksonMapper.ofJson();
    public static final String DEFAULT_BASE_URL = "https://api.scaleway.ai/v1";

    @Schema(
        title = "Base API URL",
        description = "The base URL for Scaleway Generative APIs. Defaults to `https://api.scaleway.ai/v1`."
    )
    @Builder.Default
    @PluginProperty(group = "advanced")
    protected Property<String> baseUrl = Property.ofValue(DEFAULT_BASE_URL);

    @Schema(
        title = "HTTP Request Timeout",
        description = "Timeout duration for HTTP requests to the Generative APIs. Defaults to 60 seconds."
    )
    @Builder.Default
    @PluginProperty(group = "advanced")
    protected Property<Duration> timeout = Property.ofValue(Duration.ofSeconds(60));

    protected URI resolveUri(RunContext runContext, String endpoint) throws Exception {
        String base = runContext.render(this.baseUrl).as(String.class).orElse(DEFAULT_BASE_URL);
        if (base.endsWith("/")) {
            base = base.substring(0, base.length() - 1);
        }
        if (!endpoint.startsWith("/")) {
            endpoint = "/" + endpoint;
        }
        return URI.create(base + endpoint);
    }

    protected String executeRequest(RunContext runContext, String method, String endpoint, String jsonBody) throws Exception {
        String rSecretKey = runContext.render(this.secretKey).as(String.class)
            .orElseThrow(() -> new IllegalArgumentException("Scaleway secretKey is required"));

        Duration rTimeout = runContext.render(this.timeout).as(Duration.class).orElse(Duration.ofSeconds(60));
        URI uri = resolveUri(runContext, endpoint);

        try (HttpClient httpClient = HttpClient.builder()
            .runContext(runContext)
            .configuration(HttpConfiguration.builder()
                .timeout(TimeoutConfiguration.builder()
                    .connectTimeout(Property.ofValue(Duration.ofSeconds(10)))
                    .readIdleTimeout(Property.ofValue(rTimeout))
                    .build())
                .build())
            .build()) {

            HttpRequest.HttpRequestBuilder reqBuilder = HttpRequest.builder()
                .method(method)
                .uri(uri)
                .addHeader("Authorization", "Bearer " + rSecretKey)
                .addHeader("Accept", "application/json");

            if (jsonBody != null && !jsonBody.isBlank()) {
                reqBuilder.addHeader("Content-Type", "application/json");
                reqBuilder.body(HttpRequest.StringRequestBody.builder()
                    .content(jsonBody)
                    .contentType("application/json")
                    .charset(StandardCharsets.UTF_8)
                    .build());
            }

            try {
                HttpResponse<String> response = httpClient.request(reqBuilder.build(), String.class);
                return response.getBody() != null ? response.getBody() : "";
            } catch (HttpClientResponseException e) {
                String errorBody = "";
                if (e.getResponse() != null && e.getResponse().getBody() != null) {
                    errorBody = e.getResponse().getBody().toString();
                }
                throw new IllegalStateException(
                    "Scaleway Generative API call to [" + method + " " + uri + "] failed with status " +
                        (e.getResponse() != null ? e.getResponse().getStatus().getCode() : "unknown") +
                        ": " + errorBody,
                    e
                );
            }
        }
    }
}
