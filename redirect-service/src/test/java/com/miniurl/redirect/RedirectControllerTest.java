package com.miniurl.redirect;

import com.miniurl.redirect.config.SecurityConfig;
import com.miniurl.redirect.controller.RedirectController;
import com.miniurl.redirect.service.RedirectService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.reactive.WebFluxTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.reactive.server.WebTestClient;
import reactor.core.publisher.Mono;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@WebFluxTest(RedirectController.class)
@Import(SecurityConfig.class)
@DisplayName("RedirectController Security Tests")
class RedirectControllerTest {

    @Autowired
    private WebTestClient webTestClient;

    @MockBean
    private RedirectService redirectService;

    // -----------------------------------------------------------------------
    // Allowed protocols: http and https
    // -----------------------------------------------------------------------

    @Nested
    @DisplayName("Allowed protocols")
    class AllowedProtocols {

        @Test
        @DisplayName("should redirect http:// URLs with 302")
        void shouldAllowHttp() {
            when(redirectService.resolveUrl("abc123"))
                    .thenReturn(Mono.just("http://example.com"));
            when(redirectService.publishClickEvent(any()))
                    .thenReturn(Mono.empty().then());

            webTestClient.get()
                    .uri("/r/abc123")
                    .exchange()
                    .expectStatus().isFound()
                    .expectHeader().location("http://example.com");
        }

        @Test
        @DisplayName("should redirect https:// URLs with 302")
        void shouldAllowHttps() {
            when(redirectService.resolveUrl("abc123"))
                    .thenReturn(Mono.just("https://example.com/page?q=1"));
            when(redirectService.publishClickEvent(any()))
                    .thenReturn(Mono.empty().then());

            webTestClient.get()
                    .uri("/r/abc123")
                    .exchange()
                    .expectStatus().isFound()
                    .expectHeader().location("https://example.com/page?q=1");
        }

        @Test
        @DisplayName("should redirect HTTP (uppercase) URLs with 302")
        void shouldAllowHttpUppercase() {
            when(redirectService.resolveUrl("abc123"))
                    .thenReturn(Mono.just("HTTP://EXAMPLE.COM"));
            when(redirectService.publishClickEvent(any()))
                    .thenReturn(Mono.empty().then());

            webTestClient.get()
                    .uri("/r/abc123")
                    .exchange()
                    .expectStatus().isFound()
                    .expectHeader().location("HTTP://EXAMPLE.COM");
        }
    }

    // -----------------------------------------------------------------------
    // Blocked protocols: javascript, data, vbscript, file
    // -----------------------------------------------------------------------

    @Nested
    @DisplayName("Blocked dangerous protocols")
    class BlockedProtocols {

        @Test
        @DisplayName("should block javascript: protocol with 400")
        void shouldBlockJavascriptProtocol() {
            when(redirectService.resolveUrl("evil1"))
                    .thenReturn(Mono.just("javascript:alert(document.cookie)"));

            webTestClient.get()
                    .uri("/r/evil1")
                    .exchange()
                    .expectStatus().isBadRequest();

            verify(redirectService, never()).publishClickEvent(any());
        }

        @Test
        @DisplayName("should block data: protocol with 400")
        void shouldBlockDataProtocol() {
            when(redirectService.resolveUrl("evil2"))
                    .thenReturn(Mono.just("data:text/html,<script>alert(1)</script>"));

            webTestClient.get()
                    .uri("/r/evil2")
                    .exchange()
                    .expectStatus().isBadRequest();

            verify(redirectService, never()).publishClickEvent(any());
        }

        @Test
        @DisplayName("should block vbscript: protocol with 400")
        void shouldBlockVbscriptProtocol() {
            when(redirectService.resolveUrl("evil3"))
                    .thenReturn(Mono.just("vbscript:msgbox(1)"));

            webTestClient.get()
                    .uri("/r/evil3")
                    .exchange()
                    .expectStatus().isBadRequest();

            verify(redirectService, never()).publishClickEvent(any());
        }

        @Test
        @DisplayName("should block file: protocol with 400")
        void shouldBlockFileProtocol() {
            when(redirectService.resolveUrl("evil4"))
                    .thenReturn(Mono.just("file:///etc/passwd"));

            webTestClient.get()
                    .uri("/r/evil4")
                    .exchange()
                    .expectStatus().isBadRequest();

            verify(redirectService, never()).publishClickEvent(any());
        }

