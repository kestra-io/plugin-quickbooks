# Kestra Quickbooks Plugin

## What

- Provides plugin components under `io.kestra.plugin.quickbooks`.
- Includes the base class `AbstractQuickBooksConnection`.

## Why

- What user problem does this solve? Teams need a concrete starting point for building and validating new Kestra plugins without recreating the same project scaffolding from scratch.
- Why would a team adopt this plugin in a workflow? It gives plugin authors a ready-made reference repo they can adapt alongside their own build, test, and publishing workflow.
- What operational/business outcome does it enable? It shortens plugin delivery time, reduces setup mistakes, and makes internal or partner plugin development more repeatable.

## How

### Architecture

Single-module plugin. Source packages under `io.kestra.plugin`:

- `quickbooks`
- `quickbooks.query` (`Query`, `Changes`)
- `quickbooks.invoices`, `quickbooks.customers`, `quickbooks.payments`, `quickbooks.bills`, `quickbooks.vendors`, `quickbooks.items`, `quickbooks.accounts`, `quickbooks.companyinfo`
- `quickbooks.reports` (`Run`)
- `quickbooks.trigger` (`CdcTrigger`, `WebhookTrigger`)

Infrastructure dependencies (Docker Compose services):

- `app`

### Key Plugin Classes

- `io.kestra.plugin.quickbooks.AbstractQuickBooksConnection`
- `io.kestra.plugin.quickbooks.query.Query`, `io.kestra.plugin.quickbooks.query.Changes`
- `io.kestra.plugin.quickbooks.reports.Run`

### Project Structure

```
plugin-quickbooks/
├── src/main/java/io/kestra/plugin/quickbooks/
├── src/test/java/io/kestra/plugin/quickbooks/
├── build.gradle
└── README.md
```

## Local rules

- Base the wording on the implemented packages and classes, not on template README text.

## References

- https://kestra.io/docs/plugin-developer-guide
- https://kestra.io/docs/plugin-developer-guide/contribution-guidelines
