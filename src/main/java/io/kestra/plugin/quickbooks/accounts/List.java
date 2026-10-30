package io.kestra.plugin.quickbooks.accounts;

import io.kestra.core.models.annotations.Example;
import io.kestra.core.models.annotations.Plugin;
import io.kestra.plugin.quickbooks.AbstractList;
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
            title = "List Account records",
            full = true,
            code = """
                id: list_accounts
                namespace: company.team
                tasks:
                  - id: list
                    type: io.kestra.plugin.quickbooks.accounts.List
                    clientId: "{{ secret('QBO_CLIENT_ID') }}"
                    clientSecret: "{{ secret('QUICKBOOKS_CLIENT_SECRET') }}"
                    refreshToken: "{{ secret('QUICKBOOKS_REFRESH_TOKEN') }}"
                    realmId: "{{ secret('QBO_REALM_ID') }}"
                    fetchType: FETCH
                """
        )
    }
)
@Schema(title = "List Account records", description = "List Account records")
public class List extends AbstractList {

    @Override
    protected String entityName() {
        return "Account";
    }
}
