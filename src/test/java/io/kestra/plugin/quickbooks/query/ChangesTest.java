package io.kestra.plugin.quickbooks.query;

import java.util.List;
import java.util.Map;

import io.kestra.core.models.property.Property;
import io.kestra.plugin.quickbooks.AbstractTaskWireMockTest;

import org.junit.jupiter.api.Test;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;
import static org.junit.jupiter.api.Assertions.assertThrows;

public class ChangesTest extends AbstractTaskWireMockTest {

    private Changes.ChangesBuilder<?, ?> builder() {
        return Changes.builder()
            .clientId(Property.ofValue("client_id"))
            .clientSecret(Property.ofValue("client_secret"))
            .refreshToken(Property.ofValue("refresh_token"))
            .realmId(Property.ofValue("12345"))
            .baseUrl(Property.ofValue("http://localhost:" + wireMockServer.port()))
            .authUrl(Property.ofValue("http://localhost:" + wireMockServer.port() + "/oauth2/v1/tokens/bearer"));
    }

    @Test
    void returnsRowsPerEntity() throws Exception {
        stubFor(get(urlPathEqualTo("/v3/company/12345/cdc"))
            .withQueryParam("minorversion", equalTo("75"))
            .withQueryParam("entities", equalTo("Invoice,Customer,Payment"))
            .withQueryParam("changedSince", equalTo("2026-01-01T00:00:00Z"))
            .willReturn(aResponse()
                .withHeader("Content-Type", "application/json")
                .withBody("""
                    {"CDCResponse": [{"QueryResponse": [
                      {"Invoice": [
                        {"Id": "130", "MetaData": {"CreateTime": "2026-01-02T10:00:00Z", "LastUpdatedTime": "2026-01-02T10:00:00Z"}},
                        {"Id": "131", "MetaData": {"CreateTime": "2026-01-01T10:00:00Z", "LastUpdatedTime": "2026-01-03T10:00:00Z"}},
                        {"Id": "132", "status": "Deleted", "domain": "QBO"}
                      ]},
                      {"Customer": [{"Id": "58", "status": "Updated"}]}
                    ]}], "time": "2026-01-04T00:00:00Z"}""")));

        var run = builder()
            .entities(Property.ofValue(List.of("Invoice", "Customer", "Payment")))
            .changedSince(Property.ofValue("2026-01-01T00:00:00Z"))
            .build()
            .run(runContext());

        assertThat(run.getRows().get("Invoice"), is(List.of(
            Map.of("id", "130", "status", "Created", "deleted", false),
            Map.of("id", "131", "status", "Updated", "deleted", false),
            Map.of("id", "132", "status", "Deleted", "deleted", true)
        )));
        assertThat(run.getRows().get("Customer"), is(List.of(Map.of("id", "58", "status", "Updated", "deleted", false))));
        assertThat(run.getRows().get("Payment"), is(List.of()));
        assertThat(((List<?>) run.getCdcResponse().get("CDCResponse")).size(), is(1));
    }

    @Test
    void emptyEntitiesFail() {
        assertThrows(IllegalArgumentException.class, () -> builder()
            .entities(Property.ofValue(List.of()))
            .changedSince(Property.ofValue("2026-01-01T00:00:00Z"))
            .build()
            .run(runContext()));
    }
}
