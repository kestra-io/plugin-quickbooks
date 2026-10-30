package io.kestra.plugin.quickbooks.trigger;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

import jakarta.validation.constraints.NotNull;

import io.kestra.core.http.HttpResponse;
import io.kestra.core.models.annotations.Example;
import io.kestra.core.models.annotations.Plugin;
import io.kestra.core.models.annotations.PluginProperty;
import io.kestra.core.models.executions.Execution;
import io.kestra.core.models.property.Property;
import io.kestra.core.models.triggers.TriggerOutput;
import io.kestra.core.serializers.JacksonMapper;
import io.kestra.plugin.core.trigger.AbstractWebhookTrigger;
import io.kestra.plugin.core.trigger.WebhookContext;
import io.swagger.v3.oas.annotations.media.Schema;

import com.fasterxml.jackson.core.type.TypeReference;
import lombok.Builder;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.ToString;
import lombok.experimental.SuperBuilder;
import reactor.core.publisher.Mono;

@SuperBuilder
@ToString
@EqualsAndHashCode(callSuper = true)
@Getter
@NoArgsConstructor
@Plugin(
    examples = {
        @Example(
            title = "Start a flow on QuickBooks webhook notifications",
            full = true,
            code = """
                id: qbo_webhook
                namespace: company.finance

                triggers:
                  - id: on_event
                    type: io.kestra.plugin.quickbooks.trigger.WebhookTrigger
                    key: "{{ secret('QBO_WEBHOOK_KEY') }}"
                    verifierToken: "{{ secret('QBO_WEBHOOK_VERIFIER_TOKEN') }}"

                tasks:
                  - id: log
                    type: io.kestra.plugin.core.log.Log
                    message: "Received {{ trigger.eventNotifications | length }} QuickBooks notification(s)"
                """
        )
    }
)
@Schema(
    title = "Trigger a flow from QuickBooks webhooks",
    description = """
        Exposes a webhook URL `.../executions/webhook/{namespace}/{flowId}/{key}` to register in the Intuit developer portal. Every request is authenticated with the `intuit-signature` header, which must be the base64 HMAC-SHA256 of the raw request body keyed with the `verifierToken` of your webhook endpoint. A request with a missing or invalid signature gets a `401` and never starts an execution.

        The `key` is part of the URL: keep it secret, for example with `{{ secret('...') }}`. Webhook payloads only describe what changed (entity name, id, operation); fetch the entity with the matching QuickBooks task. Responses: 200 (triggered), 204 (conditions not met), 400 (invalid payload), 401 (invalid signature), 404 (unexpected path)."""
)
public class WebhookTrigger extends AbstractWebhookTrigger implements TriggerOutput<WebhookTrigger.Output> {
    private static final String SIGNATURE_HEADER = "intuit-signature";
    private static final String HMAC_ALGORITHM = "HmacSHA256";

    @Schema(
        title = "Webhook verifier token",
        description = "The verifier token shown for your webhook endpoint in the Intuit developer portal. Used to check the `intuit-signature` header of every request."
    )
    @PluginProperty(secret = true, group = "connection")
    @NotNull
    @ToString.Exclude
    private Property<String> verifierToken;

    @Override
    public Mono<HttpResponse<?>> evaluate(WebhookContext context) throws Exception {
        // Reject path since not expected
        if (context.path() != null || context.request().getUri().getPath().endsWith("/")) {
            return Mono.just(HttpResponse.of(HttpResponse.Status.NOT_FOUND));
        }

        var runContext = context.webhookService().runContext(context.flow(), this);
        var rToken = runContext.render(this.verifierToken).as(String.class).orElse(null);
        if (rToken == null || rToken.isBlank()) {
            runContext.logger().error("The verifierToken of the QuickBooks webhook trigger is empty, rejecting the request");
            return Mono.just(HttpResponse.of(HttpResponse.Status.INTERNAL_SERVER_ERROR));
        }

        var body = rawBody(context);
        var signature = context.request().getHeaders() == null
            ? Optional.<String>empty()
            : context.request().getHeaders().firstValue(SIGNATURE_HEADER);

        if (signature.isEmpty() || !isValidSignature(body, rToken, signature.get())) {
            runContext.logger().warn("Rejected a QuickBooks webhook request with a missing or invalid {} header", SIGNATURE_HEADER);
            return Mono.just(HttpResponse.of(HttpResponse.Status.UNAUTHORIZED));
        }

        List<Map<String, Object>> eventNotifications;
        try {
            var payload = JacksonMapper.ofJson().readValue(body, new TypeReference<Map<String, Object>>() {});
            eventNotifications = JacksonMapper.ofJson().convertValue(
                payload.getOrDefault("eventNotifications", List.of()),
                new TypeReference<List<Map<String, Object>>>() {}
            );
        } catch (Exception e) {
            return Mono.just(HttpResponse.of(HttpResponse.Status.BAD_REQUEST));
        }

        var maybeExecution = context.webhookService().newExecution(
            context,
            context.flow(),
            this,
            Output.builder().eventNotifications(eventNotifications).build()
        );

        if (maybeExecution.isEmpty()) {
            // Conditions not met: answer 2xx so Intuit does not keep retrying the delivery.
            return Mono.just(HttpResponse.of(HttpResponse.Status.NO_CONTENT));
        }

        var execution = maybeExecution.get();
        HttpResponse<?> accepted = HttpResponse.of(context.webhookService().executionResponse(execution));
        return context.webhookService().startExecution(execution)
            .then(Mono.<HttpResponse<?>>just(accepted))
            .onErrorResume(e -> {
                runContext.logger().error("Failed to start the execution for a QuickBooks webhook", e);
                return Mono.<HttpResponse<?>>just(HttpResponse.of(HttpResponse.Status.INTERNAL_SERVER_ERROR));
            });
    }

    private static byte[] rawBody(WebhookContext context) {
        var body = context.request().getBody();
        if (body == null || body.getContent() == null) {
            return new byte[0];
        }
        if (body.getContent() instanceof byte[] bytes) {
            return bytes;
        }
        var charset = body.getCharset() != null ? body.getCharset() : StandardCharsets.UTF_8;
        return body.getContent().toString().getBytes(charset);
    }

    /**
     * {@code intuit-signature} is base64(HMAC-SHA256(raw payload, verifierToken)), compared in constant time.
     */
    static boolean isValidSignature(byte[] payload, String verifierToken, String signature) {
        try {
            var mac = Mac.getInstance(HMAC_ALGORITHM);
            mac.init(new SecretKeySpec(verifierToken.getBytes(StandardCharsets.UTF_8), HMAC_ALGORITHM));
            var expected = Base64.getEncoder().encodeToString(mac.doFinal(payload)).getBytes(StandardCharsets.UTF_8);
            return MessageDigest.isEqual(expected, signature.trim().getBytes(StandardCharsets.UTF_8));
        } catch (Exception e) {
            return false;
        }
    }

    @Builder
    @Getter
    public static class Output implements io.kestra.core.models.tasks.Output {
        @Schema(
            title = "Event notifications",
            description = "The `eventNotifications` array of the webhook payload. Each entry holds a `realmId` and a `dataChangeEvent.entities` list describing the changed entities (`name`, `id`, `operation`, `lastUpdated`)."
        )
        @NotNull
        private final List<Map<String, Object>> eventNotifications;
    }
}
