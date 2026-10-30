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
        var rAccessToken = this.getAccessToken(runContext);
        var rRealmId = this.getValidatedRealmId(runContext);
        var rBaseUrl = this.getValidatedBaseUrl(runContext);
        var rMinorVersion = this.getValidatedMinorVersion(runContext);

        var rEntityId = runContext.render(this.entityId).as(String.class)
            .orElseThrow(() -> new IllegalVariableEvaluationException("entityId is required, set the 'entityId' property"));

        var endpoint = rBaseUrl + "/v3/company/" + rRealmId + "/" + entityPath() + "/"
            + URLEncoder.encode(rEntityId, StandardCharsets.UTF_8) + "?minorversion=" + rMinorVersion;

        try (HttpClient client = HttpClient.builder().runContext(runContext).build()) {
            var request = HttpRequest.builder()
                .uri(URI.create(endpoint))
                .method("GET")
                .addHeader("Authorization", "Bearer " + rAccessToken)
                .addHeader("Accept", "application/json")
                .build();

            var responseBody = sendWithBackoff(client, request);

            var responseMap = JacksonMapper.ofJson().readValue(responseBody, new TypeReference<Map<String, Object>>() {});

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
