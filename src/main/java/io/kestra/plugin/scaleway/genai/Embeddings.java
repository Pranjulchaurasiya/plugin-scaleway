package io.kestra.plugin.scaleway.genai;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.core.type.TypeReference;
import io.kestra.core.models.annotations.Example;
import io.kestra.core.models.annotations.Plugin;
import io.kestra.core.models.annotations.PluginProperty;
import io.kestra.core.models.property.Property;
import io.kestra.core.models.tasks.RunnableTask;
import io.kestra.core.runners.RunContext;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import lombok.Builder;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.ToString;
import lombok.experimental.SuperBuilder;
import org.slf4j.Logger;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@SuperBuilder
@ToString
@EqualsAndHashCode
@Getter
@NoArgsConstructor
@Schema(
    title = "Generate dense text embeddings using Scaleway Generative APIs",
    description = "Calls the `POST /v1/embeddings` endpoint to transform texts into dense vector representations for search, clustering, or RAG pipelines."
)
@Plugin(
    examples = {
        @Example(
            title = "Embed a batch of text documents and inspect vector count",
            full = true,
            code = """
                id: scaleway_embeddings
                namespace: company.ai

                tasks:
                  - id: embed
                    type: io.kestra.plugin.scaleway.genai.Embeddings
                    secretKey: "{{ secret('SCW_SECRET_KEY') }}"
                    model: "bge-multilingual-gemma2"
                    input:
                      - "First document to embed"
                      - "Second document to embed"
                """
        )
    }
)
public class Embeddings extends AbstractGenAiTask implements RunnableTask<Embeddings.Output> {

    @Schema(
        title = "Model Name",
        description = "Embedding model identifier (e.g. `bge-multilingual-gemma2`, `bge-large-en-v1.5`)."
    )
    @NotNull
    @PluginProperty(group = "main")
    private Property<String> model;

    @Schema(
        title = "Input Texts",
        description = "List of text strings to generate vector embeddings for."
    )
    @NotNull
    @PluginProperty(group = "main")
    private Property<List<String>> input;

    @Schema(
        title = "Encoding Format",
        description = "Format of returned embeddings (typically `float`)."
    )
    @PluginProperty(group = "advanced")
    private Property<String> encodingFormat;

    @Override
    public Output run(RunContext runContext) throws Exception {
        Logger logger = runContext.logger();

        String rModel = runContext.render(this.model).as(String.class)
            .orElseThrow(() -> new IllegalArgumentException("Model parameter is required"));

        List<String> rInput = runContext.render(this.input).asList(String.class);
        if (rInput == null || rInput.isEmpty()) {
            throw new IllegalArgumentException("Input text list must not be empty");
        }

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("model", rModel);
        payload.put("input", rInput);

        if (this.encodingFormat != null) {
            runContext.render(this.encodingFormat).as(String.class).ifPresent(v -> payload.put("encoding_format", v));
        }

        String requestBody = MAPPER.writeValueAsString(payload);
        logger.info("Requesting embeddings for {} text(s) with model '{}'", rInput.size(), rModel);

        String responseBody = executeRequest(runContext, "POST", "/embeddings", requestBody);
        Map<String, Object> respMap = MAPPER.readValue(responseBody, new TypeReference<>() {});

        String modelResp = asString(respMap.get("model"));

        List<EmbeddingData> dataList = new ArrayList<>();
        if (respMap.get("data") instanceof List<?> rawList) {
            for (Object item : rawList) {
                if (item instanceof Map<?, ?> itemMap) {
                    List<Double> vector = new ArrayList<>();
                    if (itemMap.get("embedding") instanceof List<?> vecList) {
                        for (Object v : vecList) {
                            if (v instanceof Number num) {
                                vector.add(num.doubleValue());
                            }
                        }
                    }
                    dataList.add(EmbeddingData.builder()
                        .index(asInteger(itemMap.get("index")))
                        .embedding(vector)
                        .build());
                }
            }
        }

        Usage usage = null;
        if (respMap.get("usage") instanceof Map<?, ?> usageMap) {
            usage = Usage.builder()
                .promptTokens(asInteger(usageMap.get("prompt_tokens")))
                .totalTokens(asInteger(usageMap.get("total_tokens")))
                .build();
        }

        logger.info("Successfully generated {} embedding vector(s)", dataList.size());

        return Output.builder()
            .data(dataList)
            .model(modelResp)
            .usage(usage)
            .total(dataList.size())
            .build();
    }

    private static String asString(Object val) {
        return val != null ? String.valueOf(val) : null;
    }

    private static Integer asInteger(Object val) {
        if (val instanceof Number num) {
            return num.intValue();
        }
        return null;
    }

    @Builder
    @Getter
    @Schema(title = "Embedding Data", description = "Vector embedding data point.")
    public static class EmbeddingData {
        @Schema(title = "Index", description = "Zero-based index corresponding to the input text.")
        private Integer index;

        @Schema(title = "Embedding Vector", description = "Dense float vector embedding.")
        private List<Double> embedding;
    }

    @Builder
    @Getter
    @Schema(title = "Usage", description = "Token consumption for the embedding operation.")
    public static class Usage {
        @JsonProperty("prompt_tokens")
        @Schema(title = "Prompt Tokens", description = "Number of prompt tokens processed.")
        private Integer promptTokens;

        @JsonProperty("total_tokens")
        @Schema(title = "Total Tokens", description = "Total tokens processed.")
        private Integer totalTokens;
    }

    @Builder
    @Getter
    @Schema(title = "Task Output", description = "Output containing generated vector embeddings.")
    public static class Output implements io.kestra.core.models.tasks.Output {
        @Schema(title = "Embeddings Data", description = "List of generated vector embeddings.")
        private List<EmbeddingData> data;

        @Schema(title = "Model", description = "Model that generated the embeddings.")
        private String model;

        @Schema(title = "Usage", description = "Token usage statistics.")
        private Usage usage;

        @Schema(title = "Total", description = "Total count of embeddings generated.")
        private Integer total;
    }
}
