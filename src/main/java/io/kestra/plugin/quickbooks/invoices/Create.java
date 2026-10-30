package io.kestra.plugin.quickbooks.invoices;

import java.util.Map;

import io.kestra.core.models.annotations.Example;
import io.kestra.core.models.annotations.Plugin;
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
        **Write operation**: creates a new invoice in the QuickBooks company (`POST /invoice`). Running the task twice creates two invoices, so do not retry it blindly.
        The `invoice` body needs at least `CustomerRef` and one `Line`."""
)
public class Create extends AbstractInvoiceWrite {

    @Override
    protected Map<String, Object> body(RunContext runContext, Map<String, Object> rInvoice) {
        return rInvoice;
    }
}
