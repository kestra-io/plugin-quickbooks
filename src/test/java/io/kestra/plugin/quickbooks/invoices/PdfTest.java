package io.kestra.plugin.quickbooks.invoices;

import io.kestra.core.http.client.HttpClientResponseException;
import io.kestra.core.models.property.Property;
import io.kestra.plugin.quickbooks.AbstractTaskWireMockTest;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.util.Arrays;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;
import static org.junit.jupiter.api.Assertions.assertThrows;

public class PdfTest extends AbstractTaskWireMockTest {

    private Pdf.PdfBuilder<?, ?> builder() {
        return Pdf.builder()
            .clientId(Property.ofValue("client_id"))
            .clientSecret(Property.ofValue("client_secret"))
            .refreshToken(Property.ofValue("refresh_token"))
            .realmId(Property.ofValue("12345"))
            .baseUrl(Property.ofValue("http://localhost:" + wireMockServer.port()))
            .authUrl(Property.ofValue("http://localhost:" + wireMockServer.port() + "/oauth2/v1/tokens/bearer"));
    }

    @Test
    void storesBinaryBodyUnchanged() throws Exception {
        // non UTF-8 bytes: a text based read would corrupt them
        byte[] pdf = new byte[256 * 1024];
        for (int i = 0; i < pdf.length; i++) {
            pdf[i] = (byte) i;
        }
        stubFor(get(urlPathEqualTo("/v3/company/12345/invoice/130/pdf"))
            .withHeader("Accept", equalTo("application/pdf"))
            .withQueryParam("minorversion", equalTo("75"))
            .willReturn(aResponse().withHeader("Content-Type", "application/pdf").withBody(pdf)));

        var ctx = runContext();
        var run = builder().invoiceId(Property.ofValue("130")).build().run(ctx);

        byte[] stored;
        try (InputStream in = ctx.storage().getFile(run.getUri())) {
            var out = new ByteArrayOutputStream();
            in.transferTo(out);
            stored = out.toByteArray();
        }
        assertThat(Arrays.equals(stored, pdf), is(true));
        assertThat(run.getSize(), is((long) pdf.length));
    }

    @Test
    void notFoundFails() {
        stubFor(get(urlPathEqualTo("/v3/company/12345/invoice/999/pdf"))
            .willReturn(aResponse().withStatus(404).withBody("{\"Fault\": {}}")));

        assertThrows(HttpClientResponseException.class, () -> builder().invoiceId(Property.ofValue("999")).build().run(runContext()));
    }
}