        @Test
        @DisplayName("should block ftp: protocol with 400")
        void shouldBlockFtpProtocol() {
            when(redirectService.resolveUrl("evil5"))
                    .thenReturn(Mono.just("ftp://evil.com/malware.exe"));

            webTestClient.get()
                    .uri("/r/evil5")
                    .exchange()
                    .expectStatus().isBadRequest();

            verify(redirectService, never()).publishClickEvent(any());
        }

        @Test
        @DisplayName("should block about: protocol with 400")
        void shouldBlockAboutProtocol() {
            when(redirectService.resolveUrl("evil6"))
                    .thenReturn(Mono.just("about:blank"));

            webTestClient.get()
                    .uri("/r/evil6")
                    .exchange()
                    .expectStatus().isBadRequest();

            verify(redirectService, never()).publishClickEvent(any());
        }
    }

    // -----------------------------------------------------------------------
    // Edge cases: null, empty, malformed
    // -----------------------------------------------------------------------

    @Nested
    @DisplayName("Edge cases")
    class EdgeCases {

        @Test
        @DisplayName("should return 404 when service returns empty (null-safe)")
        void shouldReturn404WhenServiceReturnsEmpty() {
            // Reactor does not allow null in Mono.just(); use justOrEmpty
            // which converts null to Mono.empty(), triggering the 404 path
            when(redirectService.resolveUrl("nullcode"))
                    .thenReturn(Mono.justOrEmpty((String) null));

            webTestClient.get()
                    .uri("/r/nullcode")
                    .exchange()
                    .expectStatus().isNotFound();

            verify(redirectService, never()).publishClickEvent(any());
        }

        @Test
        @DisplayName("should block empty URL with 400")
        void shouldBlockEmptyUrl() {
            when(redirectService.resolveUrl("emptycode"))
                    .thenReturn(Mono.just(""));

            webTestClient.get()
                    .uri("/r/emptycode")
                    .exchange()
                    .expectStatus().isBadRequest();

            verify(redirectService, never()).publishClickEvent(any());
        }

        @Test
        @DisplayName("should block malformed URL with 400")
        void shouldBlockMalformedUrl() {
            when(redirectService.resolveUrl("badcode"))
                    .thenReturn(Mono.just("not a valid url at all :::"));

            webTestClient.get()
                    .uri("/r/badcode")
                    .exchange()
                    .expectStatus().isBadRequest();

            verify(redirectService, never()).publishClickEvent(any());
        }

        @Test
        @DisplayName("should block javascript: with mixed case")
        void shouldBlockJavascriptMixedCase() {
            when(redirectService.resolveUrl("evil7"))
                    .thenReturn(Mono.just("JaVaScRiPt:alert(1)"));

            webTestClient.get()
                    .uri("/r/evil7")
                    .exchange()
                    .expectStatus().isBadRequest();

            verify(redirectService, never()).publishClickEvent(any());
        }

        @Test
        @DisplayName("should block javascript: with leading whitespace")
        void shouldBlockJavascriptWithWhitespace() {
            when(redirectService.resolveUrl("evil8"))
                    .thenReturn(Mono.just("  javascript:alert(1)"));

            webTestClient.get()
                    .uri("/r/evil8")
                    .exchange()
                    .expectStatus().isBadRequest();

            verify(redirectService, never()).publishClickEvent(any());
        }
    }

    // -----------------------------------------------------------------------
    // Existing behavior preserved
    // -----------------------------------------------------------------------

    @Nested
    @DisplayName("Existing behavior preserved")
    class ExistingBehavior {

        @Test
        @DisplayName("should return 404 when short code not found")
        void shouldReturn404ForUnknownCode() {
            when(redirectService.resolveUrl("unknown"))
                    .thenReturn(Mono.empty());

            webTestClient.get()
                    .uri("/r/unknown")
                    .exchange()
                    .expectStatus().isNotFound();
        }

        @Test
        @DisplayName("should publish click event for valid redirects")
        void shouldPublishClickEventForValidRedirect() {
            when(redirectService.resolveUrl("abc123"))
                    .thenReturn(Mono.just("https://example.com"));
            when(redirectService.publishClickEvent(any()))
                    .thenReturn(Mono.empty().then());

            webTestClient.get()
                    .uri("/r/abc123")
                    .exchange()
                    .expectStatus().isFound();

            verify(redirectService).publishClickEvent(any());
        }
    }
}
