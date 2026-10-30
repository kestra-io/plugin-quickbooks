package io.kestra.plugin.quickbooks.reports;

import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

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
            title = "Run the Profit and Loss report for the quarter",
            full = true,
            code = """
                id: qbo_profit_and_loss
                namespace: company.finance

                tasks:
                  - id: pnl
                    type: io.kestra.plugin.quickbooks.reports.Run
                    clientId: "{{ secret('QBO_CLIENT_ID') }}"
                    clientSecret: "{{ secret('QBO_CLIENT_SECRET') }}"
                    refreshToken: "{{ secret('QBO_REFRESH_TOKEN') }}"
                    realmId: "{{ secret('QBO_REALM_ID') }}"
                    report: ProfitAndLoss
                    startDate: "2026-01-01"
                    endDate: "2026-03-31"
                """
        ),
        @Example(
            title = "Run the Aged Receivables report on an accrual basis for one customer",
            full = true,
            code = """
                id: qbo_aged_receivables
                namespace: company.finance

                tasks:
                  - id: aged_receivables
                    type: io.kestra.plugin.quickbooks.reports.Run
                    clientId: "{{ secret('QBO_CLIENT_ID') }}"
                    clientSecret: "{{ secret('QBO_CLIENT_SECRET') }}"
                    refreshToken: "{{ secret('QBO_REFRESH_TOKEN') }}"
                    realmId: "{{ secret('QBO_REALM_ID') }}"
                    report: AgedReceivables
                    customer: "58"
                    accountingMethod: Accrual
                """
        )
    }
)
@Schema(
    title = "Run a QuickBooks report",
    description = "Runs a report of the QuickBooks Reports API and flattens its rows. The untouched response is also returned in `raw`."
)
public class Run extends AbstractQuickBooksConnection implements RunnableTask<Run.Output> {

    private static final List<String> REPORTS = List.of(
        "AgedReceivables",
        "AgedReceivableDetail",
        "AgedPayables",
        "ProfitAndLoss",
        "BalanceSheet",
        "GeneralLedger",
        "CustomerBalance",
        "TrialBalance",
        "CashFlow"
    );
    private static final List<String> ACCOUNTING_METHODS = List.of("Cash", "Accrual");

    @Schema(
        title = "Report",
        description = """
            The report to run. Case-insensitive, one of `AgedReceivables`, `AgedReceivableDetail`, `AgedPayables`, \
            `ProfitAndLoss`, `BalanceSheet`, `GeneralLedger`, `CustomerBalance`, `TrialBalance` or `CashFlow`."""
    )
    @PluginProperty(group = "main")
    @NotNull
    private Property<String> report;

    @Schema(
        title = "Start date",
        description = "First day of the report period (`yyyy-MM-dd`). Sent as `start_date`; QuickBooks applies its own default when unset."
    )
    @PluginProperty(group = "main")
    private Property<LocalDate> startDate;

    @Schema(
        title = "End date",
        description = "Last day of the report period (`yyyy-MM-dd`). Sent as `end_date`; QuickBooks applies its own default when unset."
    )
    @PluginProperty(group = "main")
    private Property<LocalDate> endDate;

    @Schema(
        title = "Summarize columns by",
        description = "How the columns are grouped, sent as `summarize_column_by`. For example `Total`, `Month`, `Week`, `Days`, `Quarter`, `Year`, `Customers` or `Vendors`. The accepted values depend on the report, see the QuickBooks documentation."
    )
    @PluginProperty(group = "processing")
    private Property<String> summarizeColumnBy;

    @Schema(
        title = "Customer",
        description = "Restricts the report to this customer id, sent as `customer`."
    )
    @PluginProperty(group = "processing")
    private Property<String> customer;

    @Schema(
        title = "Accounting method",
        description = "`Cash` or `Accrual`, case-insensitive. Sent as `accounting_method`; QuickBooks uses the company preference when unset."
    )
    @PluginProperty(group = "processing")
    private Property<String> accountingMethod;

    @Schema(
        title = "Additional report parameters",
        description = "Any other query parameter of the report, for example `department` or `class`. The typed properties win over a parameter with the same name."
    )
    @PluginProperty(group = "advanced")
    private Property<Map<String, String>> parameters;

