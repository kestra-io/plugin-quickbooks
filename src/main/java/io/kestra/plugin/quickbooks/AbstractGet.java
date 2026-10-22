package io.kestra.plugin.quickbooks;

import com.fasterxml.jackson.core.type.TypeReference;
import io.kestra.core.exceptions.IllegalVariableEvaluationException;
import io.kestra.core.http.HttpRequest;
import io.kestra.core.http.client.HttpClient;
import io.kestra.core.models.annotations.PluginProperty;
import io.kestra.core.models.property.Property;
import io.kestra.core.models.tasks.RunnableTask;
import io.kestra.core.runners.RunContext;
import io.kestra.core.serializers.JacksonMapper;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import lombok.Builder;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.ToString;
import lombok.experimental.SuperBuilder;

import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Map;

@SuperBuilder
@ToString
@EqualsAndHashCode
@Getter
@NoArgsConstructor
public abstract class AbstractGet extends AbstractQuickBooksConnection implements RunnableTask<AbstractGet.Output> {

    @Schema(
        title = "The ID of the entity to fetch"
    )
    @PluginProperty(group = "main")
    @NotNull
    protected Property<String> entityId;

    protected abstract String entityPath();

    @Override
    public Output run(RunContext runContext) throws Exception {
        String accessToken = this.getAccessToken(runContext);
        String rRealmId = this.getValidatedRealmId(runContext);
        String baseUrl = this.getValidatedBaseUrl(runContext);
        Integer minorVersion = this.getValidatedMinorVersion(runContext);

        String rEntityId = runContext.render(this.entityId).as(String.class)
            .orElseThrow(() -> new IllegalVariableEvaluationException("entityId is required, set the 'entityId' property"));

        String endpoint = baseUrl + "/v3/company/" + rRealmId + "/" + entityPath() + "/"
            + URLEncoder.encode(rEntityId, StandardCharsets.UTF_8) + "?minorversion=" + minorVersion;

        try (HttpClient client = HttpClient.builder().runContext(runContext).build()) {
            HttpRequest request = HttpRequest.builder()
                .uri(URI.create(endpoint))
                .method("GET")
                .addHeader("Authorization", "Bearer " + accessToken)
                .addHeader("Accept", "application/json")
                .build();

            String responseBody = sendWithBackoff(client, request);

            Map<String, Object> responseMap = JacksonMapper.ofJson().readValue(responseBody, new TypeReference<Map<String, Object>>() {});

            return Output.builder()
                .row(responseMap)
                .build();
        }
    }

    @Builder
    @Getter
    public static class Output implements io.kestra.core.models.tasks.Output {
        @Schema(
            title = "The fetched entity"
        )
        private final Map<String, Object> row;
    }
}
