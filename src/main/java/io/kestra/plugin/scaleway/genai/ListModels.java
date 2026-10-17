package io.kestra.plugin.scaleway.genai;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.core.type.TypeReference;
import io.kestra.core.models.annotations.Example;
import io.kestra.core.models.annotations.Plugin;
import io.kestra.core.models.tasks.RunnableTask;
import io.kestra.core.runners.RunContext;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Builder;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.ToString;
import lombok.experimental.SuperBuilder;
import org.slf4j.Logger;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@SuperBuilder
@ToString
@EqualsAndHashCode
@Getter
@NoArgsConstructor
@Schema(
    title = "List available models in Scaleway Generative APIs",
    description = "Queries the `GET /v1/models` endpoint to retrieve all hosted LLM models and their capabilities."
)
@Plugin(
    examples = {
        @Example(
            title = "List all available Generative API models",
            full = true,
            code = """
                id: scaleway_list_models
                namespace: company.ai

                tasks:
                  - id: list_models
                    type: io.kestra.plugin.scaleway.genai.ListModels
                    secretKey: "{{ secret('SCW_SECRET_KEY') }}"
                """
        )
    }
)
public class ListModels extends AbstractGenAiTask implements RunnableTask<ListModels.Output> {

    @Override
    public Output run(RunContext runContext) throws Exception {
        Logger logger = runContext.logger();
        logger.info("Fetching available Scaleway Generative API models");

        String responseBody = executeRequest(runContext, "GET", "/models", null);

        Map<String, Object> envelope = MAPPER.readValue(responseBody, new TypeReference<>() {});
        List<Model> models = new ArrayList<>();

        if (envelope.get("data") instanceof List<?> dataList) {
            for (Object item : dataList) {
                if (item instanceof Map<?, ?> modelMap) {
                    models.add(Model.builder()
                        .id(asString(modelMap.get("id")))
                        .object(asString(modelMap.get("object")))
                        .created(asLong(modelMap.get("created")))
                        .ownedBy(asString(modelMap.get("owned_by")))
                        .build());
                }
            }
        }

        logger.info("Successfully fetched {} model(s) from Scaleway Generative APIs", models.size());

        return Output.builder()
            .models(models)
            .count(models.size())
            .build();
    }

    private static String asString(Object val) {
        return val != null ? String.valueOf(val) : null;
    }

    private static Long asLong(Object val) {
        if (val instanceof Number num) {
            return num.longValue();
        }
        return null;
    }

    @Builder
    @Getter
    @Schema(title = "Model", description = "Metadata for a hosted model in Scaleway Generative APIs.")
    public static class Model {
        @Schema(title = "Model ID", description = "The unique identifier of the model (e.g. `llama-3.1-8b-instruct`).")
        private String id;

        @Schema(title = "Object Type", description = "The object type, typically `model`.")
        private String object;

        @Schema(title = "Creation Timestamp", description = "Unix timestamp of model creation.")
        private Long created;

        @JsonProperty("owned_by")
        @Schema(title = "Owner", description = "The organization owning the model.")
        private String ownedBy;
    }

    @Builder
    @Getter
    @Schema(title = "Task Output", description = "Output containing retrieved models.")
    public static class Output implements io.kestra.core.models.tasks.Output {
        @Schema(title = "Models", description = "List of available models.")
        private List<Model> models;

        @Schema(title = "Count", description = "Total number of models returned.")
        private Integer count;
    }
}
