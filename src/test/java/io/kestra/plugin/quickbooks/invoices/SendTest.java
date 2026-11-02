package io.kestra.plugin.quickbooks.invoices;

import io.kestra.core.http.client.HttpClientResponseException;
import io.kestra.core.models.property.Property;
import io.kestra.plugin.quickbooks.AbstractTaskWireMockTest;
import org.junit.jupiter.api.Test;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;
import static org.junit.jupiter.api.Assertions.assertThrows;

public class SendTest extends AbstractTaskWireMockTest {

    private Send.SendBuilder<?, ?> builder() {
        return Send.builder()
            .clientId(Property.ofValue("client_id"))
            .clientSecret(Property.ofValue("client_secret"))
            .refreshToken(Property.ofValue("refresh_token"))
            .realmId(Property.ofValue("12345"))
            .baseUrl(Property.ofValue("http://localhost:" + wireMockServer.port()))
            .authUrl(Property.ofValue("http://localhost:" + wireMockServer.port() + "/oauth2/v1/tokens/bearer"));
    }

    @Test
    void send() throws Exception {
        stubFor(post(urlPathEqualTo("/v3/company/12345/invoice/130/send"))
            .withQueryParam("sendTo", equalTo("a+b@example.com"))
            .withQueryParam("minorversion", equalTo("75"))
            .withHeader("Content-Type", containing("application/octet-stream"))
            .willReturn(aResponse()
                .withHeader("Content-Type", "application/json")
                .withBody("{\"Invoice\": {\"Id\": \"130\", \"EmailStatus\": \"EmailSent\"}}")));

        var run = builder().invoiceId(Property.ofValue("130")).sendTo(Property.ofValue("a+b@example.com")).build().run(runContext());

        assertThat(run.getEmailStatus(), is("EmailSent"));
        assertThat(run.getRow().get("Id"), is("130"));
    }

    @Test
    void sendWithoutRecipientOmitsSendTo() throws Exception {
        stubFor(post(urlPathEqualTo("/v3/company/12345/invoice/130/send"))
            .withQueryParam("sendTo", absent())
            .willReturn(aResponse()
                .withHeader("Content-Type", "application/json")
                .withBody("{\"Invoice\": {\"Id\": \"130\", \"EmailStatus\": \"EmailSent\"}}")));

        assertThat(builder().invoiceId(Property.ofValue("130")).build().run(runContext()).getEmailStatus(), is("EmailSent"));
    }

    @Test
    void apiErrorFails() {
        stubFor(post(urlPathEqualTo("/v3/company/12345/invoice/999/send"))
            .willReturn(aResponse().withStatus(400).withBody("{\"Fault\": {}}")));

        assertThrows(HttpClientResponseException.class, () -> builder().invoiceId(Property.ofValue("999")).build().run(runContext()));
    }
}
