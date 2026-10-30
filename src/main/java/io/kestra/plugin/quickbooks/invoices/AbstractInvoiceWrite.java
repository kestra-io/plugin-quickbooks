package io.kestra.plugin.quickbooks.invoices;

import java.math.BigDecimal;
import java.net.URI;
import java.util.Map;

import jakarta.validation.constraints.NotNull;

import io.kestra.core.exceptions.IllegalVariableEvaluationException;
import io.kestra.core.http.HttpRequest;
import io.kestra.core.http.client.HttpClient;
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

/**
 * Shared flow of the invoice write tasks: both create and update are a POST on /invoice.
 */
@SuperBuilder
@ToString
@EqualsAndHashCode
@Getter
@NoArgsConstructor
public abstract class AbstractInvoiceWrite extends AbstractQuickBooksConnection implements RunnableTask<AbstractInvoiceWrite.Output> {

    @Schema(
        title = "Invoice body",
        description = "The QuickBooks `Invoice` object as a map, using QuickBooks field names (`CustomerRef`, `Line`, ...). See https://developer.intuit.com/app/developer/qbo/docs/api/accounting/all-entities/invoice."
    )
    @PluginProperty(group = "main")
    @NotNull
    protected Property<Map<String, Object>> invoice;

    /**
     * Builds the JSON body to POST.
     */
    protected abstract Map<String, Object> body(RunContext runContext, Map<String, Object> rInvoice) throws Exception;

    @Override
    public Output run(RunContext runContext) throws Exception {
        var rInvoice = runContext.render(invoice).asMap(String.class, Object.class);
        if (rInvoice.isEmpty()) {
            throw new IllegalVariableEvaluationException("invoice is required, set the 'invoice' property");
        }
        var body = body(runContext, rInvoice);

        var accessToken = getAccessToken(runContext);
        var url = getValidatedBaseUrl(runContext) + "/v3/company/" + getValidatedRealmId(runContext)
            + "/invoice?minorversion=" + getValidatedMinorVersion(runContext);

        try (HttpClient client = HttpClient.builder().runContext(runContext).build()) {
            var request = HttpRequest.builder()
                .uri(URI.create(url))
                .method("POST")
                .addHeader("Authorization", "Bearer " + accessToken)
                .addHeader("Accept", "application/json")
                .body(HttpRequest.StringRequestBody.builder()
                    .content(JacksonMapper.ofJson().writeValueAsString(body))
                    .contentType("application/json")
                    .charset(java.nio.charset.StandardCharsets.UTF_8)
                    .build())
                .build();

            var responseBody = sendWithBackoff(client, request);
            Map<String, Object> response = JacksonMapper.ofJson().readValue(responseBody, new TypeReference<Map<String, Object>>() {});

            @SuppressWarnings("unchecked")
            var row = response.get("Invoice") instanceof Map<?, ?> m ? (Map<String, Object>) m : Map.<String, Object>of();

            var id = row.get("Id") != null ? row.get("Id").toString() : null;
            runContext.logger().info("Invoice {} written (SyncToken {})", id, row.get("SyncToken"));

            return Output.builder()
                .row(row)
                .id(id)
                .syncToken(row.get("SyncToken") != null ? row.get("SyncToken").toString() : null)
                .totalAmt(row.get("TotalAmt") != null ? new BigDecimal(row.get("TotalAmt").toString()) : null)
                .build();
        }
    }

    @Builder
    @Getter
    public static class Output implements io.kestra.core.models.tasks.Output {
        @Schema(
            title = "The invoice",
            description = "The created or updated invoice as returned by QuickBooks."
        )
        private final Map<String, Object> row;

        @Schema(
            title = "Invoice ID",
            description = "The `Id` of the invoice."
        )
        private final String id;

        @Schema(
            title = "Sync token",
            description = "The new `SyncToken`, required for the next update of this invoice."
        )
        private final String syncToken;

        @Schema(
            title = "Total amount",
            description = "The `TotalAmt` of the invoice."
        )
        private final BigDecimal totalAmt;
    }
}
