package io.kestra.plugin.quickbooks.payments;

import java.time.ZonedDateTime;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

import io.kestra.core.models.annotations.Plugin;
import io.kestra.core.models.flows.Flow;
import io.kestra.core.models.property.Property;
import io.kestra.core.serializers.YamlParser;
import io.kestra.plugin.quickbooks.AbstractTriggerWireMockTest;
import org.junit.jupiter.api.Test;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.not;

class PaymentReceivedTriggerTest extends AbstractTriggerWireMockTest {
    private static final ZonedDateTime SINCE = ZonedDateTime.parse("2023-09-01T12:00:00Z");

    private static final String CDC = """
        {"CDCResponse": [{"QueryResponse": [{"Payment": [
          {"Id": "77", "TotalAmt": 150.0,
           "Line": [
             {"Amount": 100.0, "LinkedTxn": [{"TxnId": "10", "TxnType": "Invoice"}]},
             {"Amount": 50.0, "LinkedTxn": [{"TxnId": "11", "TxnType": "Invoice"}, {"TxnId": "12", "TxnType": "CreditMemo"}]}
           ],
           "MetaData": {"CreateTime": "2023-09-01T13:00:00Z", "LastUpdatedTime": "2023-09-01T13:00:00Z"}},
          {"Id": "78", "MetaData": {"CreateTime": "2023-07-01T13:00:00Z", "LastUpdatedTime": "2023-09-01T14:00:00Z"}},
          {"Id": "79", "status": "Deleted"}
        ]}]}], "time": "2023-09-02T12:00:00Z"}
        """;

    @Test
    @SuppressWarnings("unchecked")
    void reportsNewPaymentWithLinkedInvoices() throws Exception {
        stubJson("/v3/company/12345/cdc", CDC);

        var trigger = PaymentReceivedTrigger.builder()
            .id("payments")
            .type(PaymentReceivedTrigger.class.getName())
            .clientId(Property.ofValue("client_id"))
            .clientSecret(Property.ofValue("client_secret"))
            .refreshToken(Property.ofValue("refresh_token"))
            .realmId(Property.ofValue("12345"))
            .baseUrl(Property.ofValue(baseUrl()))
            .authUrl(Property.ofValue(authUrl()))
            .build();

        var result = evaluate(trigger, "payments", SINCE);

        assertThat(result.isPresent(), is(true));
        var variables = variables(result.get());
        assertThat(((Map<String, Object>) variables.get("payment")).get("Id"), is("77"));
        assertThat(variables.get("count"), is(1));
        var linked = (List<Map<String, Object>>) variables.get("linkedInvoices");
        assertThat(linked.stream().map(i -> i.get("id")).toList(), is(List.of("10", "11")));
    }

    @Test
    void issueExampleWorksAsWritten() throws Exception {
        stubJson("/v3/company/12345/cdc", CDC);

        var code = String.join("\n", PaymentReceivedTrigger.class.getAnnotation(Plugin.class).examples()[0].code())
            .replaceAll(Pattern.quote("\"{{ secret('QBO_REALM_ID') }}\""), "\"12345\"")
            .replaceAll("\"\\{\\{ secret\\('[A-Z_]+'\\) \\}\\}\"", "\"dummy\"")
            .replace("interval: PT10M", "interval: PT10M\n    baseUrl: \"" + baseUrl() + "\"\n    authUrl: \"" + authUrl() + "\"");
        var flow = YamlParser.parse(code, Flow.class);

        var result = evaluate((PaymentReceivedTrigger) flow.getTriggers().get(0), "on_payment", SINCE);

        assertThat(result.isPresent(), is(true));
        var message = runContext().render(
            "Payment {{ trigger.payment.id }} applied to {{ trigger.linkedInvoices | length }} invoice(s)",
            Map.of("trigger", variables(result.get()))
        );
        assertThat(message, is("Payment 77 applied to 2 invoice(s)"));
    }

    @Test
    void toStringDoesNotPrintSecrets() {
        var text = PaymentReceivedTrigger.builder()
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
