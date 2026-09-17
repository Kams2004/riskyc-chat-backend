package com.riskyc.messaging.controller;

import com.riskyc.common.security.JwtIssuer;
import com.riskyc.messaging.security.RevokedJtiCache;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.io.IOException;
import java.net.InetAddress;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.UnknownHostException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Flow;

/**
 * Fetches a message's linked page server-side and extracts Open Graph /
 * plain HTML metadata for a WhatsApp-style link preview card. This is real
 * server-side-fetch-of-an-arbitrary-URL surface (SSRF), so every step here
 * exists specifically to keep that request from ever reaching this VPS's
 * own internal network:
 *   - only http/https, no redirects followed (a redirect to an internal
 *     address would otherwise be an easy bypass of the checks below)
 *   - the hostname is resolved and EVERY resolved address is checked against
 *     loopback/site-local/link-local/any-local/multicast before connecting
 *     (doesn't fully close a DNS-rebinding race, but blocks the vastly more
 *     common case of a URL that's simply internal/private outright — a
 *     reasonable tradeoff for a link-preview feature, not a security
 *     boundary this app relies on elsewhere)
 *   - strict connect/read timeouts and a hard cap on how many response bytes
 *     are ever read, so a slow-loris or a multi-gigabyte response can't tie
 *     up a request thread or exhaust memory
 */
@RestController
@RequestMapping("/api/link-preview")
public class LinkPreviewController {

    private static final Logger log = LoggerFactory.getLogger(LinkPreviewController.class);
    private static final int MAX_RESPONSE_BYTES = 512 * 1024;
    private static final Duration TIMEOUT = Duration.ofSeconds(5);

    private final JwtIssuer jwtIssuer;
    private final RevokedJtiCache revokedJtiCache;
    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(TIMEOUT)
            .followRedirects(HttpClient.Redirect.NEVER)
            .build();

    public LinkPreviewController(JwtIssuer jwtIssuer, RevokedJtiCache revokedJtiCache) {
        this.jwtIssuer = jwtIssuer;
        this.revokedJtiCache = revokedJtiCache;
    }

    public record LinkPreview(String url, String title, String description, String imageUrl, String siteName) {
    }

    @GetMapping
    public LinkPreview preview(@RequestParam String url,
                                @RequestHeader(value = "Authorization", required = false) String authorization) {
        callerIdFrom(authorization); // any signed-in user may request a preview; just needs to be authenticated
        URI uri = parseAndValidate(url);

        String html;
        try {
            HttpRequest request = HttpRequest.newBuilder(uri)
                    .timeout(TIMEOUT)
                    .header("User-Agent", "Mozilla/5.0 (compatible; RiskyCChatLinkPreview/1.0)")
                    .GET()
                    .build();
            HttpResponse<String> response = httpClient.send(request, info -> new CappedStringBodySubscriber(MAX_RESPONSE_BYTES));
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                return new LinkPreview(url, null, null, null, null);
            }
            String contentType = response.headers().firstValue("Content-Type").orElse("");
            if (!contentType.toLowerCase().contains("html")) {
                return new LinkPreview(url, null, null, null, null);
            }
            html = response.body();
        } catch (IOException | InterruptedException e) {
            log.warn("Link preview fetch failed for {}: {}", uri, e.getMessage());
            return new LinkPreview(url, null, null, null, null);
        }

        Document doc = Jsoup.parse(html, uri.toString());
        String title = firstNonBlank(metaContent(doc, "og:title"), doc.title());
        String description = firstNonBlank(metaContent(doc, "og:description"), metaContent(doc, "description"));
        // abs: resolves a relative og:image (e.g. "/social-card.png") against
        // the page's own base URI — a plain "content" read would otherwise
        // hand the client an unusable relative path.
        String imageUrl = metaAbsContent(doc, "og:image");
        String siteName = metaContent(doc, "og:site_name");

