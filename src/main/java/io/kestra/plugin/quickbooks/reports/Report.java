package io.kestra.plugin.quickbooks.reports;

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
import com.fasterxml.jackson.core.type.TypeReference;
import io.kestra.plugin.quickbooks.AbstractQuickBooksConnection;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Builder;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.ToString;
import lombok.experimental.SuperBuilder;

import jakarta.validation.constraints.NotNull;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.stream.Collectors;

@SuperBuilder
@ToString
@EqualsAndHashCode
@Getter
@NoArgsConstructor
@Plugin(
    examples = {
        @Example(
            title = "Get a Profit and Loss report",
            full = true,
            code = """
                id: quickbooks_profit_and_loss
                namespace: company.team
                tasks:
                  - id: get_profit_and_loss
                    type: io.kestra.plugin.quickbooks.reports.Report
                    clientId: "{{ secret('QBO_CLIENT_ID') }}"
                    clientSecret: "{{ secret('QUICKBOOKS_CLIENT_SECRET') }}"
                    refreshToken: "{{ secret('QUICKBOOKS_REFRESH_TOKEN') }}"
                    realmId: "{{ secret('QBO_REALM_ID') }}"
                    reportName: "ProfitAndLoss"
                    parameters:
                      start_date: "2023-01-01"
                      end_date: "2023-12-31"
                """
        )
    }
)
@Schema(
    title = "Retrieve a report from QuickBooks",
    description = "Retrieves a specified report for the given Realm ID, optionally applying parameters like date ranges."
)
public class Report extends AbstractQuickBooksConnection implements RunnableTask<Report.Output> {

    @Schema(
        title = "Report Name",
        description = "The name of the report to fetch. E.g. 'ProfitAndLoss', 'TransactionList', 'GeneralLedger', etc."
    )
    @PluginProperty(group = "main")
    @NotNull
    private Property<String> reportName;

    @Schema(
        title = "Report Parameters",
        description = "Additional query parameters to filter or format the report, such as 'start_date', 'end_date', 'accounting_method'."
    )
    @PluginProperty(group = "main")
    private Property<Map<String, String>> parameters;

    @Override
    public Output run(RunContext runContext) throws Exception {
        String accessToken = this.getAccessToken(runContext);
        String rRealmId = this.getValidatedRealmId(runContext);
        String baseUrl = this.getValidatedBaseUrl(runContext);
        Integer minorVersion = this.getValidatedMinorVersion(runContext);
        String rReportName = runContext.render(this.reportName).as(String.class)
            .orElseThrow(() -> new IllegalVariableEvaluationException("reportName is required, set the 'reportName' property"));

        StringBuilder endpointBuilder = new StringBuilder(baseUrl)
            .append("/v3/company/")
            .append(rRealmId)
            .append("/reports/")
            .append(URLEncoder.encode(rReportName, StandardCharsets.UTF_8))
            .append("?minorversion=")
            .append(minorVersion);

        Map<String, String> rParameters = null;
        if (this.parameters != null) {
            rParameters = runContext.render(this.parameters).asMap(String.class, String.class);
        }
        if (rParameters != null && !rParameters.isEmpty()) {
            String queryParams = rParameters.entrySet().stream()
                .map(e -> URLEncoder.encode(e.getKey(), StandardCharsets.UTF_8) + "=" + URLEncoder.encode(e.getValue(), StandardCharsets.UTF_8))
                .collect(Collectors.joining("&"));
            endpointBuilder.append("&").append(queryParams);
        }

        try (HttpClient client = HttpClient.builder().runContext(runContext).build()) {
            HttpRequest request = HttpRequest.builder()
                .uri(URI.create(endpointBuilder.toString()))
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
            title = "Report Data",
            description = "The fully parsed JSON report object containing header, columns, and rows."
        )
        private final Map<String, Object> row;
    }
}
