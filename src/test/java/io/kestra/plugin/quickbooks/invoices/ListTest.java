package io.kestra.plugin.quickbooks.invoices;

import io.kestra.core.models.property.Property;
import io.kestra.core.models.tasks.common.FetchType;
import io.kestra.plugin.quickbooks.AbstractTaskWireMockTest;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.Map;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.notNullValue;

public class ListTest extends AbstractTaskWireMockTest {

    @Test
    void testList() throws Exception {
        stubFor(get(urlPathEqualTo("/v3/company/12345/query"))
            .withQueryParam("query", equalTo("select * from Invoice STARTPOSITION 1 MAXRESULTS 1000"))
            .withQueryParam("minorversion", equalTo("75"))
            .willReturn(aResponse()
                .withHeader("Content-Type", "application/json")
                .withBody("{\"QueryResponse\": {\"Invoice\": [{\"Id\": \"1\", \"TotalAmt\": 250.0}], \"startPosition\": 1, \"maxResults\": 1000}}")));

        List task = List.builder()
            .clientId(Property.ofValue("client_id"))
            .clientSecret(Property.ofValue("client_secret"))
            .refreshToken(Property.ofValue("refresh_token"))
            .realmId(Property.ofValue("12345"))
            .baseUrl(Property.ofValue("http://localhost:" + wireMockServer.port()))
            .authUrl(Property.ofValue("http://localhost:" + wireMockServer.port() + "/oauth2/v1/tokens/bearer"))
            .fetchType(Property.ofValue(FetchType.FETCH))
            .build();

        var run = task.run(runContext());

        assertThat(run, notNullValue());
        assertThat(run.getSize(), is(1L));
        assertThat(run.getRows().size(), is(1));
        Map<String, Object> first = (Map<String, Object>) run.getRows().get(0);
        assertThat(first.get("Id"), is("1"));
    }

    private List.ListBuilder<?, ?> builder() {
        return List.builder()
            .clientId(Property.ofValue("client_id"))
            .clientSecret(Property.ofValue("client_secret"))
            .refreshToken(Property.ofValue("refresh_token"))
            .realmId(Property.ofValue("12345"))
            .baseUrl(Property.ofValue("http://localhost:" + wireMockServer.port()))
            .authUrl(Property.ofValue("http://localhost:" + wireMockServer.port() + "/oauth2/v1/tokens/bearer"))
            .fetchType(Property.ofValue(FetchType.FETCH));
    }

    private void stubQuery(String expectedQuery) {
        stubFor(get(urlPathEqualTo("/v3/company/12345/query"))
            .withQueryParam("query", equalTo(expectedQuery + " STARTPOSITION 1 MAXRESULTS 1000"))
            .willReturn(aResponse()
                .withHeader("Content-Type", "application/json")
                .withBody("{\"QueryResponse\": {\"Invoice\": [{\"Id\": \"1\"}]}}")));
    }

    @Test
    void allTypedFiltersAreCombinedWithWhere() throws Exception {
        stubQuery("select * from Invoice where Terms = '1' AND MetaData.LastUpdatedTime >= '2026-01-01T00:00:00Z'"
            + " AND CustomerRef = '58' AND Balance > '0' AND DueDate < '2026-03-01' AND DueDate > '2026-02-01'"
            + " AND TxnDate >= '2026-01-15' AND TxnDate <= '2026-01-31' AND Balance >= '100.50'");

        var run = builder()
            .where(Property.ofValue("Terms = '1'"))
            .updatedSince(Property.ofValue(Instant.parse("2026-01-01T00:00:00.789Z")))
            .customerId(Property.ofValue("58"))
            .status(Property.ofValue("open"))
            .dueBefore(Property.ofValue(LocalDate.parse("2026-03-01")))
            .dueAfter(Property.ofValue(LocalDate.parse("2026-02-01")))
            .txnDateFrom(Property.ofValue(LocalDate.parse("2026-01-15")))
            .txnDateTo(Property.ofValue(LocalDate.parse("2026-01-31")))
            .balanceMin(Property.ofValue(new BigDecimal("100.50")))
            .build()
            .run(runContext());

        assertThat(run.getSize(), is(1L));
    }

    @Test
    void paidStatus() throws Exception {
        stubQuery("select * from Invoice where Balance = '0'");

        var run = builder().status(Property.ofValue("PAID")).build().run(runContext());

        assertThat(run.getSize(), is(1L));
    }

    @Test
    void overdueStatusUsesTodayUtc() throws Exception {
        stubQuery("select * from Invoice where Balance > '0' AND DueDate < '" + LocalDate.now(ZoneOffset.UTC) + "'");

        var run = builder().status(Property.ofValue("overdue")).build().run(runContext());

        assertThat(run.getSize(), is(1L));
    }

    @Test
    void statusIsCaseInsensitiveWhenRendered() throws Exception {
        stubQuery("select * from Invoice where Balance = '0'");

        var run = builder().status(Property.ofExpression("{{ 'Paid' }}")).build().run(runContext());

        assertThat(run.getSize(), is(1L));
    }

    @Test
    void unknownStatusIsRejected() {
        org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class,
            () -> builder().status(Property.ofValue("pending")).build().run(runContext()));
    }

    @Test
    void customerIdCannotInjectQuery() throws Exception {
        stubQuery("select * from Invoice where CustomerRef = 'O\\'Brien\\' OR Id > \\'0'");

        var run = builder().customerId(Property.ofValue("O'Brien' OR Id > '0")).build().run(runContext());

        assertThat(run.getSize(), is(1L));
    }

    @Test
    void customerIdBackslashCannotEscapeTheQuote() throws Exception {
        // a trailing backslash would otherwise turn the closing quote into an escaped one
        stubQuery("select * from Invoice where CustomerRef = 'a\\\\'");

        var run = builder().customerId(Property.ofValue("a\\")).build().run(runContext());

        assertThat(run.getSize(), is(1L));
    }

    @Test
    void quoteEscapesBackslashThenApostrophe() {
        assertThat(List.quote("a'b\\c"), is("'a\\'b\\\\c'"));
    }
}
