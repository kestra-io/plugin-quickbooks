package io.kestra.plugin.quickbooks.invoices;

import java.util.LinkedHashMap;
import java.util.Map;

import jakarta.validation.constraints.NotNull;

import io.kestra.core.exceptions.IllegalVariableEvaluationException;
import io.kestra.core.models.annotations.Example;
import io.kestra.core.models.annotations.Plugin;
import io.kestra.core.models.annotations.PluginProperty;
import io.kestra.core.models.property.Property;
import io.kestra.core.runners.RunContext;
import io.swagger.v3.oas.annotations.media.Schema;
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
            title = "Change the private note of an invoice with a sparse update",
            full = true,
            code = """
                id: update_invoice
                namespace: company.team
                tasks:
                  - id: update
                    type: io.kestra.plugin.quickbooks.invoices.Update
                    clientId: "{{ secret('QBO_CLIENT_ID') }}"
                    clientSecret: "{{ secret('QBO_CLIENT_SECRET') }}"
                    refreshToken: "{{ secret('QBO_REFRESH_TOKEN') }}"
                    realmId: "{{ secret('QBO_REALM_ID') }}"
                    invoiceId: "130"
                    syncToken: "2"
                    invoice:
                      PrivateNote: "Reviewed by finance"
                """
        )
    }
)
@Schema(
    title = "Update an invoice",
    description = """
        **Write operation**: modifies an existing invoice (`POST /invoice`). With `sparse: true` (default) only the fields in `invoice` change. With `sparse: false` QuickBooks performs a full update: every writable field you omit is reset, so send the complete invoice.
        `syncToken` must be the current one, otherwise QuickBooks rejects the update as stale. Read it with `invoices.Get` first, or reuse the `syncToken` output of a previous write."""
)
public class Update extends AbstractInvoiceWrite {

    @Schema(
        title = "Invoice ID",
        description = "The QuickBooks `Id` of the invoice to update. It overrides any `Id` in `invoice`."
    )
    @PluginProperty(group = "main")
    @NotNull
    protected Property<String> invoiceId;

    @Schema(
        title = "Sync token",
        description = "The current `SyncToken` of the invoice, used by QuickBooks for optimistic locking. It overrides any `SyncToken` in `invoice`."
    )
    @PluginProperty(group = "main")
    @NotNull
    protected Property<String> syncToken;

    @Schema(
        title = "Sparse update",
        description = "When true, only the fields given in `invoice` are updated.",
        defaultValue = "true"
    )
    @PluginProperty(group = "main")
    @Builder.Default
    protected Property<Boolean> sparse = Property.ofValue(true);

    @Override
    protected Map<String, Object> body(RunContext runContext, Map<String, Object> rInvoice) throws Exception {
        var rId = runContext.render(invoiceId).as(String.class).filter(v -> !v.isBlank())
            .orElseThrow(() -> new IllegalVariableEvaluationException("invoiceId is required, set the 'invoiceId' property"));
        var rSyncToken = runContext.render(syncToken).as(String.class).filter(v -> !v.isBlank())
            .orElseThrow(() -> new IllegalVariableEvaluationException("syncToken is required, set the 'syncToken' property"));

        var body = new LinkedHashMap<>(rInvoice);
        body.put("Id", rId);
        body.put("SyncToken", rSyncToken);
        body.put("sparse", runContext.render(sparse).as(Boolean.class).orElse(true));
        return body;
    }
}
