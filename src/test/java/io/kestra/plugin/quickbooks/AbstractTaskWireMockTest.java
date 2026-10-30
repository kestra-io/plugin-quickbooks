package io.kestra.plugin.quickbooks;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.client.WireMock;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import io.kestra.core.junit.annotations.KestraTest;
import io.kestra.core.runners.RunContext;
import io.kestra.core.runners.RunContextFactory;
import jakarta.inject.Inject;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;

import java.util.Map;

import static com.github.tomakehurst.wiremock.client.WireMock.*;

@KestraTest
public abstract class AbstractTaskWireMockTest {

    @Inject
    protected RunContextFactory runContextFactory;

    protected static WireMockServer wireMockServer;

    // Lets tests in sub-packages reach the package-private seam.
    public static void allowLoopback(boolean allow) {
        AbstractQuickBooksConnection.setAllowLoopbackForTests(allow);
    }

    @BeforeAll
    static void baseSetup() {
        AbstractQuickBooksConnection.setAllowLoopbackForTests(true);
        wireMockServer = new WireMockServer(WireMockConfiguration.options().dynamicPort());
        wireMockServer.start();
        WireMock.configureFor("localhost", wireMockServer.port());
    }

    @AfterAll
    static void baseTeardown() {
        if (wireMockServer != null) {
            wireMockServer.stop();
        }
    }

    @BeforeEach
    void baseReset() {
        wireMockServer.resetAll();
        stubFor(post(urlEqualTo("/oauth2/v1/tokens/bearer"))
            .willReturn(aResponse()
                .withHeader("Content-Type", "application/json")
                .withBody("{\"access_token\": \"dummy_access\", \"refresh_token\": \"dummy_refresh\", \"expires_in\": 3600, \"x_refresh_token_expires_in\": 8726400}")));
    }

    protected RunContext runContext() {
        return runContextFactory.of(Map.of(
            "flow", Map.of(
                "tenantId", "",
                "id", "test-flow",
                "namespace", "io.kestra.plugin.quickbooks.test",
                "revision", 1
            )
        ));
    }
}
