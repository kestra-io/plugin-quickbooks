package io.kestra.plugin.quickbooks.payments;

import io.kestra.core.models.annotations.Example;
import io.kestra.core.models.annotations.Plugin;
import io.kestra.plugin.quickbooks.AbstractGet;
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
            title = "Get a Payment by ID",
            full = true,
            code = """
                id: get_payments
                namespace: company.team
                tasks:
                  - id: get
                    type: io.kestra.plugin.quickbooks.payments.Get
                    clientId: "{{ secret('QBO_CLIENT_ID') }}"
                    clientSecret: "{{ secret('QUICKBOOKS_CLIENT_SECRET') }}"
                    refreshToken: "{{ secret('QUICKBOOKS_REFRESH_TOKEN') }}"
                    realmId: "{{ secret('QBO_REALM_ID') }}"
                    entityId: "123"
                """
        )
    }
)
@Schema(title = "Get a Payment by ID", description = "Get a Payment by ID")
public class Get extends AbstractGet {

    @Override
    protected String entityPath() {
        return "payment";
    }
}
