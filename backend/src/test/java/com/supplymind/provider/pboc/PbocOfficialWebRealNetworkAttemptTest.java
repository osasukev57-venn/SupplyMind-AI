package com.supplymind.provider.pboc;

import com.supplymind.SupplyMindApplication;
import com.supplymind.foundation.storage.DataRoot;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;

import java.net.URI;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Opt-in evidence only: this invokes the normal JDK HTTPS transport once against the public PBOC list.
 * It is never a fixture substitute.  An EXTERNAL_ACCESS_BLOCKED outcome documents an attempted connection;
 * it does not make D1-T04 or AT-SRC-002 pass.
 */
class PbocOfficialWebRealNetworkAttemptTest {

    @Test
    @EnabledIfSystemProperty(named = "pboc.real-network", matches = "true")
    void performsOneRealJavaHttpsAttemptOrRecordsAnExternalAccessBlock() {
        String rootValue = System.getProperty("pboc.integration.data-root");
        assertNotNull(rootValue, "explicit one dataRoot is required for a real collection attempt");
        Path configuredRoot = Path.of(rootValue).toAbsolutePath().normalize();
        assertTrue(Path.of(rootValue).isAbsolute(), "the real collection dataRoot must be absolute");

        try (ConfigurableApplicationContext context = new SpringApplicationBuilder(SupplyMindApplication.class)
                .web(WebApplicationType.NONE)
                .run("--supplymind.data-root=" + configuredRoot,
                        "--spring.main.web-application-type=none")) {
            assertEquals(configuredRoot, context.getBean(DataRoot.class).path());
            PbocOfficialWebDataProvider provider = context.getBean(PbocOfficialWebDataProvider.class);

            try {
                PbocCollectionResult result = provider.collectLatestAnnouncement();
                assertEquals("www.pbc.gov.cn", result.listUrl().getHost());
                assertEquals("www.pbc.gov.cn", result.detailUrl().getHost());
                assertNotNull(result.usdRaw());
                assertNotNull(result.eurRaw());
                System.out.printf("D1T04_REAL_JAVA_HTTPS outcome=SUCCESS businessDate=%s list=%s detail=%s%n",
                        result.businessDate(), sanitize(result.listUrl()), sanitize(result.detailUrl()));
            } catch (PbocCollectionException exception) {
                assertEquals(PbocCollectionFailureKind.EXTERNAL_ACCESS_BLOCKED, exception.failureKind(),
                        "a non-blocked real response failure must be investigated rather than accepted as network evidence");
                System.out.printf("D1T04_REAL_JAVA_HTTPS outcome=EXTERNAL_ACCESS_BLOCKED stage=%s url=%s httpStatus=%s cause=%s%n",
                        exception.stage(), sanitize(exception.uri()), exception.httpStatus(),
                        exception.getCause() == null ? "NONE" : exception.getCause().getClass().getSimpleName());
            }
        }
    }

    private static String sanitize(URI uri) {
        if (uri == null || uri.getScheme() == null || uri.getHost() == null) {
            return "unavailable";
        }
        String path = uri.getRawPath();
        return uri.getScheme() + "://" + uri.getHost() + (path == null || path.isBlank() ? "/" : path);
    }
}
