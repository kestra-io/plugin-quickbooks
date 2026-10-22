package io.kestra.plugin.quickbooks.customers;

import io.kestra.core.models.property.Property;
import io.kestra.core.models.tasks.common.FetchType;
import io.kestra.plugin.quickbooks.AbstractTaskWireMockTest;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.notNullValue;

public class ListTest extends AbstractTaskWireMockTest {

    @Test
    void testList() throws Exception {
        stubFor(get(urlPathEqualTo("/v3/company/12345/query"))
            .withQueryParam("query", equalTo("select * from Customer STARTPOSITION 1 MAXRESULTS 1000"))
            .withQueryParam("minorversion", equalTo("75"))
            .willReturn(aResponse()
                .withHeader("Content-Type", "application/json")
                .withBody("{\"QueryResponse\": {\"Customer\": [{\"Id\": \"1\", \"DisplayName\": \"John Doe\"}], \"startPosition\": 1, \"maxResults\": 1000}}")));

        List task = List.builder()
            .clientId(Property.ofValue("client_id"))
            .clientSecret(Property.ofValue("client_secret"))
            .refreshToken(Property.ofValue("refresh_token"))
            .realmId(Property.ofValue("12345"))
            .baseUrl(Property.ofValue("http://localhost:" + wireMockServer.port()))
            .authUrl(Property.ofValue("http://localhost:" + wireMockServer.port() + "/oauth2/v1/tokens/bearer"))
            .fetchType(Property.ofValue(FetchType.FETCH))
            .build();

        var run = task.run(runContext());

        assertThat(run, notNullValue());
        assertThat(run.getSize(), is(1L));
        assertThat(run.getRows().size(), is(1));
        Map<String, Object> first = (Map<String, Object>) run.getRows().get(0);
        assertThat(first.get("Id"), is("1"));
    }
}
