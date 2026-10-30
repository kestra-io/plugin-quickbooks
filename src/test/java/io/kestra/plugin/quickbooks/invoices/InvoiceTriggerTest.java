package io.kestra.plugin.quickbooks.invoices;

import java.time.ZonedDateTime;
import java.util.List;
import java.util.Map;

import io.kestra.core.models.flows.Flow;
import io.kestra.core.models.property.Property;
import io.kestra.core.serializers.YamlParser;
import io.kestra.plugin.quickbooks.AbstractTriggerWireMockTest;
import org.junit.jupiter.api.Test;

import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.not;

class InvoiceTriggerTest extends AbstractTriggerWireMockTest {
    private static final ZonedDateTime SINCE = ZonedDateTime.parse("2023-09-01T12:00:00Z");

    private static final String CDC = """
        {"CDCResponse": [{"QueryResponse": [{"Invoice": [
          {"Id": "1", "DocNumber": "A-1", "TotalAmt": 100.0, "Balance": 100.0,
           "MetaData": {"CreateTime": "2023-09-01T13:00:00Z", "LastUpdatedTime": "2023-09-01T13:00:00Z"}},
          {"Id": "2", "DocNumber": "A-2", "TotalAmt": 50.0, "Balance": 0,
           "MetaData": {"CreateTime": "2023-08-01T10:00:00Z", "LastUpdatedTime": "2023-09-01T14:00:00Z"}},
          {"Id": "3", "status": "Deleted", "domain": "QBO"}
        ]}]}], "time": "2023-09-02T12:00:00Z"}
        """;

    private InvoiceTrigger.InvoiceTriggerBuilder<?, ?> builder() {
        return InvoiceTrigger.builder()
            .id("invoices")
            .type(InvoiceTrigger.class.getName())
            .clientId(Property.ofValue("client_id"))
            .clientSecret(Property.ofValue("client_secret"))
            .refreshToken(Property.ofValue("refresh_token"))
            .realmId(Property.ofValue("12345"))
            .baseUrl(Property.ofValue(baseUrl()))
            .authUrl(Property.ofValue(authUrl()));
    }

    @Test
    @SuppressWarnings("unchecked")
    void createdReportsOnlyNewInvoices() throws Exception {
        stubJson("/v3/company/12345/cdc", CDC);

        var result = evaluate(builder().event(Property.ofValue("created")).build(), "invoices", SINCE);

        assertThat(result.isPresent(), is(true));
        var variables = variables(result.get());
        assertThat(((Map<String, Object>) variables.get("invoice")).get("Id"), is("1"));
        assertThat(((List<?>) variables.get("invoices")).size(), is(1));
        assertThat(variables.get("count"), is(1));
    }

    @Test
    @SuppressWarnings("unchecked")
    void updatedSkipsInvoicesCreatedInTheWindowAndDeletedOnes() throws Exception {
        stubJson("/v3/company/12345/cdc", CDC);

        var result = evaluate(builder().event(Property.ofValue("UPDATED")).build(), "invoices", SINCE);

        assertThat(result.isPresent(), is(true));
        assertThat(((Map<String, Object>) variables(result.get()).get("invoice")).get("Id"), is("2"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void paidFromLowercaseYaml() throws Exception {
        stubJson("/v3/company/12345/cdc", CDC);

        var flow = YamlParser.parse("""
            id: paid
            namespace: company.team
            triggers:
              - id: invoices
                type: io.kestra.plugin.quickbooks.invoices.InvoiceTrigger
                clientId: cid
                clientSecret: csec
                refreshToken: rt
                realmId: "12345"
                baseUrl: "%s"
                authUrl: "%s"
                event: paid
            tasks:
              - id: log
                type: io.kestra.plugin.core.log.Log
                message: hi
            """.formatted(baseUrl(), authUrl()), Flow.class);

        var result = evaluate((InvoiceTrigger) flow.getTriggers().get(0), "invoices", SINCE);

        assertThat(result.isPresent(), is(true));
        assertThat(((Map<String, Object>) variables(result.get()).get("invoice")).get("Id"), is("2"));
    }

    @Test
    void noMatchStartsNothingAndAdvancesTheWatermark() throws Exception {
        stubJson("/v3/company/12345/cdc", "{\"CDCResponse\": [{\"QueryResponse\": [{}]}]}");
        var trigger = builder().build();

        assertThat(evaluate(trigger, "invoices", SINCE).isEmpty(), is(true));
        assertThat(evaluate(trigger, "invoices", SINCE).isEmpty(), is(true));

        var cdcCalls = wireMockServer.findAll(getRequestedFor(urlPathEqualTo("/v3/company/12345/cdc")));
        assertThat(cdcCalls.size(), is(2));
        // The second poll starts from the first poll time, not from the trigger date again.
        assertThat(cdcCalls.get(0).queryParameter("changedSince").firstValue(), is("2023-09-01T12:00:00Z"));
        assertThat(cdcCalls.get(1).queryParameter("changedSince").firstValue(), not("2023-09-01T12:00:00Z"));
    }

    @Test
    void toStringDoesNotPrintSecrets() {
        var text = builder()
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
