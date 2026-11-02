package io.kestra.plugin.quickbooks;

import io.kestra.core.models.annotations.Example;
import io.kestra.core.models.annotations.Plugin;
import io.kestra.core.junit.annotations.KestraTest;
import io.kestra.core.models.validations.ModelValidator;
import io.kestra.core.serializers.YamlParser;
import io.kestra.core.models.flows.Flow;
import io.kestra.plugin.quickbooks.companyinfo.Get;
import io.kestra.plugin.quickbooks.invoices.Send;
import io.kestra.plugin.quickbooks.invoices.Pdf;
import io.kestra.plugin.quickbooks.invoices.Create;
import io.kestra.plugin.quickbooks.invoices.Update;
import io.kestra.plugin.quickbooks.trigger.CdcTrigger;
import io.kestra.plugin.quickbooks.trigger.WebhookTrigger;
import io.kestra.plugin.quickbooks.invoices.InvoiceTrigger;
import io.kestra.plugin.quickbooks.invoices.OverdueInvoiceTrigger;
import io.kestra.plugin.quickbooks.payments.PaymentReceivedTrigger;
import io.kestra.plugin.quickbooks.reports.Run;
import io.kestra.plugin.quickbooks.query.Changes;
import io.kestra.plugin.quickbooks.query.Query;
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
                clientId: "{{ secret('QBO_CLIENT_ID') }}"
                clientSecret: "{{ secret('QBO_CLIENT_SECRET') }}"
                refreshToken: "{{ secret('QBO_REFRESH_TOKEN') }}"
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
    void invoiceTaskExamplesAreValid() {
        var tasks = List.of(
            io.kestra.plugin.quickbooks.invoices.List.class,
            Send.class,
            Pdf.class,
            Create.class,
            Update.class
        );

        for (var taskClass : tasks) {
            for (Example example : taskClass.getAnnotation(Plugin.class).examples()) {
                var flow = YamlParser.parse(String.join("\n", example.code()), Flow.class);
                assertThat(taskClass.getName() + ": " + modelValidator.isValid(flow), modelValidator.isValid(flow).isEmpty(), is(true));
            }
        }
    }

    @Test
    void invoiceListWithAllFiltersIsValid() {
        var flow = YamlParser.parse("""
            id: quickbooks_invoice_filters
            namespace: company.team

            tasks:
              - id: list
                type: io.kestra.plugin.quickbooks.invoices.List
                clientId: "cid"
                clientSecret: "csec"
                refreshToken: "rt"
                realmId: "123456"
                customerId: "58"
                status: OPEN
                dueBefore: "2026-03-01"
                dueAfter: "2026-02-01"
                txnDateFrom: "2026-01-01"
                txnDateTo: "2026-01-31"
                balanceMin: 10.5
                updatedSince: "2026-01-01T00:00:00Z"
                where: "Terms = '1'"
                fetchType: FETCH
            """, Flow.class);

        assertThat(modelValidator.isValid(flow).isEmpty(), is(true));
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
            CdcTrigger.class,
            WebhookTrigger.class,
            InvoiceTrigger.class,
            OverdueInvoiceTrigger.class,
            PaymentReceivedTrigger.class
        );

        for (var triggerClass : triggers) {
            for (Example example : triggerClass.getAnnotation(Plugin.class).examples()) {
                var flow = YamlParser.parse(String.join("\n", example.code()), Flow.class);
                assertThat(triggerClass.getName() + ": " + modelValidator.isValid(flow), modelValidator.isValid(flow).isEmpty(), is(true));
            }
        }
    }

    @Test
    void invoiceTriggerAcceptsUppercaseEvent() {
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
                event: UPDATED

            tasks:
              - id: log
                type: io.kestra.plugin.core.log.Log
                message: hi
            """, Flow.class);

        assertThat(modelValidator.isValid(flow).isEmpty(), is(true));
    }

    @Test
    void reportsRunIssueExampleIsValid() {
        var flow = YamlParser.parse("""
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
            """, Flow.class);

        assertThat(modelValidator.isValid(flow).isEmpty(), is(true));
    }

    @Test
    void reportsRunAcceptsAllTypedProperties() {
        var flow = YamlParser.parse("""
            id: qbo_report_all
            namespace: company.team

            tasks:
              - id: report
                type: io.kestra.plugin.quickbooks.reports.Run
                clientId: "cid"
                clientSecret: "csec"
                refreshToken: "rt"
                realmId: "123456"
                report: agedreceivables
                startDate: "2026-01-01"
                endDate: "2026-03-31"
                summarizeColumnBy: Month
                customer: "58"
                accountingMethod: ACCRUAL
                fetchType: STORE
                includeRaw: true
                parameters:
                  department: "1"
            """, Flow.class);

        assertThat(modelValidator.isValid(flow).isEmpty(), is(true));
    }

    @Test
    void reportAndChangesExamplesAreValid() {
        var tasks = List.of(
            Run.class,
            Changes.class,
            Query.class,
            Get.class
        );

        for (var taskClass : tasks) {
            for (Example example : taskClass.getAnnotation(Plugin.class).examples()) {
                var flow = YamlParser.parse(String.join("\n", example.code()), Flow.class);
                assertThat(taskClass.getName() + ": " + modelValidator.isValid(flow), modelValidator.isValid(flow).isEmpty(), is(true));
            }
        }
    }
}
