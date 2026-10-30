package io.kestra.plugin.quickbooks;

import java.time.ZonedDateTime;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import io.kestra.core.models.conditions.ConditionContext;
import io.kestra.core.models.executions.Execution;
import io.kestra.core.models.flows.Flow;
import io.kestra.core.models.triggers.PollingTriggerInterface;
import io.kestra.core.models.triggers.TriggerContext;
import io.kestra.core.runners.DefaultRunContext;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.stubFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;

/**
 * Evaluates polling triggers the way the scheduler does. Each test class gets its own flow id so the
 * watermarks stored in the namespace KV store never leak between tests.
 */
public abstract class AbstractTriggerWireMockTest extends AbstractTaskWireMockTest {
    protected static final String NAMESPACE = "io.kestra.plugin.quickbooks.test";

    protected final String flowId = "flow-" + UUID.randomUUID();

    protected String baseUrl() {
        return "http://localhost:" + wireMockServer.port();
    }

    protected String authUrl() {
        return baseUrl() + "/oauth2/v1/tokens/bearer";
    }

    protected Optional<Execution> evaluate(PollingTriggerInterface trigger, String triggerId, ZonedDateTime date) throws Exception {
        var flow = Flow.builder().id(flowId).namespace(NAMESPACE).revision(1).build();
        var triggerContext = TriggerContext.builder()
            .tenantId("")
            .namespace(NAMESPACE)
            .flowId(flowId)
            .triggerId(triggerId)
            .date(date)
            .build();

        var runContext = (DefaultRunContext) runContext();
        runContextFactory.initializer().forScheduler(runContext, triggerContext, (io.kestra.core.models.triggers.AbstractTrigger) trigger);

        var conditionContext = ConditionContext.builder().runContext(runContext).flow(flow).build();
        return trigger.evaluate(conditionContext, triggerContext);
    }

    @SuppressWarnings("unchecked")
    protected static Map<String, Object> variables(Execution execution) {
        return (Map<String, Object>) (Map<?, ?>) execution.getTrigger().getVariables();
    }

    protected static void stubJson(String path, String body) {
        stubFor(get(urlPathEqualTo(path))
            .willReturn(aResponse().withHeader("Content-Type", "application/json").withBody(body)));
    }
}
