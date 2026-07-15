package dev.bookforge.adminapi.steps;

import io.cucumber.spring.CucumberContextConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;

/**
 * Single source of {@link CucumberContextConfiguration} for the Phase-3b admin-portal steps.
 * Brings up the real Spring Boot context on a random port so the JWT filter, the
 * {@code @PreAuthorize} chain, and the {@code AdminController} all participate in the test —
 * the only way to faithfully exercise the 403-with-DENY-audit flow and the 200-with-ALLOW-audit
 * flow end-to-end.
 *
 * <p>The Phase-3a wired-POJO {@link AdminSteps} (StepUpVerifier / params-hash / single-use)
 * does NOT need the Spring context but coexists with it transparently — its scenarios construct
 * an {@code AdminService} directly and call methods, never going through HTTP.
 *
 * <p>{@link AdminWireMock} is started in its {@code @BeforeAll} cucumber hook (before any
 * scenario runs) and pushes the mock mesh base URL into system properties under the keys
 * {@code downstream.entitlements-url} / {@code downstream.billing-url} /
 * {@code downstream.org-url}. Spring's {@code Environment} resolves system properties with
 * higher priority than {@code application.yml}, so the {@code DownstreamProperties} bean is
 * automatically wired against WireMock without any extra configuration here.
 */
@CucumberContextConfiguration
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
public class AdminApiCucumberContext {
}
