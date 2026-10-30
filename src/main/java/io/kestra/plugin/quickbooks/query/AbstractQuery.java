package io.kestra.plugin.quickbooks.query;

import io.kestra.core.models.annotations.PluginProperty;
import io.kestra.core.models.property.Property;
import io.kestra.core.models.tasks.RunnableTask;
import io.kestra.core.models.tasks.common.FetchOutput;
import io.kestra.core.models.tasks.common.FetchType;
import io.kestra.core.runners.RunContext;
import io.kestra.core.serializers.FileSerde;
import io.kestra.core.serializers.JacksonMapper;
import io.kestra.core.http.HttpRequest;
import io.kestra.core.http.client.HttpClient;
import io.kestra.plugin.quickbooks.AbstractQuickBooksConnection;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Builder;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.ToString;
import lombok.experimental.SuperBuilder;

import java.io.File;
import java.io.FileOutputStream;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;


@SuperBuilder
@ToString
@EqualsAndHashCode
@Getter
@NoArgsConstructor
public abstract class AbstractQuery extends AbstractQuickBooksConnection implements RunnableTask<FetchOutput> {

    @Schema(
        title = "The way to consume the results",
        description = "FETCH stores results in memory (useful for small datasets). STORE saves them as an internal storage file (mandatory for large datasets).",
        defaultValue = "STORE"
    )
    @PluginProperty(group = "main")
    @Builder.Default
    private Property<FetchType> fetchType = Property.ofValue(FetchType.STORE);

    protected FetchOutput run(RunContext runContext, String rQuery) throws Exception {
        var rFetchType = runContext.render(this.fetchType).as(FetchType.class).orElse(FetchType.STORE);
        var rAccessToken = this.getAccessToken(runContext);
        var rRealmId = this.getValidatedRealmId(runContext);
        var rBaseUrl = this.getValidatedBaseUrl(runContext);
        var rMinorVersion = this.getValidatedMinorVersion(runContext);

        // Strip existing pagination if provided
        rQuery = rQuery.replaceAll("(?i)\\s+(startposition|maxresults)\\s+\\d+", "");

        var rows = new ArrayList<Object>();
        File tempFile = null;
        FileOutputStream fileOutputStream = null;

        if (rFetchType == FetchType.STORE) {
            tempFile = runContext.workingDir().createTempFile(".ion").toFile();
            fileOutputStream = new FileOutputStream(tempFile);
        }

        var startPosition = 1;
        var maxResults = 1000;
        var totalRows = 0;
        var hasMore = true;

        try (var client = HttpClient.builder().runContext(runContext).build()) {
            while (hasMore) {
                var pagedQuery = rQuery + " STARTPOSITION " + startPosition + " MAXRESULTS " + maxResults;
                var uriStr = rBaseUrl + "/v3/company/" + rRealmId + "/query?minorversion=" + rMinorVersion + "&query=" + URLEncoder.encode(pagedQuery, StandardCharsets.UTF_8);

                var request = HttpRequest.builder()
                    .uri(URI.create(uriStr))
                    .method("GET")
                    .addHeader("Accept", "application/json")
                    .addHeader("Authorization", "Bearer " + rAccessToken)
                    .build();

                var responseBody = sendWithBackoff(client, request);

                var responseMap = JacksonMapper.ofJson().readValue(responseBody, Map.class);
                var queryResponse = (Map<String, Object>) responseMap.get("QueryResponse");

                if (queryResponse == null || queryResponse.isEmpty()) {
                    break;
                }

                var foundData = false;
                for (Map.Entry<String, Object> entry : queryResponse.entrySet()) {
                    if (!entry.getKey().equals("startPosition") && !entry.getKey().equals("maxResults") && !entry.getKey().equals("totalCount")) {
                        if (entry.getValue() instanceof List<?> entityList) {
                            foundData = true;
                            totalRows += entityList.size();
                            for (Object row : entityList) {
                                if (rFetchType == FetchType.STORE) {
                                    FileSerde.write(fileOutputStream, row);
                                } else if (rFetchType == FetchType.FETCH || rFetchType == FetchType.FETCH_ONE) {
                                    rows.add(row);
                                }
                            }
                            if (entityList.size() < maxResults) {
                                hasMore = false;
                            }
                        }
                    }
                }

                if (!foundData) {
                    hasMore = false;
                }

                if (rFetchType == FetchType.FETCH_ONE && totalRows > 0) {
                    hasMore = false;
                }

                startPosition += maxResults;
            }
        } finally {
            if (fileOutputStream != null) {
                fileOutputStream.close();
            }
        }

        var builder = FetchOutput.builder().size((long) totalRows);

        if (rFetchType == FetchType.STORE) {
            builder.uri(runContext.storage().putFile(tempFile));
        } else if (rFetchType == FetchType.FETCH) {
            builder.rows((List) rows);
        } else if (rFetchType == FetchType.FETCH_ONE) {
            builder.row(!rows.isEmpty() ? (Map) rows.getFirst() : null);
        }

        return builder.build();
    }
}
