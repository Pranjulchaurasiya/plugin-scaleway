package io.kestra.plugin.scaleway.genai;

import com.github.tomakehurst.wiremock.junit5.WireMockExtension;
import io.kestra.core.context.TestRunContextFactory;
import io.kestra.core.junit.annotations.KestraTest;
import io.kestra.core.models.property.Property;
import io.kestra.core.runners.RunContext;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.junit.jupiter.api.parallel.ResourceLock;

import java.util.List;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.*;
import static org.junit.jupiter.api.Assertions.assertThrows;

@ResourceLock("kestra-h2-flyway")
@KestraTest
class GenAiTasksTest {

    @Inject
    private TestRunContextFactory runContextFactory;

    @RegisterExtension
    static WireMockExtension wireMock = WireMockExtension.newInstance()
        .options(wireMockConfig().dynamicPort())
        .build();

    private String wireMockBaseUrl() {
        return "http://localhost:" + wireMock.getPort();
    }

    @Test
    void testListModels() throws Exception {
        wireMock.stubFor(
            get(urlPathEqualTo("/models"))
                .withHeader("Authorization", equalTo("Bearer test-secret-key"))
                .willReturn(okJson("""
                    {
                      "object": "list",
                      "data": [
                        {
                          "id": "llama-3.1-8b-instruct",
                          "object": "model",
                          "created": 1720000000,
                          "owned_by": "scaleway"
                        },
                        {
                          "id": "bge-multilingual-gemma2",
                          "object": "model",
                          "created": 1720000001,
                          "owned_by": "scaleway"
                        }
                      ]
                    }
                    """))
        );

        RunContext runContext = runContextFactory.of();

        ListModels task = ListModels.builder()
            .id("list_models")
            .type(ListModels.class.getName())
            .secretKey(Property.ofValue("test-secret-key"))
            .baseUrl(Property.ofValue(wireMockBaseUrl()))
            .build();

        ListModels.Output output = task.run(runContext);

        assertThat(output, notNullValue());
        assertThat(output.getCount(), is(2));
        assertThat(output.getModels().size(), is(2));
        assertThat(output.getModels().get(0).getId(), is("llama-3.1-8b-instruct"));
        assertThat(output.getModels().get(0).getOwnedBy(), is("scaleway"));
        assertThat(output.getModels().get(1).getId(), is("bge-multilingual-gemma2"));
    }

    @Test
    void testChatCompletion() throws Exception {
        wireMock.stubFor(
            post(urlPathEqualTo("/chat/completions"))
                .withHeader("Authorization", equalTo("Bearer test-secret-key"))
                .willReturn(okJson("""
                    {
                      "id": "chatcmpl-test-123",
                      "object": "chat.completion",
                      "created": 1720000000,
                      "model": "llama-3.1-8b-instruct",
                      "choices": [
                        {
                          "index": 0,
                          "message": {
                            "role": "assistant",
                            "content": "Hello! How can I assist you today?"
                          },
                          "finish_reason": "stop"
                        }
                      ],
                      "usage": {
                        "prompt_tokens": 12,
                        "completion_tokens": 8,
                        "total_tokens": 20
                      }
                    }
                    """))
        );

        RunContext runContext = runContextFactory.of();

        ChatCompletion task = ChatCompletion.builder()
            .id("chat")
            .type(ChatCompletion.class.getName())
            .secretKey(Property.ofValue("test-secret-key"))
            .baseUrl(Property.ofValue(wireMockBaseUrl()))
            .model(Property.ofValue("llama-3.1-8b-instruct"))
            .messages(Property.ofValue(List.of(
                ChatCompletion.ChatMessage.builder()
                    .role("user")
                    .content("Hi")
                    .build()
            )))
            .temperature(Property.ofValue(0.7))
            .maxTokens(Property.ofValue(128))
            .build();

        ChatCompletion.Output output = task.run(runContext);

        assertThat(output, notNullValue());
        assertThat(output.getId(), is("chatcmpl-test-123"));
        assertThat(output.getModel(), is("llama-3.1-8b-instruct"));
        assertThat(output.getMessage(), is("Hello! How can I assist you today?"));
        assertThat(output.getChoices().size(), is(1));
        assertThat(output.getChoices().get(0).getFinishReason(), is("stop"));
        assertThat(output.getUsage().getTotalTokens(), is(20));
    }

