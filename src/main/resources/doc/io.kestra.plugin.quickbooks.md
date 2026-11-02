# How to use the QuickBooks plugin

This plugin reads and writes Intuit QuickBooks Online (QBO) data from Kestra workflows: run queries and reports, list or fetch entities, send, download, create or update invoices, and start flows from QuickBooks changes and webhooks. It requires Kestra 2.0.2 or later (the plugin is built against Kestra 2.0.2 and Java 25).

Property values that are enums, such as `status`, `event` and `accountingMethod`, use the uppercase constants listed below and are case-sensitive.

## Setup
Because the plugin encrypts the saved OAuth tokens before persisting them in Kestra's Key-Value store, your Kestra instance must have a valid `kestra.encryption.secret-key` configured.

## Authentication

All tasks in this plugin extend the `AbstractQuickBooksConnection` and require standard OAuth2 credentials. The plugin features an automatic token rotation system using Kestra's internal Key-Value (KV) store, removing the need for you to build complex token refresh flows.

> [!WARNING]
> Due to Intuit's refresh token rotation policy, token refreshes across multiple workers are only guarded by a **best-effort KV lease**, not a distributed lock. Running multiple QuickBooks tasks concurrently across different Kestra workers may fail with "Another worker is currently rotating the QuickBooks token" (configure task retries) or, in the worst case, lose a token and fail with `invalid_grant`.

