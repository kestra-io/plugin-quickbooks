package io.kestra.plugin.quickbooks.trigger;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

import io.kestra.core.http.HttpRequest;
import io.kestra.core.http.HttpResponse;
import io.kestra.core.models.executions.Execution;
import io.kestra.core.models.flows.Flow;
import io.kestra.core.models.property.Property;
import io.kestra.core.models.tasks.Output;
import io.kestra.core.services.WebhookService;
import io.kestra.plugin.core.trigger.WebhookContext;
import io.kestra.plugin.quickbooks.AbstractTaskWireMockTest;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import reactor.core.publisher.Mono;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.not;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class WebhookTriggerTest extends AbstractTaskWireMockTest {
    private static final String TOKEN = "verifier-token-123";
    private static final String PAYLOAD = """
        {"eventNotifications":[{"realmId":"12345","dataChangeEvent":{"entities":[{"name":"Invoice","id":"130","operation":"Create","lastUpdated":"2023-09-01T12:00:00Z"}]}}]}""";

    private final WebhookService webhookService = mock(WebhookService.class);
    private final AtomicInteger subscriptions = new AtomicInteger();
    private final WebhookTrigger trigger = WebhookTrigger.builder()
        .id("qbo_webhook")
        .type(WebhookTrigger.class.getName())
        .key("a-secret-key")
        .verifierToken(Property.ofValue(TOKEN))
        .build();

    private static String sign(String payload, String token) throws Exception {
        var mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(token.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        return Base64.getEncoder().encodeToString(mac.doFinal(payload.getBytes(StandardCharsets.UTF_8)));
    }

    private HttpResponse<?> call(String path, String body, String signature) throws Exception {
        var flow = Flow.builder().id("test-flow").namespace("io.kestra.plugin.quickbooks.test").revision(1).build();
        when(webhookService.runContext(any(Flow.class), any(io.kestra.core.models.triggers.AbstractTrigger.class))).thenReturn(runContext());
        when(webhookService.newExecution(any(), any(), any(), any(Output.class)))
            .thenReturn(Optional.of(Execution.builder().id("exec").namespace(flow.getNamespace()).flowId(flow.getId()).build()));
        when(webhookService.startExecution(any()))
            .thenReturn(Mono.defer(() -> {
                subscriptions.incrementAndGet();
                return Mono.empty();
            }));

        var request = HttpRequest.builder()
            .uri(URI.create("http://localhost/api/v1/executions/webhook/ns/test-flow/a-secret-key" + path))
            .method("POST");
        if (signature != null) {
            request.addHeader("intuit-signature", signature);
        }
        var built = request.body(HttpRequest.StringRequestBody.builder()
            .content(body)
            .contentType("application/json")
            .charset(StandardCharsets.UTF_8)
            .build()).build();

        var context = WebhookContext.builder()
            .request(built)
            .flow(flow)
            .trigger(trigger)
            .webhookService(webhookService)
            .build();

        return trigger.evaluate(context).block();
    }

    @Test
    @SuppressWarnings("unchecked")
    void validSignatureStartsAnExecutionWithEventNotifications() throws Exception {
        var response = call("", PAYLOAD, sign(PAYLOAD, TOKEN));

        assertThat(response.getStatus().getCode(), is(200));
        var output = ArgumentCaptor.forClass(Output.class);
        verify(webhookService).newExecution(any(), any(), any(), output.capture());
        verify(webhookService).startExecution(any());
        assertThat(subscriptions.get(), is(1));

        var notifications = ((WebhookTrigger.Output) output.getValue()).getEventNotifications();
        assertThat(notifications.size(), is(1));
        assertThat(notifications.get(0).get("realmId"), is("12345"));
        var entities = (List<Map<String, Object>>) ((Map<String, Object>) notifications.get(0).get("dataChangeEvent")).get("entities");
        assertThat(entities.get(0).get("id"), is("130"));
    }

    @Test
    void invalidSignatureIsRejectedWithoutExecution() throws Exception {
        var response = call("", PAYLOAD, sign(PAYLOAD, "another-token"));

        assertThat(response.getStatus().getCode(), is(401));
        verify(webhookService, never()).newExecution(any(), any(), any(), any(Output.class));
        verify(webhookService, never()).startExecution(any());
        assertThat(subscriptions.get(), is(0));
    }

    @Test
    void tamperedBodyIsRejected() throws Exception {
        var response = call("", PAYLOAD.replace("130", "131"), sign(PAYLOAD, TOKEN));

        assertThat(response.getStatus().getCode(), is(401));
        verify(webhookService, never()).startExecution(any());
    }

    @Test
    void missingSignatureIsRejected() throws Exception {
        var response = call("", PAYLOAD, null);

        assertThat(response.getStatus().getCode(), is(401));
        verify(webhookService, never()).newExecution(any(), any(), any(), any(Output.class));
    }

    @Test
    void unexpectedPathIsNotFound() throws Exception {
        var response = call("/", PAYLOAD, sign(PAYLOAD, TOKEN));

        assertThat(response.getStatus().getCode(), is(404));
        verify(webhookService, never()).startExecution(any());
    }

    @Test
    void signedButMalformedPayloadIsABadRequest() throws Exception {
        var response = call("", "not json", sign("not json", TOKEN));

        assertThat(response.getStatus().getCode(), is(400));
        verify(webhookService, never()).startExecution(any());
    }

    @Test
    void failureToStartTheExecutionIsAnInternalServerError() throws Exception {
        var flow = Flow.builder().id("test-flow").namespace("io.kestra.plugin.quickbooks.test").revision(1).build();
        when(webhookService.runContext(any(Flow.class), any(io.kestra.core.models.triggers.AbstractTrigger.class))).thenReturn(runContext());
        when(webhookService.newExecution(any(), any(), any(), any(Output.class)))
            .thenReturn(Optional.of(Execution.builder().id("exec").namespace(flow.getNamespace()).flowId(flow.getId()).build()));
        when(webhookService.startExecution(any())).thenReturn(Mono.error(new IllegalStateException("queue down")));

        var request = HttpRequest.builder()
            .uri(URI.create("http://localhost/api/v1/executions/webhook/ns/test-flow/a-secret-key"))
            .method("POST")
            .addHeader("intuit-signature", sign(PAYLOAD, TOKEN))
            .body(HttpRequest.StringRequestBody.builder()
                .content(PAYLOAD)
                .contentType("application/json")
                .charset(StandardCharsets.UTF_8)
                .build())
            .build();
        var context = WebhookContext.builder().request(request).flow(flow).trigger(trigger).webhookService(webhookService).build();

        assertThat(trigger.evaluate(context).block().getStatus().getCode(), is(500));
    }

    @Test
    void toStringDoesNotPrintTheVerifierToken() {
        assertThat(trigger.toString(), not(containsString(TOKEN)));
    }
}
