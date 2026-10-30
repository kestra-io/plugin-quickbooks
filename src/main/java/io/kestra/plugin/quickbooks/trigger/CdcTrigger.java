package io.kestra.plugin.quickbooks.trigger;

import io.kestra.core.models.annotations.Example;
import io.kestra.core.models.annotations.Plugin;
import io.kestra.core.models.annotations.PluginProperty;
import io.kestra.core.models.conditions.ConditionContext;
import io.kestra.core.models.executions.Execution;
import io.kestra.core.models.property.Property;
import io.kestra.core.models.triggers.TriggerContext;
import io.kestra.core.models.triggers.TriggerOutput;
import io.kestra.core.models.triggers.TriggerService;
import io.kestra.core.runners.RunContext;
import io.kestra.plugin.quickbooks.cdc.Cdc;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Builder;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.ToString;
import lombok.experimental.SuperBuilder;

import com.fasterxml.jackson.databind.JsonNode;
import io.kestra.core.serializers.JacksonMapper;
import jakarta.validation.constraints.NotNull;
import java.time.Duration;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.Optional;

@SuperBuilder
@ToString
@EqualsAndHashCode(callSuper = true)
@Getter
@NoArgsConstructor
@Plugin(
    examples = {
        @Example(
            title = "Trigger a flow when QuickBooks entities change",
            full = true,
            code = """
                id: quickbooks_cdc_trigger
                namespace: company.team
                triggers:
                  - id: watch_invoices
                    type: io.kestra.plugin.quickbooks.trigger.CdcTrigger
                    clientId: "{{ secret('QBO_CLIENT_ID') }}"
                    clientSecret: "{{ secret('QUICKBOOKS_CLIENT_SECRET') }}"
                    refreshToken: "{{ secret('QUICKBOOKS_REFRESH_TOKEN') }}"
                    realmId: "{{ secret('QBO_REALM_ID') }}"
                    entities:
                      - Invoice
                      - Customer
                    interval: "PT1H"
                tasks:
                  - id: process_changes
                    type: io.kestra.plugin.core.log.Log
                    message: "CDC Output: {{ trigger.cdcResponse }}"
                """
        )
    }
)
@Schema(
    title = "Trigger based on QuickBooks Change Data Capture (CDC)",
    description = "Periodically polls QuickBooks to fetch changed entities since the last successful poll."
)
public class CdcTrigger extends AbstractQuickBooksTrigger implements TriggerOutput<Cdc.Output> {

    @Schema(
        title = "Entities",
        description = "A list of QuickBooks entities to check for changes (e.g., 'Invoice', 'Customer')."
    )
    @PluginProperty(group = "main")
    @NotNull
    private Property<List<String>> entities;

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
        RunContext runContext = conditionContext.getRunContext();
        
        var kvStore = runContext.namespaceKv(context.getNamespace());
        var kvKey = stateKey("quickbooks_cdc_", context);
        ZonedDateTime changedSince = readWatermark(kvStore, kvKey, context);

        ZonedDateTime pollStartTime = ZonedDateTime.now();

        var output = fetchChanges(runContext, runContext.render(this.entities).asList(String.class), changedSince);

        boolean hasChanges = false;
        var cdcResponse = output.getCdcResponse();
        if (cdcResponse != null) {
            try {
                JsonNode rootNode = JacksonMapper.ofJson().valueToTree(cdcResponse);
                JsonNode responses = rootNode.get("CDCResponse");
                if (responses != null && responses.isArray()) {
                    for (JsonNode resp : responses) {
                        JsonNode queryResponses = resp.get("QueryResponse");
                        if (queryResponses != null && queryResponses.isArray()) {
                            for (JsonNode qResp : queryResponses) {
                                if (!qResp.isEmpty()) {
                                    hasChanges = true;
                                    break;
                                }
                            }
                        }
                    }
                }
            } catch (Exception e) {
                runContext.logger().warn("Failed to parse CDCResponse for changes check", e);
            }
        }

        writeWatermark(kvStore, kvKey, pollStartTime);

        if (hasChanges) {
            Execution execution = TriggerService.generateExecution(this, conditionContext, context, output);
            return Optional.of(execution);
        }

        return Optional.empty();
    }
}
