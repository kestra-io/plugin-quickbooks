# QuickBooks Plugin

This plugin provides foundational connection and authentication components for Intuit QuickBooks Online (QBO) within Kestra workflows.

## Setup
Because the plugin encrypts the saved OAuth tokens before persisting them in Kestra's Key-Value store, your Kestra instance must have a valid `kestra.encryption.secret-key` configured.

## Authentication

All tasks in this plugin extend the `AbstractQuickBooksConnection` and require standard OAuth2 credentials. The plugin features an automatic token rotation system using Kestra's internal Key-Value (KV) store, removing the need for you to build complex token refresh flows.

> [!WARNING]
> Due to Intuit's refresh token rotation policy, **concurrent token refreshes across multiple workers are not serialized** via a distributed lock. Running multiple QuickBooks tasks concurrently across different Kestra workers may result in lost tokens or `invalid_grant` errors.

To authenticate your tasks, provide the following properties. We recommend using [Kestra Secrets](https://kestra.io/docs/concepts/secret) and [Plugin Defaults](https://kestra.io/docs/concepts/plugin-defaults) to manage them:

- `clientId`: Your QuickBooks application's Client ID.
- `clientSecret`: Your QuickBooks application's Client Secret.
- `refreshToken`: A valid, active OAuth2 Refresh Token. The plugin will automatically rotate it and persist the new token in the Kestra namespace KV-store.
- `realmId`: Your QuickBooks Realm ID (also known as the Company ID).

Example connection setup:
```yaml
clientId: "{{ secret('QUICKBOOKS_CLIENT_ID') }}"
clientSecret: "{{ secret('QUICKBOOKS_CLIENT_SECRET') }}"
refreshToken: "{{ secret('QUICKBOOKS_REFRESH_TOKEN') }}"
realmId: "{{ secret('QUICKBOOKS_REALM_ID') }}"
```

## Features
- **Automatic Token Management**: Kestra handles token refreshes and securely stores tokens across executions.
- **Support for Sandbox and Production**: Configure the `baseUrl` to point to `https://sandbox-quickbooks.api.intuit.com` for sandbox testing or leave it as default for production.
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

## Triggers

Polling triggers keep their watermark (or the ids they already reported) in the namespace KV store and only update it once the execution has been built. A polling trigger starts at most one execution per evaluation, so the batch outputs (`invoices`, `payments`) are the way to process every change of a poll.

### InvoiceTrigger (`io.kestra.plugin.quickbooks.invoices.InvoiceTrigger`)
Polls Change Data Capture for `Invoice` changes. `event` is `CREATED`, `UPDATED` or `PAID` (case-insensitive). Outputs `invoice`, `invoices` and `count`.

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
Receives QuickBooks webhooks. Every request must carry a valid `intuit-signature` header, the base64 HMAC-SHA256 of the raw payload keyed with your `verifierToken`; otherwise the trigger answers `401` and starts nothing. Outputs `eventNotifications`.

### CdcTrigger (`io.kestra.plugin.quickbooks.trigger.CdcTrigger`)
Polls Change Data Capture for any list of entities and outputs the raw `cdcResponse`.
