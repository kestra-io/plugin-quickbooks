package io.kestra.plugin.quickbooks;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;

import io.kestra.core.models.annotations.PluginProperty;
import io.kestra.core.models.property.Property;
import io.kestra.core.models.tasks.common.FetchOutput;
import io.kestra.core.runners.RunContext;
import io.kestra.plugin.quickbooks.query.AbstractQuery;
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
public abstract class AbstractList extends AbstractQuery {

    @Schema(
        title = "Optional WHERE clause to filter records",
        description = "Raw QuickBooks query condition, for example `Balance > '0'`. It is sent as is, so escape any value you build from untrusted input yourself (a single quote is written `\\'`). It is combined with the typed filters using `AND` (QuickBooks queries have no `OR`)."
    )
    @PluginProperty(group = "main")
    protected Property<String> where;

    @Schema(
        title = "Only records updated since",
        description = "Keeps records whose `MetaData.LastUpdatedTime` is greater than or equal to this ISO 8601 instant, for example `2026-01-01T00:00:00Z`. Sub-second precision is dropped."
    )
    @PluginProperty(group = "main")
    protected Property<Instant> updatedSince;

    protected abstract String entityName();

    /**
     * Extra, already escaped, conditions of a specific entity. They are joined with the generic ones using AND.
     */
    protected java.util.List<String> conditions(RunContext runContext) throws Exception {
        return java.util.List.of();
    }

    /**
     * Quotes a value as a QuickBooks query string literal. Backslash is the escape character, so it is
     * escaped first and then the single quote, which prevents a value from closing the literal.
     */
    public static String quote(String value) {
        return "'" + value.replace("\\", "\\\\").replace("'", "\\'") + "'";
    }

    @Override
    public FetchOutput run(RunContext runContext) throws Exception {
        var all = new ArrayList<String>();

        var rWhere = this.where != null ? runContext.render(this.where).as(String.class).orElse(null) : null;
        if (rWhere != null && !rWhere.isBlank()) {
            all.add(rWhere);
        }

        var rUpdatedSince = this.updatedSince != null ? runContext.render(this.updatedSince).as(Instant.class).orElse(null) : null;
        if (rUpdatedSince != null) {
            all.add("MetaData.LastUpdatedTime >= " + quote(rUpdatedSince.truncatedTo(ChronoUnit.SECONDS).toString()));
        }

        all.addAll(conditions(runContext));

        var queryStr = "select * from " + entityName();
        if (!all.isEmpty()) {
            queryStr += " where " + String.join(" AND ", all);
        }

        return this.run(runContext, queryStr);
    }
}
