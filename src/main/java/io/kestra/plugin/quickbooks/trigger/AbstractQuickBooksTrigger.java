package io.kestra.plugin.quickbooks.trigger;

import java.time.OffsetDateTime;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import jakarta.validation.constraints.NotNull;

import io.kestra.core.models.annotations.PluginProperty;
import io.kestra.core.models.property.Property;
import io.kestra.core.models.tasks.common.FetchType;
import io.kestra.core.models.triggers.AbstractTrigger;
import io.kestra.core.models.triggers.PollingTriggerInterface;
import io.kestra.core.models.triggers.TriggerContext;
import io.kestra.core.runners.RunContext;
import io.kestra.core.storages.kv.KVStore;
import io.kestra.core.storages.kv.KVValueAndMetadata;
import io.kestra.plugin.quickbooks.cdc.Cdc;
import io.kestra.plugin.quickbooks.query.Query;
import io.swagger.v3.oas.annotations.media.Schema;

import lombok.Builder;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.ToString;
import lombok.experimental.SuperBuilder;

/**
 * Connection properties and polling helpers shared by the QuickBooks polling triggers.
 * A trigger cannot extend {@code AbstractQuickBooksConnection} (it is a Task), so the connection
 * properties are declared here and forwarded to the {@link Cdc} and {@link Query} tasks it reuses.
 */
@SuperBuilder
@ToString
@EqualsAndHashCode(callSuper = true)
@Getter
@NoArgsConstructor
public abstract class AbstractQuickBooksTrigger extends AbstractTrigger implements PollingTriggerInterface {

    @Schema(
        title = "QuickBooks Client ID"
    )
    @PluginProperty(secret = true, group = "connection")
    @NotNull
    @ToString.Exclude
    protected Property<String> clientId;

    @Schema(
        title = "QuickBooks Client Secret"
    )
    @PluginProperty(secret = true, group = "connection")
    @NotNull
    @ToString.Exclude
    protected Property<String> clientSecret;

    @Schema(
        title = "QuickBooks Refresh Token"
    )
    @PluginProperty(secret = true, group = "connection")
    @NotNull
    @ToString.Exclude
    protected Property<String> refreshToken;

    @Schema(
        title = "QuickBooks Realm ID"
    )
    @PluginProperty(group = "connection")
    @NotNull
    protected Property<String> realmId;

    @Schema(
        title = "Base URL",
        description = "The Base URL for QuickBooks API. Must be an intuit.com domain. Defaults to production.",
        defaultValue = "https://quickbooks.api.intuit.com"
    )
    @PluginProperty(group = "connection")
    @Builder.Default
    protected Property<String> baseUrl = Property.ofValue("https://quickbooks.api.intuit.com");

    @Schema(
        title = "OAuth Token URL",
        description = "The OAuth2 token endpoint. Must be an intuit.com domain.",
        defaultValue = "https://oauth.platform.intuit.com/oauth2/v1/tokens/bearer"
    )
    @PluginProperty(group = "connection")
    @Builder.Default
    protected Property<String> authUrl = Property.ofValue("https://oauth.platform.intuit.com/oauth2/v1/tokens/bearer");

    @Schema(
        title = "Minor Version",
        description = "The minor version of the QuickBooks API to use. Must be 1 or more, checked after rendering.",
        defaultValue = "75"
    )
    @PluginProperty(group = "connection")
    @Builder.Default
    protected Property<Integer> minorVersion = Property.ofValue(75);

    /**
     * Key of the state stored in the namespace KV store for this trigger. The flow id length prefix keeps
     * keys unambiguous when a flow id contains the separator.
     */
    protected static String stateKey(String prefix, TriggerContext context) {
        var flowId = context.getFlowId();
        return prefix + (flowId != null ? flowId.length() : 0) + "_" + flowId + "_" + context.getTriggerId();
    }

