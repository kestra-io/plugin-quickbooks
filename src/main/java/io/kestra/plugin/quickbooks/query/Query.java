package io.kestra.plugin.quickbooks.query;

import io.kestra.core.models.annotations.Example;
import io.kestra.core.models.annotations.Plugin;
import io.kestra.core.models.annotations.PluginProperty;
import io.kestra.core.models.property.Property;
import io.kestra.core.models.tasks.RunnableTask;
import io.kestra.core.models.tasks.common.FetchOutput;
import io.kestra.core.models.tasks.common.FetchType;
import io.kestra.core.runners.RunContext;
import io.kestra.core.serializers.FileSerde;
import io.kestra.core.serializers.JacksonMapper;
import io.kestra.core.http.HttpRequest;
import io.kestra.core.http.client.HttpClient;
import io.kestra.plugin.quickbooks.AbstractQuickBooksConnection;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import lombok.Builder;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.ToString;
import lombok.experimental.SuperBuilder;

import java.io.File;
import java.io.FileOutputStream;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;


@SuperBuilder
@ToString
@EqualsAndHashCode
@Getter
@NoArgsConstructor
@Plugin(
    examples = {
        @Example(
            title = "Query all open invoices with a positive balance",
            full = true,
            code = """
                id: qbo_open_invoices
                namespace: company.finance
                tasks:
                  - id: open_invoices
                    type: io.kestra.plugin.quickbooks.query.Query
                    clientId: "{{ secret('QBO_CLIENT_ID') }}"
                    clientSecret: "{{ secret('QUICKBOOKS_CLIENT_SECRET') }}"
                    refreshToken: "{{ secret('QUICKBOOKS_REFRESH_TOKEN') }}"
                    realmId: "{{ secret('QBO_REALM_ID') }}"
                    query: "select * from Invoice where Balance > '0'"
                    fetchType: STORE
                """
        )
    }
)
@Schema(
    title = "Execute a query using QuickBooks Online SQL-like Query Language",
    description = "Query QBO entities like Invoice, Customer, Payment, Bill, Vendor, Item, Account. Supports STARTPOSITION and MAXRESULTS pagination automatically if fetchType is STORE or FETCH."
)
public class Query extends AbstractQuery {

    @Schema(
        title = "The QBO Query Language statement",
        description = "Example: select * from Invoice where Balance > '0'"
    )
    @PluginProperty(group = "main")
    @NotNull
    protected Property<String> query;

    @Override
    public FetchOutput run(RunContext runContext) throws Exception {
        String renderedQuery = runContext.render(this.query).as(String.class)
            .orElseThrow(() -> new io.kestra.core.exceptions.IllegalVariableEvaluationException("query is required, set the 'query' property"));
        return run(runContext, renderedQuery);
    }
}
