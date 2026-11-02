# Kestra Quickbooks Plugin

## What

- Provides plugin components under `io.kestra.plugin.quickbooks`.
- Includes the base class `AbstractQuickBooksConnection` (OAuth2 refresh with a realm-scoped token in the KV store, `minorversion`, 429 backoff).
- Built against Kestra 2.0.2 and Java 25.

## Why

- What user problem does this solve? Finance and ops teams need QuickBooks Online data and invoice actions inside their workflows without hand-written OAuth, paging and rate-limit code.
- Why would a team adopt this plugin in a workflow? It handles token rotation, `STARTPOSITION` paging and 429 backoff, and starts flows from QuickBooks changes or webhooks.
- What operational/business outcome does it enable? Invoice follow-up, payment reconciliation and report exports run as declarative, event-driven flows.

## How

### Architecture

Single-module plugin. Source packages under `io.kestra.plugin`:

- `quickbooks`
- `quickbooks.query` (`Query`, `Changes`)
- `quickbooks.invoices` (`List`, `Get`, `Send`, `Pdf`, `Create`, `Update`, `InvoiceTrigger`, `OverdueInvoiceTrigger`)
- `quickbooks.customers`, `quickbooks.bills`, `quickbooks.vendors`, `quickbooks.items`, `quickbooks.accounts` (each with `List` and `Get`)
- `quickbooks.payments` (`List`, `Get`, `PaymentReceivedTrigger`)
- `quickbooks.companyinfo` (`Get`)
- `quickbooks.reports` (`Run`)
- `quickbooks.trigger` (`CdcTrigger`, `WebhookTrigger`)

Enum properties use uppercase, case-sensitive constants: `invoices.List.status` (`OPEN`, `OVERDUE`, `PAID`), `InvoiceTrigger.event` (`CREATED`, `UPDATED`, `PAID`), `reports.Run.accountingMethod` (`CASH`, `ACCRUAL`). `reports.Run.report` stays a string (QuickBooks CamelCase names).

Examples use the secrets `QBO_CLIENT_ID`, `QBO_CLIENT_SECRET`, `QBO_REFRESH_TOKEN` and `QBO_REALM_ID`.

Infrastructure dependencies (Docker Compose services):

- `app`

### Key Plugin Classes

- `io.kestra.plugin.quickbooks.AbstractQuickBooksConnection`
- `io.kestra.plugin.quickbooks.query.Query`, `io.kestra.plugin.quickbooks.query.Changes`
- `io.kestra.plugin.quickbooks.reports.Run`
- `io.kestra.plugin.quickbooks.invoices.Send`, `Pdf`, `Create`, `Update`
- Triggers: `io.kestra.plugin.quickbooks.invoices.InvoiceTrigger`, `io.kestra.plugin.quickbooks.invoices.OverdueInvoiceTrigger`, `io.kestra.plugin.quickbooks.payments.PaymentReceivedTrigger`, `io.kestra.plugin.quickbooks.trigger.CdcTrigger`, `io.kestra.plugin.quickbooks.trigger.WebhookTrigger`

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
