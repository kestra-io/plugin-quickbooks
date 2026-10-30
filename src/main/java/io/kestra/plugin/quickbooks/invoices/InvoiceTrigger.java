package io.kestra.plugin.quickbooks.invoices;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import jakarta.validation.constraints.NotNull;

import io.kestra.core.models.annotations.Example;
import io.kestra.core.models.annotations.Plugin;
import io.kestra.core.models.annotations.PluginProperty;
import io.kestra.core.models.conditions.ConditionContext;
import io.kestra.core.models.executions.Execution;
import io.kestra.core.models.property.Property;
import io.kestra.core.models.triggers.TriggerContext;
import io.kestra.core.models.triggers.TriggerOutput;
import io.kestra.core.models.triggers.TriggerService;
import io.kestra.plugin.quickbooks.trigger.AbstractQuickBooksTrigger;
import io.swagger.v3.oas.annotations.media.Schema;

import lombok.Builder;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.ToString;
import lombok.experimental.SuperBuilder;

@SuperBuilder
@ToString
@EqualsAndHashCode(callSuper = true)
@Getter
@NoArgsConstructor
@Plugin(
    examples = {
        @Example(
            title = "Start a flow when an invoice is paid",
            full = true,
            code = """
                id: qbo_on_invoice_paid
                namespace: company.finance

                triggers:
                  - id: on_invoice_paid
                    type: io.kestra.plugin.quickbooks.invoices.InvoiceTrigger
                    clientId: "{{ secret('QBO_CLIENT_ID') }}"
                    clientSecret: "{{ secret('QBO_CLIENT_SECRET') }}"
                    refreshToken: "{{ secret('QBO_REFRESH_TOKEN') }}"
                    realmId: "{{ secret('QBO_REALM_ID') }}"
                    event: paid
                    interval: PT15M

                tasks:
                  - id: log
                    type: io.kestra.plugin.core.log.Log
                    message: "Invoice {{ trigger.invoice.DocNumber }} is paid ({{ trigger.invoice.TotalAmt }})"
                """
        )
    }
)
@Schema(
    title = "Trigger a flow when QuickBooks invoices are created, updated or paid",
    description = """
        Polls the QuickBooks Change Data Capture endpoint for `Invoice` changes since the last poll and starts an execution when at least one change matches the configured `event`. Deleted invoices are never reported.

        A polling trigger starts at most one execution per evaluation: `invoice` is the oldest matching change and `invoices` lists all matching changes of the poll, so use `invoices` to process every change.

        The watermark is stored in the namespace KV store and only advances once the execution has been built, so a failed poll is retried over the same window. CDC only looks back about 30 days."""
)
public class InvoiceTrigger extends AbstractQuickBooksTrigger implements TriggerOutput<InvoiceTrigger.Output> {

    private static final String ENTITY = "Invoice";

    @Schema(
        title = "Invoice event to react to",
        description = """
            Supported values (case-insensitive):
            - `created`: invoices created since the last poll.
            - `updated`: invoices that already existed before the last poll and were modified since.
            - `paid`: invoices changed since the last poll that have a zero balance and a positive total. QuickBooks CDC does not report what changed, so an already paid invoice that is edited again is reported again.""",
        defaultValue = "created",
        allowableValues = {"created", "updated", "paid"}
    )
    @PluginProperty(group = "main")
    @Builder.Default
    private Property<String> event = Property.ofValue("created");

    @Schema(
        title = "Polling interval",
        description = "How often the trigger should poll for changes."
    )
    @Builder.Default
    private final Duration interval = Duration.ofMinutes(15);

    @Override
    public Duration getInterval() {
        return this.interval;
    }

    @Override
    public Optional<Execution> evaluate(ConditionContext conditionContext, TriggerContext context) throws Exception {
        var runContext = conditionContext.getRunContext();
        var rEvent = Event.parse(runContext.render(this.event).as(String.class).orElse("created"));

        var kvStore = runContext.namespaceKv(context.getNamespace());
        var kvKey = stateKey("quickbooks_invoice_trigger_", context);
        ZonedDateTime changedSince = readWatermark(kvStore, kvKey, context);
        ZonedDateTime pollStartTime = ZonedDateTime.now();

        var matching = new ArrayList<Map<String, Object>>();
        for (var invoice : changedEntities(fetchChanges(runContext, List.of(ENTITY), changedSince), ENTITY)) {
            if (matches(rEvent, invoice, changedSince)) {
                matching.add(invoice);
            }
        }
        matching.sort(Comparator.comparing(
            (Map<String, Object> invoice) -> metaTime(invoice, "LastUpdatedTime"),
            Comparator.nullsLast(Comparator.<OffsetDateTime>naturalOrder())
        ));

        if (matching.isEmpty()) {
            writeWatermark(kvStore, kvKey, pollStartTime);
            return Optional.empty();
        }

        runContext.logger().info("Found {} invoice(s) matching event {}", matching.size(), rEvent);

        Execution execution = TriggerService.generateExecution(this, conditionContext, context, Output.builder()
            .invoice(matching.get(0))
            .invoices(matching)
            .count(matching.size())
            .build());

        // Advance only once the execution exists: a failure above makes the next poll cover the same window.
        writeWatermark(kvStore, kvKey, pollStartTime);
        return Optional.of(execution);
    }

    private static boolean matches(Event event, Map<String, Object> invoice, ZonedDateTime changedSince) {
        if (isDeleted(invoice)) {
            return false;
        }
        var created = metaTime(invoice, "CreateTime");
        boolean createdInWindow = created == null || !created.toInstant().isBefore(changedSince.toInstant());

        return switch (event) {
            case CREATED -> createdInWindow;
            case UPDATED -> !createdInWindow;
            case PAID -> isPaid(invoice);
        };
    }

    private static boolean isPaid(Map<String, Object> invoice) {
        try {
            var balance = new BigDecimal(String.valueOf(invoice.get("Balance")));
            var total = new BigDecimal(String.valueOf(invoice.get("TotalAmt")));
            return balance.signum() == 0 && total.signum() > 0;
        } catch (NumberFormatException e) {
            return false;
        }
    }

    enum Event {
        CREATED,
        UPDATED,
        PAID;

        static Event parse(String value) {
            for (var event : values()) {
                if (event.name().equalsIgnoreCase(value.trim())) {
                    return event;
                }
            }
            throw new IllegalArgumentException("event must be one of created, updated or paid. Provided: " + value);
        }
    }

    @Builder
    @Getter
    public static class Output implements io.kestra.core.models.tasks.Output {
        @Schema(
            title = "Invoice",
            description = "The oldest invoice matching the event, as returned by QuickBooks."
        )
        @NotNull
        private final Map<String, Object> invoice;

        @Schema(
            title = "Invoices",
            description = "All invoices matching the event in this poll, oldest first. Includes `invoice`."
        )
        @NotNull
        private final List<Map<String, Object>> invoices;

        @Schema(
            title = "Count",
            description = "The number of invoices matching the event in this poll."
        )
        @NotNull
        private final Integer count;
    }
}
