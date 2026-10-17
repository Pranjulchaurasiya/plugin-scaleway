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
import lombok.AllArgsConstructor;
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
    title = "Generate chat completions using Scaleway Generative APIs",
    description = "Executes an OpenAI-compatible chat completion request against Scaleway-hosted open-weight LLMs (such as Llama, Mistral, Qwen)."
)
@Plugin(
    examples = {
        @Example(
            title = "Run a prompt against Llama 3.1 8B Instruct",
            full = true,
            code = """
                id: scaleway_chat_completion
                namespace: company.ai

                inputs:
                  - id: prompt
                    type: STRING

                tasks:
                  - id: chat
                    type: io.kestra.plugin.scaleway.genai.ChatCompletion
                    secretKey: "{{ secret('SCW_SECRET_KEY') }}"
                    model: "llama-3.1-8b-instruct"
                    messages:
                      - role: user
                        content: "{{ inputs.prompt }}"
                """
        )
    }
)
public class ChatCompletion extends AbstractGenAiTask implements RunnableTask<ChatCompletion.Output> {

    @Schema(
        title = "Model Name",
        description = "Identifier of the hosted model to use for completion (e.g. `llama-3.1-8b-instruct`, `mistral-7b-instruct`)."
    )
    @NotNull
    @PluginProperty(group = "main")
    private Property<String> model;

    @Schema(
        title = "Chat Messages",
        description = "List of conversation messages comprising role and text content."
    )
    @NotNull
    @PluginProperty(group = "main")
    private Property<List<ChatMessage>> messages;

    @Schema(
        title = "Sampling Temperature",
        description = "Sampling temperature between 0.0 and 2.0. Higher values yield more creative outputs."
    )
    @PluginProperty(group = "advanced")
    private Property<Double> temperature;

    @Schema(
        title = "Top P Nucleus Sampling",
        description = "Alternative to temperature sampling: model considers results of tokens with top_p probability mass."
    )
    @PluginProperty(group = "advanced")
    private Property<Double> topP;

    @Schema(
        title = "Maximum Tokens",
        description = "Maximum number of tokens to generate in the completion response."
    )
    @PluginProperty(group = "advanced")
    private Property<Integer> maxTokens;

    @Override
    public Output run(RunContext runContext) throws Exception {
        Logger logger = runContext.logger();

        String rModel = runContext.render(this.model).as(String.class)
            .orElseThrow(() -> new IllegalArgumentException("Model parameter is required"));

        List<ChatMessage> rMessages = runContext.render(this.messages).asList(ChatMessage.class);
        if (rMessages == null || rMessages.isEmpty()) {
            throw new IllegalArgumentException("Messages list must not be empty");
        }

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("model", rModel);

        List<Map<String, String>> renderedMessages = new ArrayList<>();
        for (ChatMessage msg : rMessages) {
            Map<String, String> m = new LinkedHashMap<>();
            m.put("role", msg.getRole());
            m.put("content", msg.getContent());
            renderedMessages.add(m);
        }
        payload.put("messages", renderedMessages);

        if (this.temperature != null) {
            runContext.render(this.temperature).as(Double.class).ifPresent(v -> payload.put("temperature", v));
        }
        if (this.topP != null) {
            runContext.render(this.topP).as(Double.class).ifPresent(v -> payload.put("top_p", v));
        }
        if (this.maxTokens != null) {
            runContext.render(this.maxTokens).as(Integer.class).ifPresent(v -> payload.put("max_tokens", v));
        }

        String requestBody = MAPPER.writeValueAsString(payload);
        logger.info("Sending chat completion request for model '{}'", rModel);

        String responseBody = executeRequest(runContext, "POST", "/chat/completions", requestBody);
        Map<String, Object> respMap = MAPPER.readValue(responseBody, new TypeReference<>() {});

        String id = asString(respMap.get("id"));
        String modelResp = asString(respMap.get("model"));

        List<Choice> choices = new ArrayList<>();
        if (respMap.get("choices") instanceof List<?> choicesList) {
            for (Object c : choicesList) {
                if (c instanceof Map<?, ?> choiceMap) {
                    ChatMessage msgObj = null;
                    if (choiceMap.get("message") instanceof Map<?, ?> msgMap) {
                        msgObj = ChatMessage.builder()
                            .role(asString(msgMap.get("role")))
                            .content(asString(msgMap.get("content")))
                            .build();
                    }
                    choices.add(Choice.builder()
                        .index(asInteger(choiceMap.get("index")))
                        .finishReason(asString(choiceMap.get("finish_reason")))
                        .message(msgObj)
                        .build());
                }
            }
        }

        Usage usage = null;
        if (respMap.get("usage") instanceof Map<?, ?> usageMap) {
            usage = Usage.builder()
                .promptTokens(asInteger(usageMap.get("prompt_tokens")))
                .completionTokens(asInteger(usageMap.get("completion_tokens")))
                .totalTokens(asInteger(usageMap.get("total_tokens")))
                .build();
        }

        String firstContent = choices.isEmpty() || choices.get(0).getMessage() == null
            ? null
            : choices.get(0).getMessage().getContent();

        logger.info("Chat completion completed with ID '{}'", id);

        return Output.builder()
            .id(id)
            .model(modelResp)
            .choices(choices)
            .usage(usage)
            .message(firstContent)
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
    @NoArgsConstructor
    @AllArgsConstructor
    @Schema(title = "Chat Message", description = "A message in a chat conversation.")
    public static class ChatMessage {
        @Schema(title = "Role", description = "Role of the message author (`system`, `user`, or `assistant`).")
        private String role;

        @Schema(title = "Content", description = "Text content of the message.")
        private String content;
    }

    @Builder
    @Getter
    @Schema(title = "Choice", description = "A completion candidate choice.")
    public static class Choice {
        @Schema(title = "Index", description = "Index of the choice.")
        private Integer index;

        @Schema(title = "Message", description = "The generated chat message.")
        private ChatMessage message;

        @JsonProperty("finish_reason")
        @Schema(title = "Finish Reason", description = "Reason the model ceased generating tokens (e.g. `stop`, `length`).")
        private String finishReason;
    }

    @Builder
    @Getter
    @Schema(title = "Usage", description = "Token consumption statistics for the request.")
    public static class Usage {
        @JsonProperty("prompt_tokens")
        @Schema(title = "Prompt Tokens", description = "Number of tokens in the prompt.")
        private Integer promptTokens;

        @JsonProperty("completion_tokens")
        @Schema(title = "Completion Tokens", description = "Number of tokens generated in the completion.")
        private Integer completionTokens;

        @JsonProperty("total_tokens")
        @Schema(title = "Total Tokens", description = "Total tokens consumed.")
        private Integer totalTokens;
    }

    @Builder
    @Getter
    @Schema(title = "Task Output", description = "Output containing the chat completion response.")
    public static class Output implements io.kestra.core.models.tasks.Output {
        @Schema(title = "Response ID", description = "Unique identifier of the completion response.")
        private String id;

        @Schema(title = "Model", description = "Model identifier that performed the inference.")
        private String model;

        @Schema(title = "Choices", description = "List of completion choices.")
        private List<Choice> choices;

        @Schema(title = "Usage", description = "Token usage statistics.")
        private Usage usage;

        @Schema(title = "Message", description = "Convenience shortcut: content of the first choice message.")
        private String message;
    }
}