To authenticate your tasks, provide the following properties. We recommend using [Kestra Secrets](https://kestra.io/docs/concepts/secret) and [Plugin Defaults](https://kestra.io/docs/concepts/plugin-defaults) to manage them:

- `clientId`: Your QuickBooks application's Client ID.
- `clientSecret`: Your QuickBooks application's Client Secret.
- `refreshToken`: A valid, active OAuth2 Refresh Token. The plugin will automatically rotate it and persist the new token in the Kestra namespace KV-store.
- `realmId`: Your QuickBooks Realm ID (also known as the Company ID).

Example connection setup:
```yaml
clientId: "{{ secret('QBO_CLIENT_ID') }}"
clientSecret: "{{ secret('QBO_CLIENT_SECRET') }}"
refreshToken: "{{ secret('QBO_REFRESH_TOKEN') }}"
realmId: "{{ secret('QBO_REALM_ID') }}"
```

## Features
- **Automatic Token Management**: Kestra handles token refreshes and securely stores tokens across executions.
- **Support for Sandbox and Production**: Configure the `baseUrl` to point to `https://sandbox-quickbooks.api.intuit.com` for sandbox testing or leave it as default for production.
- **Kestra baseline**: built and tested against Kestra 2.0.2 and Java 25.
- **Custom Minor Versions**: Easily switch QuickBooks API minor versions via the `minorVersion` property (defaults to `75`).

## Tasks

### Query (`io.kestra.plugin.quickbooks.query.Query`)
Execute a SQL-like query against QuickBooks Online entities (Invoice, Customer, Payment, Bill, Vendor, Item, Account, etc.).

- Automatically paginates using `STARTPOSITION` and `MAXRESULTS` (max 1000 per page).
- Supports `fetchType`: `STORE` (writes results to internal storage), `FETCH` (returns rows in memory), or `FETCH_ONE` (returns a single row).
- Retries on HTTP 429 (rate limit) with exponential backoff.

```yaml
id: qbo_open_invoices
namespace: company.finance
tasks:
  - id: open_invoices
    type: io.kestra.plugin.quickbooks.query.Query
    clientId: "{{ secret('QBO_CLIENT_ID') }}"
    clientSecret: "{{ secret('QBO_CLIENT_SECRET') }}"
    refreshToken: "{{ secret('QBO_REFRESH_TOKEN') }}"
    realmId: "{{ secret('QBO_REALM_ID') }}"
    query: "select * from Invoice where Balance > '0'"
    fetchType: STORE
```

### Changes (`io.kestra.plugin.quickbooks.query.Changes`)
Reads the Change Data Capture endpoint for `entities` changed since `changedSince` (QuickBooks keeps about 30 days of changes). Outputs `rows`, a map from each entity to its changed records (`id`, `status`, `deleted`), and the full `cdcResponse`.

### Reports (`io.kestra.plugin.quickbooks.reports.Run`)
Runs a report: `report` (a string, case-insensitive, one of `AgedReceivables`, `AgedReceivableDetail`, `AgedPayables`, `ProfitAndLoss`, `BalanceSheet`, `GeneralLedger`, `CustomerBalance`, `TrialBalance` or `CashFlow`), plus optional `startDate`, `endDate`, `summarizeColumnBy`, `customer`, `accountingMethod` (`CASH` or `ACCRUAL`) and a free `parameters` map for any other report parameter.

Outputs depend on two properties, so large reports do not bloat the execution:
- `fetchType`: `FETCH` (default) returns the flattened `rows` (keyed by column title, with `section` and `type`), `STORE` writes them to an Ion file returned as `uri`, `NONE` returns neither. `size` is always the row count. `FETCH_ONE` is not supported.
- `includeRaw` (default `false`): also returns the untouched QuickBooks response as `raw`.

```yaml
id: qbo_profit_and_loss
namespace: company.finance
tasks:
  - id: pnl
    type: io.kestra.plugin.quickbooks.reports.Run
    clientId: "{{ secret('QBO_CLIENT_ID') }}"
    clientSecret: "{{ secret('QBO_CLIENT_SECRET') }}"
    refreshToken: "{{ secret('QBO_REFRESH_TOKEN') }}"
    realmId: "{{ secret('QBO_REALM_ID') }}"
    report: ProfitAndLoss
    startDate: "2026-01-01"
    endDate: "2026-03-31"
```

### Customers, Payments, Bills, Vendors, Items, Accounts
Each of these sub-packages has a `List` task (`fetchType`, optional `where` and `updatedSince`) and a `Get` task that fetches one record by `entityId`.

### Company info (`io.kestra.plugin.quickbooks.companyinfo.Get`)
Fetches the company information of the realm and outputs `row`.

### Invoices

#### List (`io.kestra.plugin.quickbooks.invoices.List`)
Builds `select * from Invoice where ...` from typed filters, all combined with `AND` and with the optional raw `where`: `customerId`, `status` (`OPEN`, `OVERDUE` or `PAID`), `dueBefore`, `dueAfter`, `txnDateFrom`, `txnDateTo`, `balanceMin` and `updatedSince`. String values are escaped (backslash and single quote), dates and amounts are typed, so a filter cannot change the rest of the query. `fetchType` works as on `Query`. Every `List` task also accepts `updatedSince` (`MetaData.LastUpdatedTime >=`).

```yaml
id: qbo_overdue_invoices
namespace: company.finance
tasks:
  - id: overdue
    type: io.kestra.plugin.quickbooks.invoices.List
    clientId: "{{ secret('QBO_CLIENT_ID') }}"
    clientSecret: "{{ secret('QBO_CLIENT_SECRET') }}"
    refreshToken: "{{ secret('QBO_REFRESH_TOKEN') }}"
    realmId: "{{ secret('QBO_REALM_ID') }}"
    status: OVERDUE
    balanceMin: 100
    fetchType: STORE
```

#### Send (`io.kestra.plugin.quickbooks.invoices.Send`)
Emails an invoice (`invoiceId`, the QuickBooks invoice `Id`, and optional `sendTo`) and outputs `emailStatus`. The customer receives a real email.

#### Pdf (`io.kestra.plugin.quickbooks.invoices.Pdf`)
Downloads the invoice PDF (`invoiceId`) to internal storage and outputs `uri` and `size`. The body is streamed to disk, not held in memory.

#### Create and Update (`io.kestra.plugin.quickbooks.invoices.Create`, `io.kestra.plugin.quickbooks.invoices.Update`)
> [!WARNING]
> These tasks write to your QuickBooks company. They are not behind any feature flag yet.

`Create` posts the `invoice` body. Set `requestId` (at most 50 characters, sent as the QuickBooks `requestid` idempotency key, for example `{{ execution.id }}-{{ taskrun.id }}`) so a retry after a timeout does not create a duplicate invoice. `Update` needs `invoiceId`, `syncToken` and the `invoice` fields to change (`sparse` defaults to `true`; with `false` QuickBooks replaces the whole invoice). Both output `row`, `id`, `syncToken` and `totalAmt`.

## Triggers

Polling triggers keep their watermark (or the ids they already reported) in the namespace KV store and only update it once the execution has been built. A polling trigger starts at most one execution per evaluation, so the batch outputs (`invoices`, `payments`) are the way to process every change of a poll.

### InvoiceTrigger (`io.kestra.plugin.quickbooks.invoices.InvoiceTrigger`)
Polls Change Data Capture for `Invoice` changes. `event` is `CREATED` (default), `UPDATED` or `PAID`. Outputs `invoice`, `invoices` and `count`.

### PaymentReceivedTrigger (`io.kestra.plugin.quickbooks.payments.PaymentReceivedTrigger`)
Polls Change Data Capture for new `Payment` records. Outputs `payment`, `linkedInvoices` (the invoices applied to the payment), `payments` and `count`.

```yaml
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
```

### OverdueInvoiceTrigger (`io.kestra.plugin.quickbooks.invoices.OverdueInvoiceTrigger`)
Polls (daily by default) for invoices with `Balance > '0' AND DueDate < '<today>'`. `mode` is `BATCH` (one execution with all new overdue invoices) or `PER_INVOICE` (one invoice per execution, one execution per evaluation). An invoice is reported once while it stays overdue. Outputs `invoices`, `count` and `daysOverdue`.

### WebhookTrigger (`io.kestra.plugin.quickbooks.trigger.WebhookTrigger`)
Receives QuickBooks webhooks. Every request must carry a valid `intuit-signature` header, the base64 HMAC-SHA256 of the raw payload keyed with your `verifierToken`; otherwise the trigger answers `401` and starts nothing. Keep the inherited `fetchType` at `FETCH` (the default): with `STORE` or `NONE` the body is not available to check the signature, so the trigger answers `500` and starts nothing. Outputs `eventNotifications`.

### CdcTrigger (`io.kestra.plugin.quickbooks.trigger.CdcTrigger`)
Polls Change Data Capture for any list of entities and outputs the raw `cdcResponse`.

## Enum values

| Property | Values |
|---|---|
| `invoices.List` `status` | `OPEN`, `OVERDUE`, `PAID` |
| `invoices.InvoiceTrigger` `event` | `CREATED`, `UPDATED`, `PAID` |
| `reports.Run` `accountingMethod` | `CASH`, `ACCRUAL` |
| `reports.Run` `fetchType` | `FETCH`, `STORE`, `NONE` |
| `query.Query` and `List` tasks `fetchType` | `FETCH`, `FETCH_ONE`, `STORE`, `NONE` |