    @Override
    public Output run(RunContext runContext) throws Exception {
        var rReport = parseChoice("report", runContext.render(this.report).as(String.class)
            .orElseThrow(() -> new IllegalVariableEvaluationException("report is required, set the 'report' property")), REPORTS);

        var rQuery = new LinkedHashMap<String, String>();
        var rParameters = this.parameters == null ? null : runContext.render(this.parameters).asMap(String.class, String.class);
        if (rParameters != null) {
            rQuery.putAll(rParameters);
        }
        putIfPresent(rQuery, "start_date", this.startDate == null ? null : runContext.render(this.startDate).as(LocalDate.class).orElse(null));
        putIfPresent(rQuery, "end_date", this.endDate == null ? null : runContext.render(this.endDate).as(LocalDate.class).orElse(null));
        putIfPresent(rQuery, "summarize_column_by", this.summarizeColumnBy == null ? null : runContext.render(this.summarizeColumnBy).as(String.class).orElse(null));
        putIfPresent(rQuery, "customer", this.customer == null ? null : runContext.render(this.customer).as(String.class).orElse(null));

        var rAccountingMethod = this.accountingMethod == null ? null : runContext.render(this.accountingMethod).as(String.class).orElse(null);
        if (rAccountingMethod != null && !rAccountingMethod.isBlank()) {
            rQuery.put("accounting_method", parseChoice("accountingMethod", rAccountingMethod, ACCOUNTING_METHODS));
        }

        var rAccessToken = this.getAccessToken(runContext);
        var rRealmId = this.getValidatedRealmId(runContext);
        var rBaseUrl = this.getValidatedBaseUrl(runContext);
        var rMinorVersion = this.getValidatedMinorVersion(runContext);

        var rEndpoint = rBaseUrl + "/v3/company/" + rRealmId + "/reports/" + URLEncoder.encode(rReport, StandardCharsets.UTF_8)
            + "?minorversion=" + rMinorVersion;
        if (!rQuery.isEmpty()) {
            rEndpoint += "&" + rQuery.entrySet().stream()
                .map(entry -> URLEncoder.encode(entry.getKey(), StandardCharsets.UTF_8) + "=" + URLEncoder.encode(entry.getValue(), StandardCharsets.UTF_8))
                .collect(Collectors.joining("&"));
        }

        try (var client = HttpClient.builder().runContext(runContext).build()) {
            var request = HttpRequest.builder()
                .uri(URI.create(rEndpoint))
                .method("GET")
                .addHeader("Authorization", "Bearer " + rAccessToken)
                .addHeader("Accept", "application/json")
                .build();

            var rResponseBody = sendWithBackoff(client, request);
            if (rResponseBody == null || rResponseBody.isBlank()) {
                throw new IllegalStateException("Empty response from the QuickBooks " + rReport + " report, check the report parameters and retry");
            }

            var rRaw = JacksonMapper.ofJson().readValue(rResponseBody, new TypeReference<Map<String, Object>>() {});

            return Output.builder()
                .rows(flatten(rRaw))
                .raw(rRaw)
                .build();
        }
    }

    private static void putIfPresent(Map<String, String> query, String name, Object value) {
        if (value != null && !value.toString().isBlank()) {
            query.put(name, value.toString());
        }
    }

    private static String parseChoice(String property, String value, List<String> allowed) {
        return allowed.stream()
            .filter(candidate -> candidate.equalsIgnoreCase(value.trim()))
            .findFirst()
            .orElseThrow(() -> new IllegalArgumentException(property + " must be one of " + String.join(", ", allowed) + ". Provided: " + value));
    }

    /**
     * Turns the nested {@code Rows.Row[]} tree into one map per data or summary row, keyed by column title,
     * with the enclosing section names in {@code section}. Section headers are skipped, they repeat the section name.
     */
    @SuppressWarnings("unchecked")
    static List<Map<String, Object>> flatten(Map<String, Object> report) {
        var rows = new ArrayList<Map<String, Object>>();
        var titles = new ArrayList<String>();
        if (report.get("Columns") instanceof Map<?, ?> columns && columns.get("Column") instanceof List<?> columnList) {
            for (var i = 0; i < columnList.size(); i++) {
                var title = columnList.get(i) instanceof Map<?, ?> column && column.get("ColTitle") != null ? column.get("ColTitle").toString() : "";
                titles.add(title.isBlank() ? "column" + (i + 1) : title);
            }
        }
        if (report.get("Rows") instanceof Map<?, ?> root) {
            collect((Map<String, Object>) root, titles, new ArrayList<>(), rows);
        }
        return rows;
    }

    @SuppressWarnings("unchecked")
    private static void collect(Map<String, Object> container, List<String> titles, List<String> path, List<Map<String, Object>> rows) {
        if (!(container.get("Row") instanceof List<?> children)) {
            return;
        }
        for (var child : children) {
            if (!(child instanceof Map<?, ?> row)) {
                continue;
            }
            var type = row.get("type") == null ? "Data" : row.get("type").toString();
            if (row.get("ColData") instanceof List<?> colData && !"Section".equals(type)) {
                rows.add(toRow(titles, colData, path, type));
            }
            if (row.get("Rows") instanceof Map<?, ?> nested) {
                var nestedPath = new ArrayList<>(path);
                var sectionName = sectionName(row);
                if (sectionName != null) {
                    nestedPath.add(sectionName);
                }
                collect((Map<String, Object>) nested, titles, nestedPath, rows);
            }
            if (row.get("Summary") instanceof Map<?, ?> summary && summary.get("ColData") instanceof List<?> summaryData) {
                rows.add(toRow(titles, summaryData, path, "Summary"));
            }
        }
    }

    private static String sectionName(Map<?, ?> row) {
        if (row.get("Header") instanceof Map<?, ?> header && header.get("ColData") instanceof List<?> colData && !colData.isEmpty()
            && colData.getFirst() instanceof Map<?, ?> first && first.get("value") != null && !first.get("value").toString().isBlank()) {
            return first.get("value").toString();
        }
        return null;
    }

    private static Map<String, Object> toRow(List<String> titles, List<?> colData, List<String> path, String type) {
        var row = new LinkedHashMap<String, Object>();
        for (var i = 0; i < colData.size(); i++) {
            var title = i < titles.size() ? titles.get(i) : "column" + (i + 1);
            row.put(title, colData.get(i) instanceof Map<?, ?> cell ? cell.get("value") : null);
        }
        row.put("section", String.join(" > ", path));
        row.put("type", type);
        return row;
    }

    @Builder
    @Getter
    public static class Output implements io.kestra.core.models.tasks.Output {
        @Schema(
            title = "Rows",
            description = "One map per data or summary row of the report, keyed by column title. `section` holds the enclosing section names joined with ` > ` and `type` is `Data` or `Summary`."
        )
        private final List<Map<String, Object>> rows;

        @Schema(
            title = "Raw report",
            description = "The parsed JSON response of QuickBooks, with `Header`, `Columns` and `Rows`."
        )
        private final Map<String, Object> raw;
    }
}
