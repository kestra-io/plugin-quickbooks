package io.kestra.plugin.quickbooks.payments;

import io.kestra.core.models.property.Property;
import io.kestra.plugin.quickbooks.AbstractTaskWireMockTest;
import org.junit.jupiter.api.Test;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.notNullValue;

public class GetTest extends AbstractTaskWireMockTest {

    @Test
    void testGet() throws Exception {
        stubFor(get(urlPathEqualTo("/v3/company/12345/payment/1"))
            .withQueryParam("minorversion", equalTo("75"))
            .willReturn(aResponse()
                .withHeader("Content-Type", "application/json")
                .withBody("{\"Payment\": {\"Id\": \"1\", \"TotalAmt\": 50.0}}")));

        Get task = Get.builder()
            .clientId(Property.ofValue("client_id"))
            .clientSecret(Property.ofValue("client_secret"))
            .refreshToken(Property.ofValue("refresh_token"))
            .realmId(Property.ofValue("12345"))
            .baseUrl(Property.ofValue("http://localhost:" + wireMockServer.port()))
            .authUrl(Property.ofValue("http://localhost:" + wireMockServer.port() + "/oauth2/v1/tokens/bearer"))
            .entityId(Property.ofValue("1"))
            .build();

        var run = task.run(runContext());

        assertThat(run, notNullValue());
        assertThat(run.getRow(), notNullValue());
        assertThat(run.getRow().get("Payment"), notNullValue());
    }
}
