package io.kestra.plugin.quickbooks;

import io.kestra.core.junit.annotations.KestraTest;
import io.kestra.core.models.validations.ModelValidator;
import io.kestra.core.serializers.YamlParser;
import io.kestra.core.models.flows.Flow;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;

// Unit tests call run() directly; this one goes through flow validation like the UI does.
@KestraTest
class FlowValidationTest {
    @Inject
    private ModelValidator modelValidator;

    @Test
    void flowWithQuickBooksTaskIsValid() {
        var flow = YamlParser.parse("""
            id: quickbooks_validation
            namespace: company.team

            tasks:
              - id: company_info
                type: io.kestra.plugin.quickbooks.companyinfo.Get
                clientId: "{{ secret('QB_CLIENT_ID') }}"
                clientSecret: "{{ secret('QB_CLIENT_SECRET') }}"
                refreshToken: "{{ secret('QB_REFRESH_TOKEN') }}"
                realmId: "123456"
                minorVersion: 75
            """, Flow.class);

        assertThat(modelValidator.isValid(flow).isEmpty(), is(true));
    }
}
