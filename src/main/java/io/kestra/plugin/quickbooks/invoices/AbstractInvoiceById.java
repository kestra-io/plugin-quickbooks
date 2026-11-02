package io.kestra.plugin.quickbooks.invoices;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;

import jakarta.validation.constraints.NotNull;

import io.kestra.core.exceptions.IllegalVariableEvaluationException;
import io.kestra.core.models.annotations.PluginProperty;
import io.kestra.core.models.property.Property;
import io.kestra.core.runners.RunContext;
import io.kestra.plugin.quickbooks.AbstractQuickBooksConnection;
import io.swagger.v3.oas.annotations.media.Schema;
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
public abstract class AbstractInvoiceById extends AbstractQuickBooksConnection {

    @Schema(
        title = "Invoice ID",
        description = "The QuickBooks id of the invoice."
    )
    @PluginProperty(group = "main")
    @NotNull
    protected Property<String> invoiceId;

    protected String renderId(RunContext runContext) throws Exception {
        return runContext.render(invoiceId).as(String.class)
            .filter(value -> !value.isBlank())
            .orElseThrow(() -> new IllegalVariableEvaluationException("invoiceId is required, set the 'invoiceId' property"));
    }

    /**
     * URL of an invoice sub-resource, without query string. The id is path-encoded.
     */
    protected String invoiceUrl(RunContext runContext, String rId, String suffix) throws Exception {
        return getValidatedBaseUrl(runContext) + "/v3/company/" + getValidatedRealmId(runContext)
            + "/invoice/" + URLEncoder.encode(rId, StandardCharsets.UTF_8).replace("+", "%20") + suffix;
    }
}
