package io.kestra.plugin.quickbooks.payments;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import jakarta.validation.constraints.NotNull;

import io.kestra.core.models.annotations.Example;
import io.kestra.core.models.annotations.Plugin;
import io.kestra.core.models.conditions.ConditionContext;
import io.kestra.core.models.executions.Execution;
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
            title = "React to a received payment via CDC",
            full = true,
            code = """
                id: qbo_on_payment
                namespace: company.finance

                triggers:
                  - id: on_payment
                    type: io.kestra.plugin.quickbooks.payments.PaymentReceivedTrigger
                    clientId: "{{ secret('QBO_CLIENT_ID') }}"
                    clientSecret: "{{ secret('QBO_CLIENT_SECRET') }}"
                    refreshToken: "{{ secret('QBO_REFRESH_TOKEN') }}"
                    realmId: "{{ secret('QBO_REALM_ID') }}"
                    interval: PT10M

                tasks:
                  - id: log
                    type: io.kestra.plugin.core.log.Log
                    message: "Payment {{ trigger.payment.id }} applied to {{ trigger.linkedInvoices | length }} invoice(s)"
                """
        )
    }
)
@Schema(
    title = "Trigger a flow when QuickBooks receives a payment",
    description = """
        Polls the QuickBooks Change Data Capture endpoint for new `Payment` records since the last poll and starts an execution when at least one was created. Updated and deleted payments are ignored.

        A polling trigger starts at most one execution per evaluation: `payment` is the oldest new payment and `payments` lists all new payments of the poll, so use `payments` to process every payment.

        The watermark is stored in the namespace KV store and only advances once the execution has been built, so a failed poll is retried over the same window. CDC only looks back about 30 days."""
)
public class PaymentReceivedTrigger extends AbstractQuickBooksTrigger implements TriggerOutput<PaymentReceivedTrigger.Output> {

    private static final String ENTITY = "Payment";

    @Schema(
        title = "Polling interval",
        description = "How often the trigger should poll for new payments."
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

        var kvStore = runContext.namespaceKv(context.getNamespace());
        var kvKey = stateKey("quickbooks_payment_trigger_", context);
        ZonedDateTime changedSince = readWatermark(kvStore, kvKey, context);
        ZonedDateTime pollStartTime = ZonedDateTime.now();

        var payments = new ArrayList<Map<String, Object>>();
        for (var payment : changedEntities(fetchChanges(runContext, List.of(ENTITY), changedSince), ENTITY)) {
            var created = metaTime(payment, "CreateTime");
            if (!isDeleted(payment) && (created == null || !created.toInstant().isBefore(changedSince.toInstant()))) {
                // QuickBooks names the identifier `Id`: expose `id` too so `{{ trigger.payment.id }}` works.
                var withId = new LinkedHashMap<>(payment);
                withId.putIfAbsent("id", payment.get("Id"));
                payments.add(withId);
            }
        }
        payments.sort(Comparator.comparing(
            (Map<String, Object> payment) -> metaTime(payment, "LastUpdatedTime"),
            Comparator.nullsLast(Comparator.<OffsetDateTime>naturalOrder())
        ));

        if (payments.isEmpty()) {
            writeWatermark(kvStore, kvKey, pollStartTime);
            return Optional.empty();
        }

        runContext.logger().info("Found {} new payment(s)", payments.size());

        var first = payments.get(0);
        Execution execution = TriggerService.generateExecution(this, conditionContext, context, Output.builder()
            .payment(first)
            .linkedInvoices(linkedInvoices(first))
            .payments(payments)
            .count(payments.size())
            .build());

        // Advance only once the execution exists: a failure above makes the next poll cover the same window.
        writeWatermark(kvStore, kvKey, pollStartTime);
        return Optional.of(execution);
    }

    /**
     * Invoices applied to a payment: {@code Line[].LinkedTxn[]} entries of type {@code Invoice}, with the
     * amount of the line they belong to.
     */
    static List<Map<String, Object>> linkedInvoices(Map<String, Object> payment) {
        var invoices = new ArrayList<Map<String, Object>>();
        if (!(payment.get("Line") instanceof List<?> lines)) {
            return invoices;
        }
        for (Object line : lines) {
            if (!(line instanceof Map<?, ?> lineMap) || !(lineMap.get("LinkedTxn") instanceof List<?> linked)) {
                continue;
            }
            for (Object txn : linked) {
                if (txn instanceof Map<?, ?> txnMap && "Invoice".equals(txnMap.get("TxnType")) && txnMap.get("TxnId") != null) {
                    var invoice = new LinkedHashMap<String, Object>();
                    invoice.put("id", txnMap.get("TxnId").toString());
                    invoice.put("amount", lineMap.get("Amount"));
                    invoices.add(invoice);
                }
            }
        }
        return invoices;
    }

    @Builder
    @Getter
    public static class Output implements io.kestra.core.models.tasks.Output {
        @Schema(
            title = "Payment",
            description = "The oldest new payment, as returned by QuickBooks. Its QuickBooks identifier is `payment.Id` and is also available as `payment.id`."
        )
        @NotNull
        private final Map<String, Object> payment;

        @Schema(
            title = "Linked invoices",
            description = "The invoices applied to `payment`, read from its `Line[].LinkedTxn[]` entries of type `Invoice`. Each entry holds the invoice `id` and the `amount` applied."
        )
        @NotNull
        private final List<Map<String, Object>> linkedInvoices;

        @Schema(
            title = "Payments",
            description = "All new payments of this poll, oldest first. Includes `payment`."
        )
        @NotNull
        private final List<Map<String, Object>> payments;

        @Schema(
            title = "Count",
            description = "The number of new payments in this poll."
        )
        @NotNull
        private final Integer count;
    }
}
