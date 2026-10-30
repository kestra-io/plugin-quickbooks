package io.kestra.plugin.quickbooks;

import io.kestra.core.models.annotations.Example;
import io.kestra.core.models.annotations.Plugin;
import io.kestra.core.junit.annotations.KestraTest;
import io.kestra.core.models.validations.ModelValidator;
import io.kestra.core.serializers.YamlParser;
import io.kestra.core.models.flows.Flow;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import java.util.List;

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

    @Test
    void listExamplesAreValidWithoutQuery() {
        var listTasks = List.of(
            io.kestra.plugin.quickbooks.accounts.List.class,
            io.kestra.plugin.quickbooks.bills.List.class,
            io.kestra.plugin.quickbooks.customers.List.class,
            io.kestra.plugin.quickbooks.invoices.List.class,
            io.kestra.plugin.quickbooks.items.List.class,
            io.kestra.plugin.quickbooks.payments.List.class,
            io.kestra.plugin.quickbooks.vendors.List.class
        );

        for (var taskClass : listTasks) {
            for (Example example : taskClass.getAnnotation(Plugin.class).examples()) {
                var flow = YamlParser.parse(String.join("\n", example.code()), Flow.class);
                assertThat(taskClass.getName() + ": " + modelValidator.isValid(flow), modelValidator.isValid(flow).isEmpty(), is(true));
            }
        }
    }

    @Test
    void queryWithoutQueryIsRejected() {
        var flow = YamlParser.parse("""
            id: quickbooks_query_missing
            namespace: company.team

            tasks:
              - id: q
                type: io.kestra.plugin.quickbooks.query.Query
                clientId: "cid"
                clientSecret: "csec"
                refreshToken: "rt"
                realmId: "123456"
            """, Flow.class);

        assertThat(modelValidator.isValid(flow).isPresent(), is(true));
    }

    @Test
    void triggerExamplesAreValid() {
        var triggers = List.of(
            io.kestra.plugin.quickbooks.trigger.CdcTrigger.class,
            io.kestra.plugin.quickbooks.trigger.WebhookTrigger.class,
            io.kestra.plugin.quickbooks.invoices.InvoiceTrigger.class,
            io.kestra.plugin.quickbooks.invoices.OverdueInvoiceTrigger.class,
            io.kestra.plugin.quickbooks.payments.PaymentReceivedTrigger.class
        );

        for (var triggerClass : triggers) {
            for (Example example : triggerClass.getAnnotation(Plugin.class).examples()) {
                var flow = YamlParser.parse(String.join("\n", example.code()), Flow.class);
                assertThat(triggerClass.getName() + ": " + modelValidator.isValid(flow), modelValidator.isValid(flow).isEmpty(), is(true));
            }
        }
    }

    @Test
    void invoiceTriggerAcceptsLowercaseEvent() {
        var flow = YamlParser.parse("""
            id: quickbooks_invoice_event
            namespace: company.team

            triggers:
              - id: on_invoice
                type: io.kestra.plugin.quickbooks.invoices.InvoiceTrigger
                clientId: "cid"
                clientSecret: "csec"
                refreshToken: "rt"
                realmId: "123456"
                event: updated

            tasks:
              - id: log
                type: io.kestra.plugin.core.log.Log
                message: hi
            """, Flow.class);

        assertThat(modelValidator.isValid(flow).isEmpty(), is(true));
    }
}
