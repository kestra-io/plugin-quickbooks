package io.kestra.plugin.quickbooks.trigger;

import io.kestra.core.models.conditions.ConditionContext;
import io.kestra.core.models.executions.Execution;
import io.kestra.core.models.flows.Flow;
import io.kestra.core.models.property.Property;
import io.kestra.core.models.triggers.TriggerContext;
import io.kestra.plugin.quickbooks.AbstractTaskWireMockTest;
import org.junit.jupiter.api.Test;

import java.time.ZonedDateTime;
import java.util.List;
import java.util.Optional;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;

public class CdcTriggerTest extends AbstractTaskWireMockTest {

    @Test
    void testCdcTrigger() throws Exception {
        stubFor(get(urlPathEqualTo("/v3/company/12345/cdc"))
            .withQueryParam("minorversion", equalTo("75"))
            .withQueryParam("entities", equalTo("Invoice,Customer"))
            .willReturn(aResponse()
                .withHeader("Content-Type", "application/json")
                .withBody("{" +
                    "\"CDCResponse\": [{" +
                    "\"QueryResponse\": [{" +
                    "\"Invoice\": [{" +
                    "\"Id\": \"130\"," +
                    "\"status\": \"Updated\"" +
                    "}]" +
                    "}]" +
                    "}]," +
                    "\"time\": \"2023-09-02T12:00:00Z\"" +
                    "}")));

        var trigger = CdcTrigger.builder()
            .id("watch_invoices")
            .type(CdcTrigger.class.getName())
            .clientId(Property.ofValue("client_id"))
            .clientSecret(Property.ofValue("client_secret"))
            .refreshToken(Property.ofValue("refresh_token"))
            .realmId(Property.ofValue("12345"))
            .baseUrl(Property.ofValue("http://localhost:" + wireMockServer.port()))
            .authUrl(Property.ofValue("http://localhost:" + wireMockServer.port() + "/oauth2/v1/tokens/bearer"))
            .entities(Property.ofValue(List.of("Invoice", "Customer")))
            .build();

        var flow = Flow.builder()
            .id("test-flow")
            .namespace("io.kestra.plugin.quickbooks.test")
            .revision(1)
            .build();

        var triggerContext = TriggerContext.builder()
            .tenantId("")
            .namespace("io.kestra.plugin.quickbooks.test")
            .flowId("test-flow")
            .triggerId("watch_invoices")
            .date(ZonedDateTime.parse("2023-09-01T12:00:00Z"))
            .build();

        var runContext = (io.kestra.core.runners.DefaultRunContext) runContext();
        runContextFactory.initializer().forScheduler(runContext, triggerContext, trigger);

        var conditionContext = ConditionContext.builder()
            .runContext(runContext)
            .flow(flow)
            .build();

        Optional<Execution> result = trigger.evaluate(conditionContext, triggerContext);

        assertThat(result.isPresent(), is(true));
        Execution execution = result.get();
        assertThat(execution.getFlowId(), is("test-flow"));
    }
}
