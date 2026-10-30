package io.kestra.plugin.quickbooks.reports;

import java.time.LocalDate;
import java.util.Map;

import io.kestra.core.models.property.Property;
import io.kestra.plugin.quickbooks.AbstractTaskWireMockTest;

import org.junit.jupiter.api.Test;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.notNullValue;
import static org.junit.jupiter.api.Assertions.assertThrows;

public class RunTest extends AbstractTaskWireMockTest {

    private static final String PNL = """
        {
          "Header": {"ReportName": "ProfitAndLoss", "ReportBasis": "Accrual"},
          "Columns": {"Column": [{"ColTitle": ""}, {"ColTitle": "Total"}]},
          "Rows": {"Row": [
            {"type": "Section", "Header": {"ColData": [{"value": "Income"}, {"value": ""}]},
             "Rows": {"Row": [{"type": "Data", "ColData": [{"value": "Services"}, {"value": "100.00"}]}]},
             "Summary": {"ColData": [{"value": "Total Income"}, {"value": "100.00"}]}},
            {"type": "Section", "Summary": {"ColData": [{"value": "Net Income"}, {"value": "40.00"}]}}
          ]}
        }""";

    private Run.RunBuilder<?, ?> builder() {
        return Run.builder()
            .clientId(Property.ofValue("client_id"))
            .clientSecret(Property.ofValue("client_secret"))
            .refreshToken(Property.ofValue("refresh_token"))
            .realmId(Property.ofValue("12345"))
            .baseUrl(Property.ofValue("http://localhost:" + wireMockServer.port()))
            .authUrl(Property.ofValue("http://localhost:" + wireMockServer.port() + "/oauth2/v1/tokens/bearer"));
    }

    private static void stubReport(String name) {
        stubFor(get(urlPathEqualTo("/v3/company/12345/reports/" + name))
            .willReturn(aResponse().withHeader("Content-Type", "application/json").withBody(PNL)));
    }

    @Test
    void runsTheIssueExampleAndFlattensRows() throws Exception {
        stubReport("ProfitAndLoss");

        var run = builder()
            .report(Property.ofValue("ProfitAndLoss"))
            .startDate(Property.ofValue(LocalDate.of(2026, 1, 1)))
            .endDate(Property.ofValue(LocalDate.of(2026, 3, 31)))
            .build()
            .run(runContext());

        verify(getRequestedFor(urlPathEqualTo("/v3/company/12345/reports/ProfitAndLoss"))
            .withQueryParam("minorversion", equalTo("75"))
            .withQueryParam("start_date", equalTo("2026-01-01"))
            .withQueryParam("end_date", equalTo("2026-03-31"))
            .withQueryParam("accounting_method", absent())
            .withQueryParam("customer", absent())
            .withQueryParam("summarize_column_by", absent()));

        assertThat(run.getRaw().get("Header"), is(notNullValue()));
        assertThat(run.getRows().size(), is(3));
        assertThat(run.getRows().get(0), is(Map.of("column1", "Services", "Total", "100.00", "section", "Income", "type", "Data")));
        assertThat(run.getRows().get(1), is(Map.of("column1", "Total Income", "Total", "100.00", "section", "", "type", "Summary")));
        assertThat(run.getRows().get(2).get("column1"), is("Net Income"));
    }

    @Test
    void buildsTheQueryStringFromTypedProperties() throws Exception {
        stubReport("AgedReceivables");

        builder()
            .report(Property.ofValue("AgedReceivables"))
            .summarizeColumnBy(Property.ofValue("Month"))
            .customer(Property.ofValue("58"))
            .accountingMethod(Property.ofValue("Accrual"))
            .parameters(Property.ofValue(Map.of("department", "North & South")))
            .build()
            .run(runContext());

        verify(getRequestedFor(urlPathEqualTo("/v3/company/12345/reports/AgedReceivables"))
            .withQueryParam("summarize_column_by", equalTo("Month"))
            .withQueryParam("customer", equalTo("58"))
            .withQueryParam("accounting_method", equalTo("Accrual"))
            .withQueryParam("department", equalTo("North & South")));
    }

    @Test
    void typedPropertiesWinOverParameters() throws Exception {
        stubReport("ProfitAndLoss");

        builder()
            .report(Property.ofValue("ProfitAndLoss"))
            .customer(Property.ofValue("58"))
            .parameters(Property.ofValue(Map.of("customer", "1", "start_date", "2025-01-01")))
            .build()
            .run(runContext());

        verify(getRequestedFor(urlPathEqualTo("/v3/company/12345/reports/ProfitAndLoss"))
            .withQueryParam("customer", equalTo("58"))
            .withQueryParam("start_date", equalTo("2025-01-01")));
    }

    @Test
    void reportAndAccountingMethodAreCaseInsensitive() throws Exception {
        stubReport("BalanceSheet");

        builder()
            .report(Property.ofValue("balancesheet"))
            .accountingMethod(Property.ofValue("cASH"))
            .build()
            .run(runContext());

        verify(getRequestedFor(urlPathEqualTo("/v3/company/12345/reports/BalanceSheet"))
            .withQueryParam("accounting_method", equalTo("Cash")));
    }

    @Test
    void unknownAccountingMethodFailsBeforeAnyCall() {
        var error = assertThrows(IllegalArgumentException.class, () -> builder()
            .report(Property.ofValue("ProfitAndLoss"))
            .accountingMethod(Property.ofValue("Hybrid"))
            .build()
            .run(runContext()));

        assertThat(error.getMessage().contains("Cash, Accrual"), is(true));
        verify(0, postRequestedFor(urlEqualTo("/oauth2/v1/tokens/bearer")));
    }

    @Test
    void unknownReportFails() {
        var error = assertThrows(IllegalArgumentException.class, () -> builder()
            .report(Property.ofValue("Payroll"))
            .build()
            .run(runContext()));

        assertThat(error.getMessage().contains("AgedReceivables"), is(true));
    }
}
