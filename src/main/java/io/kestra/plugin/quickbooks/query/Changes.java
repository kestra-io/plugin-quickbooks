package io.kestra.plugin.quickbooks.query;

import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import jakarta.validation.constraints.NotNull;

import io.kestra.core.exceptions.IllegalVariableEvaluationException;
import io.kestra.core.http.HttpRequest;
import io.kestra.core.http.client.HttpClient;
import io.kestra.core.models.annotations.Example;
import io.kestra.core.models.annotations.Plugin;
import io.kestra.core.models.annotations.PluginProperty;
import io.kestra.core.models.property.Property;
import io.kestra.core.models.tasks.RunnableTask;
import io.kestra.core.runners.RunContext;
import io.kestra.core.serializers.JacksonMapper;
import io.kestra.plugin.quickbooks.AbstractQuickBooksConnection;
import io.swagger.v3.oas.annotations.media.Schema;

import com.fasterxml.jackson.core.type.TypeReference;
import lombok.Builder;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.ToString;
import lombok.experimental.SuperBuilder;

@SuperBuilder
@ToString
@EqualsAndHashCode
@Getter
@NoArgsConstructor
@Plugin(
    examples = {
        @Example(
            title = "Get changed Invoice and Customer records since a specific date",
            full = true,
            code = """
                id: quickbooks_changes
                namespace: company.team

                tasks:
                  - id: get_changes
                    type: io.kestra.plugin.quickbooks.query.Changes
                    clientId: "{{ secret('QBO_CLIENT_ID') }}"
                    clientSecret: "{{ secret('QBO_CLIENT_SECRET') }}"
                    refreshToken: "{{ secret('QBO_REFRESH_TOKEN') }}"
                    realmId: "{{ secret('QBO_REALM_ID') }}"
                    entities:
                      - Invoice
                      - Customer
                    changedSince: "2026-01-01T00:00:00Z"
                """
        )
    }
)
@Schema(
    title = "Get the entities changed since a date (Change Data Capture)",
    description = "Calls the QuickBooks Change Data Capture endpoint. QuickBooks only keeps about 30 days of changes, so `changedSince` must be within that window."
)
public class Changes extends AbstractQuickBooksConnection implements RunnableTask<Changes.Output> {

    @Schema(
        title = "Entities",
        description = "The QuickBooks entities to check for changes, for example `Invoice` or `Customer`."
    )
    @PluginProperty(group = "main")
    @NotNull
    private Property<List<String>> entities;

    @Schema(
        title = "Changed since",
        description = "Only changes after this ISO 8601 date-time are returned, for example `2026-01-01T00:00:00Z`."
    )
    @PluginProperty(group = "main")
    @NotNull
    private Property<String> changedSince;

    @Override
    public Output run(RunContext runContext) throws Exception {
        var rEntities = runContext.render(this.entities).asList(String.class);
        if (rEntities.isEmpty()) {
            throw new IllegalArgumentException("entities must not be empty");
        }

        var rChangedSince = runContext.render(this.changedSince).as(String.class)
            .orElseThrow(() -> new IllegalVariableEvaluationException("changedSince is required, set the 'changedSince' property"));
        var rFormattedChangedSince = ZonedDateTime.parse(rChangedSince).format(DateTimeFormatter.ISO_OFFSET_DATE_TIME);

        var rAccessToken = this.getAccessToken(runContext);
        var rRealmId = this.getValidatedRealmId(runContext);
        var rBaseUrl = this.getValidatedBaseUrl(runContext);
        var rMinorVersion = this.getValidatedMinorVersion(runContext);

        var rEndpoint = rBaseUrl + "/v3/company/" + rRealmId + "/cdc?minorversion=" + rMinorVersion
            + "&entities=" + URLEncoder.encode(String.join(",", rEntities), StandardCharsets.UTF_8)
            + "&changedSince=" + URLEncoder.encode(rFormattedChangedSince, StandardCharsets.UTF_8);

        try (var client = HttpClient.builder().runContext(runContext).build()) {
            var request = HttpRequest.builder()
                .uri(URI.create(rEndpoint))
                .method("GET")
                .addHeader("Authorization", "Bearer " + rAccessToken)
                .addHeader("Accept", "application/json")
                .build();

            var rResponseBody = sendWithBackoff(client, request);
            if (rResponseBody == null || rResponseBody.isBlank()) {
                throw new IllegalStateException("Empty response from the QuickBooks CDC endpoint, check changedSince is within the last 30 days and retry");
            }

            var rResponse = JacksonMapper.ofJson().readValue(rResponseBody, new TypeReference<Map<String, Object>>() {});

            var rRows = new LinkedHashMap<String, List<Map<String, Object>>>();
            for (var entity : rEntities) {
                rRows.put(entity, records(rResponse, entity).stream().map(Changes::toChangeRow).toList());
            }

            return Output.builder()
                .rows(rRows)
                .cdcResponse(rResponse)
                .build();
        }
    }

    /**
     * Extracts the records of one entity from a CDC response: {@code CDCResponse[].QueryResponse[].<entity>[]}.
     */
    @SuppressWarnings("unchecked")
    public static List<Map<String, Object>> records(Map<String, Object> cdcResponse, String entity) {
        var records = new ArrayList<Map<String, Object>>();
        if (cdcResponse == null || !(cdcResponse.get("CDCResponse") instanceof List<?> responses)) {
            return records;
        }
        for (var response : responses) {
            if (!(response instanceof Map<?, ?> responseMap) || !(responseMap.get("QueryResponse") instanceof List<?> queryResponses)) {
                continue;
            }
            for (var queryResponse : queryResponses) {
                if (!(queryResponse instanceof Map<?, ?> queryResponseMap)) {
                    continue;
                }
                for (var entry : queryResponseMap.entrySet()) {
                    if (entity.equalsIgnoreCase(String.valueOf(entry.getKey())) && entry.getValue() instanceof List<?> list) {
                        for (var record : list) {
                            if (record instanceof Map<?, ?> map) {
                                records.add((Map<String, Object>) map);
                            }
                        }
                    }
                }
            }
        }
        return records;
    }

    private static Map<String, Object> toChangeRow(Map<String, Object> record) {
        var id = record.get("Id") != null ? record.get("Id") : record.get("id");
        var deleted = "Deleted".equalsIgnoreCase(String.valueOf(record.get("status")));

        var row = new LinkedHashMap<String, Object>();
        row.put("id", id);
        row.put("status", status(record, deleted));
        row.put("deleted", deleted);
        return row;
    }

    /**
     * QuickBooks only labels deletions. Other records are {@code Created} when the metadata shows they were never
     * modified after creation, else {@code Updated}.
     */
    private static String status(Map<String, Object> record, boolean deleted) {
        if (deleted) {
            return "Deleted";
        }
        if (record.get("status") != null) {
            return record.get("status").toString();
        }
        if (record.get("MetaData") instanceof Map<?, ?> meta && meta.get("CreateTime") != null
            && meta.get("CreateTime").equals(meta.get("LastUpdatedTime"))) {
            return "Created";
        }
        return "Updated";
    }

    @Builder
    @Getter
    public static class Output implements io.kestra.core.models.tasks.Output {
        @Schema(
            title = "Changed rows per entity",
            description = "Maps each requested entity to its changed records. Every record has `id`, `status` (`Created`, `Updated` or `Deleted`) and `deleted`. An entity without changes maps to an empty list."
        )
        private final Map<String, List<Map<String, Object>>> rows;

        @Schema(
            title = "CDC response",
            description = "The parsed JSON response of QuickBooks with the full changed records."
        )
        private final Map<String, Object> cdcResponse;
    }
}
