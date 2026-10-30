package io.kestra.plugin.quickbooks.invoices;

import java.time.Duration;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

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
import io.kestra.core.serializers.JacksonMapper;
import io.kestra.core.storages.kv.KVValueAndMetadata;
import io.kestra.plugin.quickbooks.trigger.AbstractQuickBooksTrigger;
import io.swagger.v3.oas.annotations.media.Schema;

import com.fasterxml.jackson.core.type.TypeReference;
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
            title = "Send one daily batch of newly overdue invoices",
            full = true,
            code = """
                id: qbo_overdue_invoices
                namespace: company.finance

                triggers:
                  - id: overdue
                    type: io.kestra.plugin.quickbooks.invoices.OverdueInvoiceTrigger
                    clientId: "{{ secret('QBO_CLIENT_ID') }}"
                    clientSecret: "{{ secret('QBO_CLIENT_SECRET') }}"
                    refreshToken: "{{ secret('QBO_REFRESH_TOKEN') }}"
                    realmId: "{{ secret('QBO_REALM_ID') }}"
                    mode: BATCH
                    interval: P1D

                tasks:
                  - id: log
                    type: io.kestra.plugin.core.log.Log
                    message: "{{ trigger.count }} invoice(s) overdue, up to {{ trigger.daysOverdue }} day(s)"
                """
        ),
        @Example(
            title = "Start one execution per overdue invoice, checking every hour",
            full = true,
            code = """
                id: qbo_overdue_invoice_reminder
                namespace: company.finance

                triggers:
                  - id: overdue
                    type: io.kestra.plugin.quickbooks.invoices.OverdueInvoiceTrigger
                    clientId: "{{ secret('QBO_CLIENT_ID') }}"
                    clientSecret: "{{ secret('QBO_CLIENT_SECRET') }}"
                    refreshToken: "{{ secret('QBO_REFRESH_TOKEN') }}"
                    realmId: "{{ secret('QBO_REALM_ID') }}"
                    mode: PER_INVOICE
                    interval: PT1H

                tasks:
                  - id: log
                    type: io.kestra.plugin.core.log.Log
                    message: "Invoice {{ trigger.invoices[0].DocNumber }} is {{ trigger.daysOverdue }} day(s) overdue"
                """
        )
    }
)
@Schema(
    title = "Trigger a flow when QuickBooks invoices become overdue",
    description = """
        Polls the QuickBooks query API for invoices matching `Balance > '0' AND DueDate < '<today>'` (today in UTC) and starts an execution for the ones not reported yet. An invoice is reported once while it stays overdue and is not reported again on later polls.

        A polling trigger starts at most one execution per evaluation. With `BATCH` that execution holds every new overdue invoice. With `PER_INVOICE` it holds the oldest not yet reported one, and the next ones follow on the next evaluations, so use a short `interval` when many invoices can become overdue at once.

        The reported invoice ids are stored in the namespace KV store, only after the execution has been built, and are dropped once the invoice is no longer overdue. All overdue invoices are loaded in memory on every poll."""
)
public class OverdueInvoiceTrigger extends AbstractQuickBooksTrigger implements TriggerOutput<OverdueInvoiceTrigger.Output> {

    @Schema(
        title = "Execution mode",
        description = """
            - `BATCH`: one execution with all newly overdue invoices in `invoices`.
            - `PER_INVOICE`: one execution per invoice, `invoices` holds a single invoice. Executions are created one per evaluation, oldest due date first.""",
        defaultValue = "BATCH"
    )
    @PluginProperty(group = "main")
    @Builder.Default
    private Property<Mode> mode = Property.ofValue(Mode.BATCH);

    @Schema(
        title = "Polling interval",
        description = "How often the trigger should look for overdue invoices.",
        defaultValue = "P1D"
    )
    @Builder.Default
    private final Duration interval = Duration.ofDays(1);

    @Override
    public Duration getInterval() {
        return this.interval;
    }

