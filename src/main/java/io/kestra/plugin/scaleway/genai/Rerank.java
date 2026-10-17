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
    title = "Rerank candidate documents based on query relevance using Scaleway Generative APIs",
    description = "Calls the `POST /v1/rerank` endpoint with a query and candidate documents to compute relevance scores for two-stage retrieval in RAG architectures."
)
@Plugin(
    examples = {
        @Example(
            title = "Rerank candidate passages against a query and select top results",
            full = true,
            code = """
                id: scaleway_rerank
                namespace: company.ai

                tasks:
                  - id: rerank_docs
                    type: io.kestra.plugin.scaleway.genai.Rerank
                    secretKey: "{{ secret('SCW_SECRET_KEY') }}"
                    model: "bge-reranker-v2-m3"
                    query: "How to configure triggers in Kestra?"
                    documents:
                      - "Kestra triggers allow scheduling and event-driven workflow execution."
                      - "Scaleway provides managed Kubernetes and cloud instances."
                      - "Triggers can be flow triggers, schedule triggers, or webhook triggers."
                    topN: 2
                """
        )
    }
)
public class Rerank extends AbstractGenAiTask implements RunnableTask<Rerank.Output> {

    @Schema(
        title = "Model Name",
        description = "Reranking model identifier (e.g. `bge-reranker-v2-m3`)."
    )
    @NotNull
    @PluginProperty(group = "main")
    private Property<String> model;

    @Schema(
        title = "Search Query",
        description = "The query string against which candidate documents are evaluated."
    )
    @NotNull
    @PluginProperty(group = "main")
    private Property<String> query;

    @Schema(
        title = "Candidate Documents",
        description = "List of candidate text passages to score and rank."
    )
    @NotNull
    @PluginProperty(group = "main")
    private Property<List<String>> documents;

    @Schema(
        title = "Top N Results",
        description = "Maximum number of highest-ranking documents to return."
    )
    @PluginProperty(group = "advanced")
    private Property<Integer> topN;

    @Override
    public Output run(RunContext runContext) throws Exception {
        Logger logger = runContext.logger();

        String rModel = runContext.render(this.model).as(String.class)
            .orElseThrow(() -> new IllegalArgumentException("Model parameter is required"));

        String rQuery = runContext.render(this.query).as(String.class)
            .orElseThrow(() -> new IllegalArgumentException("Query parameter is required"));

        List<String> rDocuments = runContext.render(this.documents).asList(String.class);
        if (rDocuments == null || rDocuments.isEmpty()) {
            throw new IllegalArgumentException("Documents list must not be empty");
        }

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("model", rModel);
        payload.put("query", rQuery);
        payload.put("documents", rDocuments);

        if (this.topN != null) {
            runContext.render(this.topN).as(Integer.class).ifPresent(v -> payload.put("top_n", v));
        }

        String requestBody = MAPPER.writeValueAsString(payload);
        logger.info("Executing rerank for {} document(s) with model '{}'", rDocuments.size(), rModel);

        String responseBody = executeRequest(runContext, "POST", "/rerank", requestBody);
        Map<String, Object> respMap = MAPPER.readValue(responseBody, new TypeReference<>() {});

        String modelResp = asString(respMap.get("model"));

        List<RankedResult> results = new ArrayList<>();
        if (respMap.get("results") instanceof List<?> rawList) {
            for (Object item : rawList) {
                if (item instanceof Map<?, ?> itemMap) {
                    Integer index = asInteger(itemMap.get("index"));
                    Double score = asDouble(itemMap.get("relevance_score"));
                    String text = null;

                    Object docObj = itemMap.get("document");
                    if (docObj instanceof Map<?, ?> docMap) {
                        text = asString(docMap.get("text"));
                    } else if (docObj instanceof String str) {
                        text = str;
                    } else if (index != null && index >= 0 && index < rDocuments.size()) {
                        text = rDocuments.get(index);
                    }

                    results.add(RankedResult.builder()
                        .index(index)
                        .relevanceScore(score)
                        .text(text)
                        .build());
                }
            }
        }

        logger.info("Reranking completed, returned {} ranked document(s)", results.size());

        return Output.builder()
            .results(results)
            .model(modelResp)
            .total(results.size())
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

    private static Double asDouble(Object val) {
        if (val instanceof Number num) {
            return num.doubleValue();
        }
        return null;
    }

    @Builder
    @Getter
    @Schema(title = "Ranked Result", description = "Reranked candidate document with associated relevance score.")
    public static class RankedResult {
        @Schema(title = "Original Index", description = "Position index in the original candidate documents array.")
        private Integer index;

        @JsonProperty("relevance_score")
        @Schema(title = "Relevance Score", description = "Relevance confidence score computed by the reranker model.")
        private Double relevanceScore;

        @Schema(title = "Document Text", description = "Text content of the candidate document.")
        private String text;
    }

    @Builder
    @Getter
    @Schema(title = "Task Output", description = "Output containing reranked documents ordered by relevance.")
    public static class Output implements io.kestra.core.models.tasks.Output {
        @Schema(title = "Ranked Results", description = "List of ranked documents with scores.")
        private List<RankedResult> results;

        @Schema(title = "Model", description = "Model that performed the reranking.")
        private String model;

        @Schema(title = "Total", description = "Total count of ranked results returned.")
        private Integer total;
    }
}
