package io.kestra.plugin.quickbooks.invoices;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;

import io.kestra.core.models.annotations.Example;
import io.kestra.core.models.annotations.Plugin;
import io.kestra.core.models.annotations.PluginProperty;
import io.kestra.core.models.property.Property;
import io.kestra.core.runners.RunContext;
import io.kestra.plugin.quickbooks.AbstractList;
import io.swagger.v3.oas.annotations.media.Schema;
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
            title = "List Invoice records",
            full = true,
            code = """
                id: list_invoices
                namespace: company.team
                tasks:
                  - id: list
                    type: io.kestra.plugin.quickbooks.invoices.List
                    clientId: "{{ secret('QBO_CLIENT_ID') }}"
                    clientSecret: "{{ secret('QUICKBOOKS_CLIENT_SECRET') }}"
                    refreshToken: "{{ secret('QUICKBOOKS_REFRESH_TOKEN') }}"
                    realmId: "{{ secret('QBO_REALM_ID') }}"
                    fetchType: FETCH
                """
        ),
        @Example(
            title = "List the overdue invoices of a customer above a minimum balance",
            full = true,
            code = """
                id: list_overdue_invoices
                namespace: company.team
                tasks:
                  - id: list
                    type: io.kestra.plugin.quickbooks.invoices.List
                    clientId: "{{ secret('QBO_CLIENT_ID') }}"
                    clientSecret: "{{ secret('QBO_CLIENT_SECRET') }}"
                    refreshToken: "{{ secret('QBO_REFRESH_TOKEN') }}"
                    realmId: "{{ secret('QBO_REALM_ID') }}"
                    customerId: "58"
                    status: overdue
                    dueAfter: "2026-10-01"
                    balanceMin: 100
                    fetchType: STORE
                """
        )
    }
)
@Schema(
    title = "List Invoice records",
    description = "Builds `select * from Invoice where ...` from the typed filters and the optional raw `where`, all combined with `AND`. Values are escaped, so a filter cannot alter the rest of the query."
)
public class List extends AbstractList {

    @Schema(
        title = "Only invoices of this customer",
        description = "The customer id, matched against `CustomerRef`."
    )
    @PluginProperty(group = "main")
    protected Property<String> customerId;

    @Schema(
        title = "Invoice status",
        description = "One of `open` (`Balance > 0`), `overdue` (`Balance > 0` and `DueDate` before today, UTC) or `paid` (`Balance = 0`). Case-insensitive."
    )
    @PluginProperty(group = "main")
    protected Property<String> status;

    @Schema(
        title = "Due before",
        description = "Only invoices with `DueDate` strictly before this date (`yyyy-MM-dd`)."
    )
    @PluginProperty(group = "main")
    protected Property<LocalDate> dueBefore;

    @Schema(
        title = "Due after",
        description = "Only invoices with `DueDate` strictly after this date (`yyyy-MM-dd`)."
    )
    @PluginProperty(group = "main")
    protected Property<LocalDate> dueAfter;

    @Schema(
        title = "Transaction date from",
        description = "Only invoices with `TxnDate` on or after this date (`yyyy-MM-dd`)."
    )
    @PluginProperty(group = "main")
    protected Property<LocalDate> txnDateFrom;

    @Schema(
        title = "Transaction date to",
        description = "Only invoices with `TxnDate` on or before this date (`yyyy-MM-dd`)."
    )
    @PluginProperty(group = "main")
    protected Property<LocalDate> txnDateTo;

    @Schema(
        title = "Minimum balance",
        description = "Only invoices with `Balance` greater than or equal to this amount."
    )
    @PluginProperty(group = "main")
    protected Property<BigDecimal> balanceMin;

    enum Status {
        OPEN,
        OVERDUE,
        PAID;

        static Status parse(String value) {
            for (var status : values()) {
                if (status.name().equalsIgnoreCase(value.trim())) {
                    return status;
                }
            }
            throw new IllegalArgumentException("status must be one of open, overdue or paid. Provided: " + value);
        }
    }

    @Override
    protected String entityName() {
        return "Invoice";
    }

    @Override
    protected java.util.List<String> conditions(RunContext runContext) throws Exception {
        var conditions = new ArrayList<String>();

        var rCustomerId = render(runContext, customerId, String.class);
        if (rCustomerId != null) {
            conditions.add("CustomerRef = " + quote(rCustomerId));
        }

        var rStatus = render(runContext, status, String.class);
        if (rStatus != null && !rStatus.isBlank()) {
            switch (Status.parse(rStatus)) {
                case OPEN -> conditions.add("Balance > '0'");
                case PAID -> conditions.add("Balance = '0'");
                case OVERDUE -> {
                    conditions.add("Balance > '0'");
                    conditions.add("DueDate < " + quote(LocalDate.now(ZoneOffset.UTC).toString()));
                }
            }
        }

        // Dates and numbers are typed and re-serialized here, so they cannot carry query syntax.
        addCondition(conditions, "DueDate < ", render(runContext, dueBefore, LocalDate.class));
        addCondition(conditions, "DueDate > ", render(runContext, dueAfter, LocalDate.class));
        addCondition(conditions, "TxnDate >= ", render(runContext, txnDateFrom, LocalDate.class));
        addCondition(conditions, "TxnDate <= ", render(runContext, txnDateTo, LocalDate.class));

        var rBalanceMin = render(runContext, balanceMin, BigDecimal.class);
        if (rBalanceMin != null) {
            conditions.add("Balance >= " + quote(rBalanceMin.toPlainString()));
        }

        return conditions;
    }

    private static void addCondition(java.util.List<String> conditions, String prefix, Object value) {
        if (value != null) {
            conditions.add(prefix + quote(value.toString()));
        }
    }

    private static <T> T render(RunContext runContext, Property<T> property, Class<T> type) throws Exception {
        return property == null ? null : runContext.render(property).as(type).orElse(null);
    }
}