    /**
     * Reads the CDC watermark: the stored value, else the trigger date, else 24 hours ago.
     */
    protected static ZonedDateTime readWatermark(KVStore kvStore, String key, TriggerContext context) throws Exception {
        var stored = kvStore.getValue(key).map(val -> val.value().toString());
        if (stored.isPresent()) {
            return ZonedDateTime.parse(stored.get(), DateTimeFormatter.ISO_OFFSET_DATE_TIME);
        }
        if (context.getDate() != null) {
            return context.getDate();
        }
        return ZonedDateTime.now().minusHours(24);
    }

    protected static void writeWatermark(KVStore kvStore, String key, ZonedDateTime watermark) throws Exception {
        kvStore.put(key, new KVValueAndMetadata(null, watermark.format(DateTimeFormatter.ISO_OFFSET_DATE_TIME)));
    }

    protected Cdc.Output fetchChanges(RunContext runContext, List<String> entities, ZonedDateTime changedSince) throws Exception {
        return Cdc.builder()
            .id(this.id)
            .type(Cdc.class.getName())
            .clientId(this.clientId)
            .clientSecret(this.clientSecret)
            .refreshToken(this.refreshToken)
            .realmId(this.realmId)
            .baseUrl(this.baseUrl)
            .authUrl(this.authUrl)
            .minorVersion(this.minorVersion)
            .entities(Property.ofValue(entities))
            .changedSince(Property.ofValue(changedSince.format(DateTimeFormatter.ISO_OFFSET_DATE_TIME)))
            .build()
            .run(runContext);
    }

    /**
     * Runs a QBO query through the {@link Query} task with {@code FETCH}, so rows come back in memory.
     */
    protected List<Map<String, Object>> fetchRows(RunContext runContext, String query) throws Exception {
        var output = Query.builder()
            .id(this.id)
            .type(Query.class.getName())
            .clientId(this.clientId)
            .clientSecret(this.clientSecret)
            .refreshToken(this.refreshToken)
            .realmId(this.realmId)
            .baseUrl(this.baseUrl)
            .authUrl(this.authUrl)
            .minorVersion(this.minorVersion)
            .fetchType(Property.ofValue(FetchType.FETCH))
            .query(Property.ofValue(query))
            .build()
            .run(runContext);

        List<Map<String, Object>> rows = new ArrayList<>();
        if (output.getRows() != null) {
            for (Object row : output.getRows()) {
                if (row instanceof Map<?, ?> map) {
                    @SuppressWarnings("unchecked")
                    var typed = (Map<String, Object>) map;
                    rows.add(typed);
                }
            }
        }
        return rows;
    }

    /**
     * Extracts the records of one entity from a CDC response:
     * {@code CDCResponse[].QueryResponse[].<entity>[]}.
     */
    @SuppressWarnings("unchecked")
    protected static List<Map<String, Object>> changedEntities(Cdc.Output output, String entity) {
        List<Map<String, Object>> records = new ArrayList<>();
        if (output == null || output.getCdcResponse() == null) {
            return records;
        }
        if (!(output.getCdcResponse().get("CDCResponse") instanceof List<?> responses)) {
            return records;
        }
        for (Object response : responses) {
            if (!(response instanceof Map<?, ?> responseMap) || !(responseMap.get("QueryResponse") instanceof List<?> queryResponses)) {
                continue;
            }
            for (Object queryResponse : queryResponses) {
                if (queryResponse instanceof Map<?, ?> qr && qr.get(entity) instanceof List<?> list) {
                    for (Object record : list) {
                        if (record instanceof Map<?, ?> map) {
                            records.add((Map<String, Object>) map);
                        }
                    }
                }
            }
        }
        return records;
    }

    protected static boolean isDeleted(Map<String, Object> record) {
        return "Deleted".equalsIgnoreCase(String.valueOf(record.get("status")));
    }

    /**
     * Reads {@code MetaData.<field>} (e.g. {@code CreateTime}) as an instant, or {@code null} when absent or malformed.
     */
    protected static OffsetDateTime metaTime(Map<String, Object> record, String field) {
        if (record.get("MetaData") instanceof Map<?, ?> meta && meta.get(field) != null) {
            try {
                return OffsetDateTime.parse(meta.get(field).toString());
            } catch (Exception e) {
                return null;
            }
        }
        return null;
    }
}