    @Override
    public Optional<Execution> evaluate(ConditionContext conditionContext, TriggerContext context) throws Exception {
        var runContext = conditionContext.getRunContext();
        var rMode = runContext.render(this.mode).as(Mode.class).orElse(Mode.BATCH);

        LocalDate today = LocalDate.now(ZoneOffset.UTC);
        var query = "select * from Invoice where Balance > '0' AND DueDate < '" + today + "' orderby DueDate";

        var overdue = new ArrayList<>(fetchRows(runContext, query));
        // DueDate values are ISO dates: lexicographic order is chronological, oldest due date first.
        overdue.sort(Comparator.comparing(invoice -> String.valueOf(invoice.get("DueDate"))));

        var kvStore = runContext.namespaceKv(context.getNamespace());
        var kvKey = stateKey("quickbooks_overdue_trigger_", context);
        Set<String> reported = readReported(kvStore.getValue(kvKey).map(val -> val.value().toString()));

        Set<String> overdueIds = new LinkedHashSet<>();
        var pending = new ArrayList<Map<String, Object>>();
        for (var invoice : overdue) {
            var id = invoice.get("Id");
            if (id == null) {
                continue;
            }
            overdueIds.add(id.toString());
            if (!reported.contains(id.toString())) {
                pending.add(invoice);
            }
        }

        // Forget invoices that are no longer overdue (paid, voided, deleted) so the state stays bounded.
        Set<String> stillReported = new LinkedHashSet<>(reported);
        stillReported.retainAll(overdueIds);

        if (pending.isEmpty()) {
            if (stillReported.size() != reported.size()) {
                writeReported(kvStore, kvKey, stillReported);
            }
            return Optional.empty();
        }

        List<Map<String, Object>> selected = rMode == Mode.PER_INVOICE ? List.of(pending.get(0)) : pending;
        int daysOverdue = (int) selected.stream()
            .mapToLong(invoice -> daysOverdue(invoice, today))
            .max()
            .orElse(0);

        runContext.logger().info("Found {} newly overdue invoice(s), reporting {}", pending.size(), selected.size());

        Execution execution = TriggerService.generateExecution(this, conditionContext, context, Output.builder()
            .invoices(selected)
            .count(selected.size())
            .daysOverdue(daysOverdue)
            .build());

        // Remember the invoices only once the execution exists, otherwise a failure would drop them silently.
        selected.forEach(invoice -> stillReported.add(invoice.get("Id").toString()));
        writeReported(kvStore, kvKey, stillReported);
        return Optional.of(execution);
    }

    private static long daysOverdue(Map<String, Object> invoice, LocalDate today) {
        try {
            return Math.max(0, ChronoUnit.DAYS.between(LocalDate.parse(String.valueOf(invoice.get("DueDate"))), today));
        } catch (Exception e) {
            return 0;
        }
    }

    private static Set<String> readReported(Optional<String> stored) throws Exception {
        if (stored.isEmpty()) {
            return new LinkedHashSet<>();
        }
        List<String> ids = JacksonMapper.ofJson().readValue(stored.get(), new TypeReference<List<String>>() {});
        return new LinkedHashSet<>(ids);
    }

    private static void writeReported(io.kestra.core.storages.kv.KVStore kvStore, String key, Set<String> ids) throws Exception {
        kvStore.put(key, new KVValueAndMetadata(null, JacksonMapper.ofJson().writeValueAsString(new ArrayList<>(ids))));
    }

    public enum Mode {
        BATCH,
        PER_INVOICE
    }

    @Builder
    @Getter
    public static class Output implements io.kestra.core.models.tasks.Output {
        @Schema(
            title = "Invoices",
            description = "The overdue invoices of this execution, oldest due date first, as returned by QuickBooks."
        )
        @NotNull
        private final List<Map<String, Object>> invoices;

        @Schema(
            title = "Count",
            description = "The number of invoices in `invoices`."
        )
        @NotNull
        private final Integer count;

        @Schema(
            title = "Days overdue",
            description = "The number of days past due date, for the most overdue invoice of `invoices` (UTC, never negative)."
        )
        @NotNull
        private final Integer daysOverdue;
    }
}
