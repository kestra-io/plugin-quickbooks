package io.kestra.plugin.quickbooks.invoices;

import io.kestra.core.models.property.Property;
import io.kestra.plugin.quickbooks.AbstractTaskWireMockTest;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;

public class UpdateTest extends AbstractTaskWireMockTest {

    private Update.UpdateBuilder<?, ?> builder() {
        return Update.builder()
            .clientId(Property.ofValue("client_id"))
            .clientSecret(Property.ofValue("client_secret"))
            .refreshToken(Property.ofValue("refresh_token"))
            .realmId(Property.ofValue("12345"))
            .baseUrl(Property.ofValue("http://localhost:" + wireMockServer.port()))
            .authUrl(Property.ofValue("http://localhost:" + wireMockServer.port() + "/oauth2/v1/tokens/bearer"));
    }

    @Test
    void sparseUpdateSendsIdSyncTokenAndSparse() throws Exception {
        stubFor(post(urlPathEqualTo("/v3/company/12345/invoice"))
            .withRequestBody(matchingJsonPath("$.sparse", equalTo("true")))
            .withRequestBody(matchingJsonPath("$.Id", equalTo("130")))
            .withRequestBody(matchingJsonPath("$.SyncToken", equalTo("2")))
            .withRequestBody(matchingJsonPath("$.PrivateNote", equalTo("hello")))
            .willReturn(aResponse()
                .withHeader("Content-Type", "application/json")
                .withBody("{\"Invoice\": {\"Id\": \"130\", \"SyncToken\": \"3\", \"TotalAmt\": 10}}")));

        var run = builder()
            .invoiceId(Property.ofValue("130"))
            .syncToken(Property.ofValue("2"))
            // the explicit properties win over values smuggled in the body
            .invoice(Property.ofValue(Map.of("PrivateNote", "hello", "Id", "999", "SyncToken", "0")))
            .build()
            .run(runContext());

        assertThat(run.getSyncToken(), is("3"));
        assertThat(run.getId(), is("130"));
    }

    @Test
    void nonSparseUpdate() throws Exception {
        stubFor(post(urlPathEqualTo("/v3/company/12345/invoice"))
            .withRequestBody(matchingJsonPath("$.sparse", equalTo("false")))
            .willReturn(aResponse()
                .withHeader("Content-Type", "application/json")
                .withBody("{\"Invoice\": {\"Id\": \"130\", \"SyncToken\": \"3\"}}")));

        var run = builder()
            .invoiceId(Property.ofValue("130"))
            .syncToken(Property.ofValue("2"))
            .sparse(Property.ofValue(false))
            .invoice(Property.ofValue(Map.of("PrivateNote", "x")))
            .build()
            .run(runContext());

        assertThat(run.getSyncToken(), is("3"));
    }
}
