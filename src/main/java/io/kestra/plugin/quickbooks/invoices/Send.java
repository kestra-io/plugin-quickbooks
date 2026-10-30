package io.kestra.plugin.quickbooks.invoices;

import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Map;

import io.kestra.core.http.HttpRequest;
import io.kestra.core.http.client.HttpClient;
import io.kestra.core.models.annotations.Example;
import io.kestra.core.models.annotations.Plugin;
import io.kestra.core.models.annotations.PluginProperty;
import io.kestra.core.models.property.Property;
import io.kestra.core.models.tasks.RunnableTask;
import io.kestra.core.runners.RunContext;
import io.kestra.core.serializers.JacksonMapper;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Builder;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.ToString;
import lombok.experimental.SuperBuilder;

@SuperBuilder
@ToString
@EqualsAndHashCode
@Getter
@NoArgsConstructor
@Plugin(
    examples = {
        @Example(
            title = "Email an invoice to a customer",
            full = true,
            code = """
                id: send_invoice
                namespace: company.team
                tasks:
                  - id: send
                    type: io.kestra.plugin.quickbooks.invoices.Send
                    clientId: "{{ secret('QBO_CLIENT_ID') }}"
                    clientSecret: "{{ secret('QBO_CLIENT_SECRET') }}"
                    refreshToken: "{{ secret('QBO_REFRESH_TOKEN') }}"
                    realmId: "{{ secret('QBO_REALM_ID') }}"
                    invoiceId: "130"
                    sendTo: "customer@example.com"
                """
        )
    }
)
@Schema(
    title = "Email an invoice",
    description = "Sends the invoice by email through QuickBooks (`POST /invoice/{id}/send`). This task has a side effect: the customer receives an email and the invoice `EmailStatus` changes. Without `sendTo`, QuickBooks uses the email address stored on the invoice."
)
public class Send extends AbstractInvoiceById implements RunnableTask<Send.Output> {

    @Schema(
        title = "Recipient email address",
        description = "Overrides the billing email of the invoice. Optional."
    )
    @PluginProperty(group = "main")
    protected Property<String> sendTo;

    @Override
    public Output run(RunContext runContext) throws Exception {
        var rId = renderId(runContext);
        var rSendTo = sendTo != null ? runContext.render(sendTo).as(String.class).filter(v -> !v.isBlank()).orElse(null) : null;
        var accessToken = getAccessToken(runContext);

        var url = invoiceUrl(runContext, rId, "/send") + "?minorversion=" + getValidatedMinorVersion(runContext);
        if (rSendTo != null) {
            url += "&sendTo=" + URLEncoder.encode(rSendTo, StandardCharsets.UTF_8);
        }

        try (HttpClient client = HttpClient.builder().runContext(runContext).build()) {
            // QuickBooks requires an octet-stream content type on this endpoint, with no payload.
            var request = HttpRequest.builder()
                .uri(URI.create(url))
                .method("POST")
                .addHeader("Authorization", "Bearer " + accessToken)
                .addHeader("Accept", "application/json")
                .body(HttpRequest.ByteArrayRequestBody.builder()
                    .contentType("application/octet-stream")
                    .content(new byte[0])
                    .build())
                .build();

            var responseBody = sendWithBackoff(client, request);
            Map<String, Object> response = JacksonMapper.ofJson().readValue(responseBody, new com.fasterxml.jackson.core.type.TypeReference<Map<String, Object>>() {});

            @SuppressWarnings("unchecked")
            var invoice = response.get("Invoice") instanceof Map<?, ?> m ? (Map<String, Object>) m : Map.<String, Object>of();
            var emailStatus = invoice.get("EmailStatus");
            runContext.logger().info("Invoice {} email status: {}", rId, emailStatus);

            return Output.builder()
                .emailStatus(emailStatus != null ? emailStatus.toString() : null)
                .row(invoice)
                .build();
        }
    }

    @Builder
    @Getter
    public static class Output implements io.kestra.core.models.tasks.Output {
        @Schema(
            title = "Email status",
            description = "The `EmailStatus` of the invoice after the call, for example `EmailSent` or `NeedToSend`."
        )
        private final String emailStatus;

        @Schema(
            title = "The invoice",
            description = "The invoice as returned by QuickBooks, including `DeliveryInfo`."
        )
        private final Map<String, Object> row;
    }
}