        if (title == null && description == null && imageUrl == null) {
            return new LinkPreview(url, null, null, null, null);
        }
        return new LinkPreview(url, title, description, imageUrl, siteName);
    }

    private static String metaContent(Document doc, String property) {
        String v = doc.select("meta[property=" + property + "]").attr("content");
        if (v == null || v.isBlank()) {
            v = doc.select("meta[name=" + property + "]").attr("content");
        }
        return v == null || v.isBlank() ? null : v;
    }

    private static String metaAbsContent(Document doc, String property) {
        String v = doc.select("meta[property=" + property + "]").attr("abs:content");
        if (v == null || v.isBlank()) {
            v = doc.select("meta[name=" + property + "]").attr("abs:content");
        }
        return v == null || v.isBlank() ? null : v;
    }

    private static String firstNonBlank(String a, String b) {
        return a != null && !a.isBlank() ? a : (b != null && !b.isBlank() ? b : null);
    }

    /**
     * The JDK's HttpResponse.BodySubscribers has no built-in size cap, and
     * ofByteArray() buffers the WHOLE body regardless of what Content-Length
     * claims — a lying or huge server response would otherwise be read into
     * memory in full before we ever get a chance to reject it. This cancels
     * the upstream subscription (closing the connection) the moment the cap
     * is crossed, completing with just what was accumulated so far — plenty
     * for a page's <head> OG tags, which is all this ever needs.
     */
    private static final class CappedStringBodySubscriber implements HttpResponse.BodySubscriber<String> {
        private final int maxBytes;
        private final java.io.ByteArrayOutputStream buffer = new java.io.ByteArrayOutputStream();
        private final CompletableFuture<String> result = new CompletableFuture<>();
        private Flow.Subscription subscription;

        CappedStringBodySubscriber(int maxBytes) {
            this.maxBytes = maxBytes;
        }

        @Override
        public CompletionStage<String> getBody() {
            return result;
        }

        @Override
        public void onSubscribe(Flow.Subscription subscription) {
            this.subscription = subscription;
            subscription.request(Long.MAX_VALUE);
        }

        @Override
        public void onNext(List<ByteBuffer> item) {
            for (ByteBuffer buf : item) {
                byte[] chunk = new byte[buf.remaining()];
                buf.get(chunk);
                buffer.writeBytes(chunk);
            }
            if (buffer.size() >= maxBytes) {
                subscription.cancel();
                result.complete(buffer.toString(StandardCharsets.UTF_8));
            }
        }

        @Override
        public void onError(Throwable throwable) {
            result.completeExceptionally(throwable);
        }

        @Override
        public void onComplete() {
            result.complete(buffer.toString(StandardCharsets.UTF_8));
        }
    }

    private URI parseAndValidate(String url) {
        URI uri;
        try {
            uri = new URI(url);
        } catch (URISyntaxException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Malformed URL");
        }
        String scheme = uri.getScheme();
        if (scheme == null || !(scheme.equalsIgnoreCase("http") || scheme.equalsIgnoreCase("https"))) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Only http/https URLs are supported");
        }
        String host = uri.getHost();
        if (host == null || host.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Malformed URL");
        }
        InetAddress[] resolved;
        try {
            resolved = InetAddress.getAllByName(host);
        } catch (UnknownHostException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Could not resolve host");
        }
        for (InetAddress addr : resolved) {
            if (addr.isLoopbackAddress() || addr.isSiteLocalAddress() || addr.isLinkLocalAddress()
                    || addr.isAnyLocalAddress() || addr.isMulticastAddress()) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "URL resolves to a non-public address");
            }
        }
        return uri;
    }

    private String callerIdFrom(String authorization) {
        if (authorization == null || !authorization.startsWith("Bearer ")) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Missing bearer token");
        }
        try {
            JwtIssuer.JwtClaims claims = jwtIssuer.verifyAndGetClaims(authorization.substring("Bearer ".length()));
            if (revokedJtiCache.isRevoked(claims.jti())) {
                throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Session has been signed out");
            }
            return claims.subject();
        } catch (ResponseStatusException e) {
            throw e;
        } catch (RuntimeException e) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Invalid token");
        }
    }
}
