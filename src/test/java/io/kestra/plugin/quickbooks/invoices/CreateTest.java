package io.kestra.plugin.quickbooks.invoices;

import io.kestra.core.models.property.Property;
import io.kestra.plugin.quickbooks.AbstractTaskWireMockTest;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;

public class CreateTest extends AbstractTaskWireMockTest {

    @Test
    void create() throws Exception {
        stubFor(post(urlPathEqualTo("/v3/company/12345/invoice"))
            .withQueryParam("minorversion", equalTo("75"))
            .withHeader("Content-Type", containing("application/json"))
            .withRequestBody(matchingJsonPath("$.CustomerRef.value", equalTo("58")))
            .withRequestBody(matchingJsonPath("$.Line[0].Amount", equalTo("150.0")))
            .willReturn(aResponse()
                .withHeader("Content-Type", "application/json")
                .withBody("{\"Invoice\": {\"Id\": \"131\", \"SyncToken\": \"0\", \"TotalAmt\": 150.0}}")));

        var run = Create.builder()
            .clientId(Property.ofValue("client_id"))
            .clientSecret(Property.ofValue("client_secret"))
            .refreshToken(Property.ofValue("refresh_token"))
            .realmId(Property.ofValue("12345"))
            .baseUrl(Property.ofValue("http://localhost:" + wireMockServer.port()))
            .authUrl(Property.ofValue("http://localhost:" + wireMockServer.port() + "/oauth2/v1/tokens/bearer"))
            .invoice(Property.ofValue(Map.of(
                "CustomerRef", Map.of("value", "58"),
                "Line", List.of(Map.of("Amount", 150.0, "DetailType", "SalesItemLineDetail")))))
            .build()
            .run(runContext());

        assertThat(run.getId(), is("131"));
        assertThat(run.getSyncToken(), is("0"));
        assertThat(run.getTotalAmt().compareTo(new BigDecimal("150.0")), is(0));
        assertThat(run.getRow().get("Id"), is("131"));
    }
}
