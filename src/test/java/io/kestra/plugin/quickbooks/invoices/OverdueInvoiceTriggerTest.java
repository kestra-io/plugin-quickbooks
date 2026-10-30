package io.kestra.plugin.quickbooks.invoices;

import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.Map;

import io.kestra.core.models.flows.Flow;
import io.kestra.core.models.property.Property;
import io.kestra.core.serializers.YamlParser;
import io.kestra.plugin.quickbooks.AbstractTriggerWireMockTest;
import org.junit.jupiter.api.Test;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.containing;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.stubFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.not;

class OverdueInvoiceTriggerTest extends AbstractTriggerWireMockTest {
    private static final ZonedDateTime DATE = ZonedDateTime.parse("2023-09-01T12:00:00Z");

    private void stubOverdue(String... invoices) {
        var today = LocalDate.now(ZoneOffset.UTC);
        stubFor(get(urlPathEqualTo("/v3/company/12345/query"))
            .withQueryParam("query", containing("Balance > '0' AND DueDate < '" + today + "'"))
            .willReturn(aResponse().withHeader("Content-Type", "application/json")
                .withBody("{\"QueryResponse\": {\"Invoice\": [" + String.join(",", invoices) + "]}}")));
    }

    private static String invoice(String id, LocalDate due) {
        return "{\"Id\": \"" + id + "\", \"DocNumber\": \"D-" + id + "\", \"Balance\": 10.0, \"DueDate\": \"" + due + "\"}";
    }

    private OverdueInvoiceTrigger trigger(OverdueInvoiceTrigger.Mode mode) {
        return OverdueInvoiceTrigger.builder()
            .id("overdue")
            .type(OverdueInvoiceTrigger.class.getName())
            .clientId(Property.ofValue("client_id"))
            .clientSecret(Property.ofValue("client_secret"))
            .refreshToken(Property.ofValue("refresh_token"))
            .realmId(Property.ofValue("12345"))
            .baseUrl(Property.ofValue(baseUrl()))
            .authUrl(Property.ofValue(authUrl()))
            .mode(Property.ofValue(mode))
            .build();
    }

    @Test
    @SuppressWarnings("unchecked")
    void batchReportsAllOnceThenDeduplicates() throws Exception {
        var today = LocalDate.now(ZoneOffset.UTC);
        stubOverdue(invoice("1", today.minusDays(3)), invoice("2", today.minusDays(10)));
        var trigger = trigger(OverdueInvoiceTrigger.Mode.BATCH);

        var first = evaluate(trigger, "overdue", DATE);

        assertThat(first.isPresent(), is(true));
        var variables = variables(first.get());
        assertThat(variables.get("count"), is(2));
        assertThat(variables.get("daysOverdue"), is(10));
        var ids = ((List<Map<String, Object>>) variables.get("invoices")).stream().map(i -> i.get("Id")).toList();
        assertThat(ids, is(List.of("2", "1")));

        // Same overdue invoices on the next poll: nothing new to report.
        assertThat(evaluate(trigger, "overdue", DATE).isEmpty(), is(true));

        // A newly overdue invoice is reported alone.
        stubOverdue(invoice("1", today.minusDays(3)), invoice("2", today.minusDays(10)), invoice("3", today.minusDays(1)));
        var third = evaluate(trigger, "overdue", DATE);
        assertThat(third.isPresent(), is(true));
        assertThat(variables(third.get()).get("count"), is(1));
        assertThat(variables(third.get()).get("daysOverdue"), is(1));
    }

    @Test
    @SuppressWarnings("unchecked")
    void perInvoiceReportsOneInvoicePerEvaluation() throws Exception {
        var today = LocalDate.now(ZoneOffset.UTC);
        stubOverdue(invoice("1", today.minusDays(3)), invoice("2", today.minusDays(10)));
        var trigger = trigger(OverdueInvoiceTrigger.Mode.PER_INVOICE);

        var first = evaluate(trigger, "overdue", DATE).orElseThrow();
        var second = evaluate(trigger, "overdue", DATE).orElseThrow();

        assertThat(((List<Map<String, Object>>) variables(first).get("invoices")).get(0).get("Id"), is("2"));
        assertThat(variables(first).get("count"), is(1));
        assertThat(variables(first).get("daysOverdue"), is(10));
        assertThat(((List<Map<String, Object>>) variables(second).get("invoices")).get(0).get("Id"), is("1"));
        assertThat(evaluate(trigger, "overdue", DATE).isEmpty(), is(true));
    }

    @Test
    @SuppressWarnings("unchecked")
    void modeIsReadFromYaml() throws Exception {
        var today = LocalDate.now(ZoneOffset.UTC);
        stubOverdue(invoice("1", today.minusDays(3)), invoice("2", today.minusDays(10)));

        var flow = YamlParser.parse("""
            id: overdue
            namespace: company.team
            triggers:
              - id: overdue
                type: io.kestra.plugin.quickbooks.invoices.OverdueInvoiceTrigger
                clientId: cid
                clientSecret: csec
                refreshToken: rt
                realmId: "12345"
                baseUrl: "%s"
                authUrl: "%s"
                mode: PER_INVOICE
            tasks:
              - id: log
                type: io.kestra.plugin.core.log.Log
                message: hi
            """.formatted(baseUrl(), authUrl()), Flow.class);

        var result = evaluate((OverdueInvoiceTrigger) flow.getTriggers().get(0), "overdue", DATE).orElseThrow();

        assertThat(variables(result).get("count"), is(1));
    }

    @Test
    void paidInvoiceCanBeReportedAgainIfItBecomesOverdueAgain() throws Exception {
        var today = LocalDate.now(ZoneOffset.UTC);
        var trigger = trigger(OverdueInvoiceTrigger.Mode.BATCH);

        stubOverdue(invoice("1", today.minusDays(3)));
        assertThat(evaluate(trigger, "overdue", DATE).isPresent(), is(true));

        // Invoice 1 is paid, so it leaves the overdue set and its de-dup entry is dropped.
        stubOverdue();
        assertThat(evaluate(trigger, "overdue", DATE).isEmpty(), is(true));

        stubOverdue(invoice("1", today.minusDays(3)));
        assertThat(evaluate(trigger, "overdue", DATE).isPresent(), is(true));
    }

    @Test
    void toStringDoesNotPrintSecrets() {
        var text = OverdueInvoiceTrigger.builder()
            .clientId(Property.ofValue("super-client-id"))
            .clientSecret(Property.ofValue("super-client-secret"))
            .refreshToken(Property.ofValue("super-refresh-token"))
            .build()
            .toString();

        assertThat(text, not(containsString("super-client-id")));
        assertThat(text, not(containsString("super-client-secret")));
        assertThat(text, not(containsString("super-refresh-token")));
    }
}
