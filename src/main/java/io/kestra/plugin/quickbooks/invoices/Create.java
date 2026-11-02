package io.kestra.plugin.quickbooks.invoices;

import java.util.Map;

import io.kestra.core.models.annotations.Example;
import io.kestra.core.models.annotations.Plugin;
import io.kestra.core.models.annotations.PluginProperty;
import io.kestra.core.models.property.Property;
import io.kestra.core.runners.RunContext;
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
@Plugin(
    examples = {
        @Example(
            title = "Create an invoice with one sales line",
            full = true,
            code = """
                id: create_invoice
                namespace: company.team
                tasks:
                  - id: create
                    type: io.kestra.plugin.quickbooks.invoices.Create
                    clientId: "{{ secret('QBO_CLIENT_ID') }}"
                    clientSecret: "{{ secret('QBO_CLIENT_SECRET') }}"
                    refreshToken: "{{ secret('QBO_REFRESH_TOKEN') }}"
                    realmId: "{{ secret('QBO_REALM_ID') }}"
                    requestId: "{{ execution.id }}-{{ taskrun.id }}"
                    invoice:
                      CustomerRef:
                        value: "58"
                      Line:
                        - Amount: 150.0
                          DetailType: SalesItemLineDetail
                          SalesItemLineDetail:
                            ItemRef:
                              value: "1"
                """
        )
    }
)
@Schema(
    title = "Create an invoice",
    description = """
        **Write operation**: creates a new invoice in the QuickBooks company (`POST /invoice`). Running the task twice creates two invoices. A retry after a timeout can also create a duplicate when QuickBooks processed the first request, so set `requestId` to make the call idempotent.
        The `invoice` body needs at least `CustomerRef` and one `Line`."""
)
public class Create extends AbstractInvoiceWrite {

    @Schema(
        title = "Request ID (idempotency key)",
        description = "Sent as the QuickBooks `requestid` query parameter, at most 50 characters. QuickBooks returns the result of the first request instead of creating a second invoice when it receives the same `requestid` again, which protects against duplicates when a retry follows a timeout. Use a value that is stable across the attempts of one run, for example `{{ execution.id }}-{{ taskrun.id }}`. Without it, a retry after a timeout can create a duplicate invoice."
    )
    @PluginProperty(group = "advanced")
    protected Property<String> requestId;

    @Override
    protected String requestId(RunContext runContext) throws Exception {
        return requestId == null ? null : runContext.render(requestId).as(String.class).orElse(null);
    }

    @Override
    protected Map<String, Object> body(RunContext runContext, Map<String, Object> rInvoice) {
        return rInvoice;
    }
}
