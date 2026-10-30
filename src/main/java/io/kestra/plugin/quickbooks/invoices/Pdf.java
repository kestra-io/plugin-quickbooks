package io.kestra.plugin.quickbooks.invoices;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;

import io.kestra.core.http.HttpRequest;
import io.kestra.core.http.client.HttpClient;
import io.kestra.core.models.annotations.Example;
import io.kestra.core.models.annotations.Plugin;
import io.kestra.core.models.tasks.RunnableTask;
import io.kestra.core.runners.RunContext;
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
            title = "Download an invoice as a PDF into internal storage",
            full = true,
            code = """
                id: invoice_pdf
                namespace: company.team
                tasks:
                  - id: pdf
                    type: io.kestra.plugin.quickbooks.invoices.Pdf
                    clientId: "{{ secret('QBO_CLIENT_ID') }}"
                    clientSecret: "{{ secret('QBO_CLIENT_SECRET') }}"
                    refreshToken: "{{ secret('QBO_REFRESH_TOKEN') }}"
                    realmId: "{{ secret('QBO_REALM_ID') }}"
                    invoiceId: "130"
                """
        )
    }
)
@Schema(
    title = "Download an invoice as a PDF",
    description = "Fetches `GET /invoice/{id}/pdf` and stores the file in Kestra internal storage. The body is streamed to a temporary file, so the PDF is never held in memory whatever its size."
)
public class Pdf extends AbstractInvoiceById implements RunnableTask<Pdf.Output> {

    @Override
    public Output run(RunContext runContext) throws Exception {
        var rId = renderId(runContext);
        var rAccessToken = getAccessToken(runContext);
        var url = invoiceUrl(runContext, rId, "/pdf") + "?minorversion=" + getValidatedMinorVersion(runContext);

        try (HttpClient client = HttpClient.builder().runContext(runContext).build()) {
            var request = HttpRequest.builder()
                .uri(URI.create(url))
                .method("GET")
                .addHeader("Authorization", "Bearer " + rAccessToken)
                .addHeader("Accept", "application/pdf")
                .build();

            // The file is created per attempt so a throttled call never leaves a partial file behind.
            var file = withBackoff(() -> {
                var tempFile = runContext.workingDir().createTempFile(".pdf");
                client.request(request, response -> {
                    try (var body = response.getBody()) {
                        Files.copy(body, tempFile, StandardCopyOption.REPLACE_EXISTING);
                    } catch (IOException e) {
                        throw new UncheckedIOException(e);
                    }
                });
                return tempFile;
            });

            var size = Files.size(file);
            var uri = runContext.storage().putFile(file.toFile());
            runContext.logger().info("Stored PDF of invoice {} ({} bytes)", rId, size);

            return Output.builder().uri(uri).size(size).build();
        }
    }

    @Builder
    @Getter
    public static class Output implements io.kestra.core.models.tasks.Output {
        @Schema(
            title = "PDF location",
            description = "The internal storage URI of the invoice PDF."
        )
        private final URI uri;

        @Schema(
            title = "PDF size",
            description = "The size of the PDF in bytes."
        )
        private final Long size;
    }
}