    @Test
    void testEmbeddings() throws Exception {
        wireMock.stubFor(
            post(urlPathEqualTo("/embeddings"))
                .withHeader("Authorization", equalTo("Bearer test-secret-key"))
                .willReturn(okJson("""
                    {
                      "object": "list",
                      "data": [
                        {
                          "object": "embedding",
                          "index": 0,
                          "embedding": [0.123, -0.456, 0.789]
                        },
                        {
                          "object": "embedding",
                          "index": 1,
                          "embedding": [0.321, -0.654, 0.987]
                        }
                      ],
                      "model": "bge-multilingual-gemma2",
                      "usage": {
                        "prompt_tokens": 10,
                        "total_tokens": 10
                      }
                    }
                    """))
        );

        RunContext runContext = runContextFactory.of();

        Embeddings task = Embeddings.builder()
            .id("embed")
            .type(Embeddings.class.getName())
            .secretKey(Property.ofValue("test-secret-key"))
            .baseUrl(Property.ofValue(wireMockBaseUrl()))
            .model(Property.ofValue("bge-multilingual-gemma2"))
            .input(Property.ofValue(List.of("First doc", "Second doc")))
            .build();

        Embeddings.Output output = task.run(runContext);

        assertThat(output, notNullValue());
        assertThat(output.getTotal(), is(2));
        assertThat(output.getModel(), is("bge-multilingual-gemma2"));
        assertThat(output.getData().size(), is(2));
        assertThat(output.getData().get(0).getIndex(), is(0));
        assertThat(output.getData().get(0).getEmbedding().size(), is(3));
        assertThat(output.getUsage().getTotalTokens(), is(10));
    }

    @Test
    void testRerank() throws Exception {
        wireMock.stubFor(
            post(urlPathEqualTo("/rerank"))
                .withHeader("Authorization", equalTo("Bearer test-secret-key"))
                .willReturn(okJson("""
                    {
                      "model": "bge-reranker-v2-m3",
                      "results": [
                        {
                          "index": 0,
                          "relevance_score": 0.985,
                          "document": {
                            "text": "Kestra is an orchestration engine"
                          }
                        },
                        {
                          "index": 1,
                          "relevance_score": 0.25,
                          "document": {
                            "text": "Scaleway is a cloud provider"
                          }
                        }
                      ],
                      "total": 2
                    }
                    """))
        );

        RunContext runContext = runContextFactory.of();

        Rerank task = Rerank.builder()
            .id("rerank")
            .type(Rerank.class.getName())
            .secretKey(Property.ofValue("test-secret-key"))
            .baseUrl(Property.ofValue(wireMockBaseUrl()))
            .model(Property.ofValue("bge-reranker-v2-m3"))
            .query(Property.ofValue("What is Kestra?"))
            .documents(Property.ofValue(List.of(
                "Kestra is an orchestration engine",
                "Scaleway is a cloud provider"
            )))
            .topN(Property.ofValue(2))
            .build();

        Rerank.Output output = task.run(runContext);

        assertThat(output, notNullValue());
        assertThat(output.getTotal(), is(2));
        assertThat(output.getModel(), is("bge-reranker-v2-m3"));
        assertThat(output.getResults().size(), is(2));
        assertThat(output.getResults().get(0).getIndex(), is(0));
        assertThat(output.getResults().get(0).getRelevanceScore(), is(0.985));
        assertThat(output.getResults().get(0).getText(), is("Kestra is an orchestration engine"));
    }

    @Test
    void testUnauthorizedErrorThrowsException() {
        wireMock.stubFor(
            get(urlPathEqualTo("/models"))
                .willReturn(aResponse()
                    .withStatus(401)
                    .withBody("{\"error\": \"Unauthorized: Invalid API key\"}"))
        );

        RunContext runContext = runContextFactory.of();

        ListModels task = ListModels.builder()
            .id("list_models_unauth")
            .type(ListModels.class.getName())
            .secretKey(Property.ofValue("invalid-key"))
            .baseUrl(Property.ofValue(wireMockBaseUrl()))
            .build();

        assertThrows(IllegalStateException.class, () -> task.run(runContext));
    }
}
