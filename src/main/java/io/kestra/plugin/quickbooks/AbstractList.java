package io.kestra.plugin.quickbooks;

import io.kestra.core.models.annotations.PluginProperty;
import io.kestra.core.models.property.Property;
import io.kestra.core.models.tasks.common.FetchOutput;
import io.kestra.core.runners.RunContext;
import io.kestra.plugin.quickbooks.query.Query;
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
public abstract class AbstractList extends Query {

    @Schema(
        title = "Optional WHERE clause to filter records",
        description = "Example: `Balance > '0'`"
    )
    @PluginProperty(group = "main")
    protected Property<String> where;

    protected abstract String entityName();

    @Override
    public FetchOutput run(RunContext runContext) throws Exception {
        String rWhere = this.where != null ? runContext.render(this.where).as(String.class).orElse(null) : null;
        String queryStr = "select * from " + entityName();
        if (rWhere != null && !rWhere.isBlank()) {
            queryStr += " where " + rWhere;
        }

        return this.run(runContext, queryStr);
    }
}
