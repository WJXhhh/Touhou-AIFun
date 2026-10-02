package com.wjx.touhou_aifun.compat.ai.web;

import org.apache.commons.lang3.StringUtils;

import javax.swing.text.MutableAttributeSet;
import javax.swing.text.html.HTML;
import javax.swing.text.html.HTMLEditorKit;
import javax.swing.text.html.parser.ParserDelegator;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.StringReader;
import java.net.IDN;
import java.net.InetAddress;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Flow;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.GZIPInputStream;
import java.util.zip.InflaterInputStream;

/** Direct public-web fetcher used by {@link WebFetchTool}. */
public final class WebFetchRuntime {
    private static final int MAX_REDIRECTS = 5;
    private static final int MAX_BODY_BYTES = 2 * 1024 * 1024;
    private static final int MAX_CONTENT_CHARS = 24_000;
    private static final int MAX_LINKS = 48;
    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(10);
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(30);
    private static final long OVERALL_TIMEOUT_SECONDS = 45;
    private static final Pattern HEADER_CHARSET = Pattern.compile(
            "(?i)(?:^|;)\\s*charset\\s*=\\s*[\\\"']?([^;\\s\\\"']+)");
    private static final Pattern META_CHARSET = Pattern.compile(
            "(?is)<meta[^>]+charset\\s*=\\s*[\\\"']?([^\\s\\\"'/>;]+)");
    private static final Set<String> BLOCK_TAGS = Set.of(
            "address", "article", "aside", "blockquote", "br", "dd", "div", "dl", "dt",
            "figcaption", "figure", "footer", "form", "h1", "h2", "h3", "h4", "h5", "h6",
            "header", "hr", "li", "main", "nav", "ol", "p", "pre", "section", "table", "td",
            "th", "tr", "ul");
    private static final Set<String> HIDDEN_TAGS = Set.of("script", "style", "noscript", "template", "svg");
    private static final HttpClient HTTP_CLIENT = HttpClient.newBuilder()
            .connectTimeout(CONNECT_TIMEOUT)
            .followRedirects(HttpClient.Redirect.NEVER)
            .build();

    private WebFetchRuntime() {
    }

    public static CompletableFuture<WebFetchResult> fetch(String url) {
        FetchOperation operation = new FetchOperation(url);
        operation.start();
        return operation.result;
    }

    /** Syntax-only normalization. DNS/address validation is deliberately performed off-thread. */
    static URI normalizeUri(String value) {
        URI uri;
        try {
            uri = URI.create(StringUtils.trimToEmpty(value)).normalize();
        } catch (RuntimeException e) {
            throw new IllegalArgumentException("Invalid URL", e);
        }
        String scheme = StringUtils.lowerCase(uri.getScheme(), Locale.ROOT);
        if (!"http".equals(scheme) && !"https".equals(scheme)) {
            throw new IllegalArgumentException("Only public http:// and https:// URLs can be fetched");
        }
        if (uri.getUserInfo() != null) {
            throw new IllegalArgumentException("URLs containing credentials are not allowed");
        }
        String host = uri.getHost();
        if (StringUtils.isBlank(host)) {
            throw new IllegalArgumentException("URL must contain a valid hostname");
        }
        int port = uri.getPort();
        if (port < -1 || port == 0) {
            throw new IllegalArgumentException("URL contains an invalid port");
        }

        // Valid DNS hostnames are converted to their ASCII wire representation here, while IPv6
        // literals are preserved as-is.
        String asciiHost = host.indexOf(':') >= 0 ? host : IDN.toASCII(host, IDN.USE_STD3_ASCII_RULES);
        try {
            URI normalized = new URI(scheme, null, asciiHost, port,
                    StringUtils.defaultIfEmpty(uri.getPath(), "/"), uri.getQuery(), null);
            return normalized.normalize();
        } catch (URISyntaxException e) {
            throw new IllegalArgumentException("Invalid URL", e);
        }
    }

    /** Reject every hostname if any of its current DNS answers points outside the public Internet. */
    static void requirePublicAddress(URI uri) {
        String host = uri.getHost();
        String lower = StringUtils.lowerCase(host, Locale.ROOT);
        if ("localhost".equals(lower) || lower.endsWith(".localhost") || lower.endsWith(".local")
                || lower.endsWith(".internal") || lower.endsWith(".home.arpa")) {
            throw new IllegalArgumentException("Local and private network addresses cannot be fetched");
        }
        try {
            InetAddress[] addresses = InetAddress.getAllByName(host);
            if (addresses.length == 0) throw new IllegalArgumentException("Hostname did not resolve");
            boolean literalHost = host.indexOf(':') >= 0 || host.matches("[0-9.]+");
            for (InetAddress address : addresses) {
                // Clash and similar transparent proxies intentionally resolve public domains into
                // RFC 2544's 198.18.0.0/15 fake-IP range. Permit that indirection for a DNS name,
                // but still reject a model-supplied literal benchmark address.
                boolean proxiedPublicName = !literalHost && isBenchmarkAddress(address);
                if (isBlockedAddress(address) && !proxiedPublicName) {
                    throw new IllegalArgumentException("Local and private network addresses cannot be fetched");
                }
            }
        } catch (IOException e) {
            throw new IllegalArgumentException("Could not resolve webpage hostname", e);
        }
    }

    static boolean isBlockedAddress(InetAddress address) {
        if (address.isAnyLocalAddress() || address.isLoopbackAddress() || address.isLinkLocalAddress()
                || address.isSiteLocalAddress() || address.isMulticastAddress()) {
            return true;
        }
        byte[] bytes = address.getAddress();
        if (bytes.length == 4) {
            int a = bytes[0] & 0xff;
            int b = bytes[1] & 0xff;
            // Includes unspecified/"this host", carrier-grade NAT and other non-public ranges that
            // InetAddress does not consistently classify as site-local.
            return a == 0 || a == 10 || a == 127 || a >= 224
                    || (a == 100 && b >= 64 && b <= 127)
                    || (a == 169 && b == 254)
                    || (a == 172 && b >= 16 && b <= 31)
                    || (a == 192 && b == 168)
                    || isBenchmarkAddress(address);
        }
        if (bytes.length == 16) {
            // fc00::/7 unique-local space. IPv4-mapped loopback/private addresses are normally
            // surfaced by the JDK as Inet4Address, but also check the mapped form explicitly.
            if ((bytes[0] & 0xfe) == 0xfc) return true;
            boolean mapped = true;
            for (int i = 0; i < 10; i++) mapped &= bytes[i] == 0;
            mapped &= bytes[10] == (byte) 0xff && bytes[11] == (byte) 0xff;
            if (mapped) {
                byte[] ipv4 = {bytes[12], bytes[13], bytes[14], bytes[15]};
                try {
                    return isBlockedAddress(InetAddress.getByAddress(ipv4));
                } catch (IOException ignored) {
                    return true;
                }
            }
        }
        return false;
    }

    private static boolean isBenchmarkAddress(InetAddress address) {
        byte[] bytes = address.getAddress();
        return bytes.length == 4 && (bytes[0] & 0xff) == 198
                && ((bytes[1] & 0xff) == 18 || (bytes[1] & 0xff) == 19);
    }

    static WebFetchResult parseDocument(URI requestedUri, URI finalUri, String contentType,
                                        String contentEncoding, byte[] encodedBody, boolean transportTruncated) {
        LimitedBody decoded = decodeBody(encodedBody, contentEncoding);
        boolean truncated = transportTruncated || decoded.truncated();
        String mime = mediaType(contentType);
        Charset charset = charset(contentType, decoded.bytes());
        String raw = new String(decoded.bytes(), charset);

        if (isHtml(mime, raw)) {
            ParsedHtml parsed = parseHtml(finalUri, raw);
            String content = cleanAndBound(parsed.content());
            truncated |= codePointLength(parsed.content()) > MAX_CONTENT_CHARS;
            return new WebFetchResult(requestedUri.toASCIIString(), finalUri.toASCIIString(),
                    StringUtils.trimToNull(parsed.title()), StringUtils.trimToNull(mime), content,
                    parsed.links(), truncated);
        }
        if (!isTextual(mime, raw)) {
            throw new IllegalArgumentException("Unsupported webpage content type: "
                    + StringUtils.defaultIfBlank(mime, "unknown/binary"));
        }
        String cleaned = cleanAndBound(raw);
        truncated |= codePointLength(normalizeText(raw)) > MAX_CONTENT_CHARS;
        return new WebFetchResult(requestedUri.toASCIIString(), finalUri.toASCIIString(), null,
                StringUtils.trimToNull(mime), cleaned, List.of(), truncated);
    }

    private static LimitedBody decodeBody(byte[] body, String contentEncoding) {
        String encoding = StringUtils.lowerCase(StringUtils.trimToEmpty(contentEncoding), Locale.ROOT);
        if (encoding.isEmpty() || "identity".equals(encoding)) return new LimitedBody(body, false);
        try {
            InputStream input = new ByteArrayInputStream(body);
            if (encoding.contains("gzip")) input = new GZIPInputStream(input);
            else if (encoding.contains("deflate")) input = new InflaterInputStream(input);
            else throw new IllegalArgumentException("Unsupported webpage content encoding: " + encoding);
            return readLimited(input, MAX_BODY_BYTES);
        } catch (IOException e) {
            throw new IllegalArgumentException("Could not decompress webpage response", e);
        }
    }

    private static LimitedBody readLimited(InputStream input, int limit) throws IOException {
        try (input; ByteArrayOutputStream output = new ByteArrayOutputStream(Math.min(limit, 32 * 1024))) {
            byte[] buffer = new byte[8192];
            while (output.size() <= limit) {
                int read = input.read(buffer, 0, Math.min(buffer.length, limit + 1 - output.size()));
                if (read < 0) break;
                output.write(buffer, 0, read);
            }
            byte[] bytes = output.toByteArray();
            if (bytes.length <= limit) return new LimitedBody(bytes, false);
            byte[] bounded = new byte[limit];
            System.arraycopy(bytes, 0, bounded, 0, limit);
            return new LimitedBody(bounded, true);
        }
    }

    private static ParsedHtml parseHtml(URI baseUri, String html) {
        HtmlTextCallback callback = new HtmlTextCallback(baseUri);
        try {
            new ParserDelegator().parse(new StringReader(html), callback, true);
        } catch (IOException e) {
            throw new IllegalArgumentException("Could not parse webpage HTML", e);
        }
        return callback.result();
    }

    private static String mediaType(String contentType) {
        if (StringUtils.isBlank(contentType)) return "";
        int semicolon = contentType.indexOf(';');
        return contentType.substring(0, semicolon >= 0 ? semicolon : contentType.length())
                .trim().toLowerCase(Locale.ROOT);
    }

    private static Charset charset(String contentType, byte[] bytes) {
        Matcher header = HEADER_CHARSET.matcher(StringUtils.defaultString(contentType));
        if (header.find()) {
            try {
                return Charset.forName(header.group(1));
            } catch (RuntimeException ignored) {
                // Fall through to an HTML meta declaration or UTF-8.
            }
        }
        String prefix = new String(bytes, 0, Math.min(bytes.length, 8192), StandardCharsets.ISO_8859_1);
        Matcher meta = META_CHARSET.matcher(prefix);
        if (meta.find()) {
            try {
                return Charset.forName(meta.group(1));
            } catch (RuntimeException ignored) {
                // Fall through to UTF-8.
            }
        }
        return StandardCharsets.UTF_8;
    }

    private static boolean isHtml(String mime, String raw) {
        if (mime.equals("text/html") || mime.equals("application/xhtml+xml")) return true;
        String trimmed = StringUtils.stripStart(raw, null);
        return mime.isEmpty() && (StringUtils.startsWithIgnoreCase(trimmed, "<!doctype html")
                || StringUtils.startsWithIgnoreCase(trimmed, "<html"));
    }

    private static boolean isTextual(String mime, String raw) {
        if (mime.startsWith("text/") || mime.equals("application/json") || mime.endsWith("+json")
                || mime.equals("application/xml") || mime.endsWith("+xml")) {
            return true;
        }
        if (!mime.isEmpty()) return false;
        int sample = Math.min(raw.length(), 4096);
        for (int i = 0; i < sample; i++) {
            if (raw.charAt(i) == '\0') return false;
        }
        return true;
    }

    private static String cleanAndBound(String value) {
        String normalized = normalizeText(value);
        if (codePointLength(normalized) <= MAX_CONTENT_CHARS) return normalized;
        int end = normalized.offsetByCodePoints(0, MAX_CONTENT_CHARS);
        return normalized.substring(0, end).stripTrailing();
    }

    private static String normalizeText(String value) {
        String normalized = StringUtils.defaultString(value).replace("\r\n", "\n").replace('\r', '\n')
                .replaceAll("[\\p{Cc}&&[^\\n\\t]]", "")
                .replaceAll("[ \\t\\x0B\\f]+", " ")
                .replaceAll(" *\\n *", "\n")
                .replaceAll("\\n{3,}", "\n\n")
                .strip();
        return normalized;
    }

    private static int codePointLength(String value) {
        return value.codePointCount(0, value.length());
    }

    private static boolean isRedirect(int status) {
        return status == 301 || status == 302 || status == 303 || status == 307 || status == 308;
    }

    private static Throwable unwrap(Throwable throwable) {
        Throwable current = throwable;
        while ((current instanceof CompletionException) && current.getCause() != null) {
            current = current.getCause();
        }
        return current;
    }

    private static final class FetchOperation {
        private final String source;
        private final CompletableFuture<WebFetchResult> result = new CompletableFuture<>();
        private final AtomicReference<CompletableFuture<?>> active = new AtomicReference<>();
        private URI requestedUri;

        private FetchOperation(String source) {
            this.source = source;
        }

        private void start() {
            result.orTimeout(OVERALL_TIMEOUT_SECONDS, TimeUnit.SECONDS);
            result.whenComplete((ignored, throwable) -> {
                if (throwable instanceof TimeoutException || result.isCancelled()) cancelActive();
            });
            validateThenRequest(source, 0);
        }

        private void validateThenRequest(String url, int redirects) {
            CompletableFuture<URI> validation = CompletableFuture.supplyAsync(() -> {
                URI uri = normalizeUri(url);
                requirePublicAddress(uri);
                return uri;
            });
            setActive(validation);
            validation.whenComplete((uri, throwable) -> {
                if (result.isDone()) return;
                if (throwable != null) {
                    result.completeExceptionally(unwrap(throwable));
                    return;
                }
                if (requestedUri == null) requestedUri = uri;
                request(uri, redirects);
            });
        }

        private void request(URI uri, int redirects) {
            HttpRequest request = HttpRequest.newBuilder(uri)
                    .timeout(REQUEST_TIMEOUT)
                    .header("Accept", "text/html, text/plain, application/xhtml+xml, application/json;q=0.8, */*;q=0.1")
                    .header("Accept-Encoding", "gzip, deflate")
                    .header("User-Agent", "Touhou-AIFun-WebFetch/1.0")
                    .GET().build();
            CompletableFuture<HttpResponse<LimitedBody>> call = HTTP_CLIENT.sendAsync(request, responseInfo -> {
                int status = responseInfo.statusCode();
                if ((status >= 200 && status < 300)) return new LimitedBodySubscriber(MAX_BODY_BYTES);
                return HttpResponse.BodySubscribers.replacing(new LimitedBody(new byte[0], false));
            });
            setActive(call);
            call.whenComplete((response, throwable) -> {
                if (result.isDone()) return;
                if (throwable != null) {
                    result.completeExceptionally(unwrap(throwable));
                    return;
                }
                int status = response.statusCode();
                if (isRedirect(status)) {
                    if (redirects >= MAX_REDIRECTS) {
                        result.completeExceptionally(new IllegalArgumentException("Too many webpage redirects"));
                        return;
                    }
                    String location = response.headers().firstValue("Location").orElse("");
                    if (StringUtils.isBlank(location)) {
                        result.completeExceptionally(new IllegalArgumentException(
                                "Webpage redirect did not include a Location header"));
                        return;
                    }
                    URI next;
                    try {
                        next = uri.resolve(location);
                    } catch (RuntimeException e) {
                        result.completeExceptionally(new IllegalArgumentException("Invalid webpage redirect URL", e));
                        return;
                    }
                    validateThenRequest(next.toString(), redirects + 1);
                    return;
                }
                if (status < 200 || status >= 300) {
                    result.completeExceptionally(new IllegalArgumentException("Web fetch HTTP " + status));
                    return;
                }

                String type = response.headers().firstValue("Content-Type").orElse("");
                String encoding = response.headers().firstValue("Content-Encoding").orElse("");
                CompletableFuture<WebFetchResult> parsing = CompletableFuture.supplyAsync(() ->
                        parseDocument(requestedUri, uri, type, encoding,
                                response.body().bytes(), response.body().truncated()));
                setActive(parsing);
                parsing.whenComplete((page, parseError) -> {
                    if (result.isDone()) return;
                    if (parseError != null) result.completeExceptionally(unwrap(parseError));
                    else result.complete(page);
                });
            });
        }

        private void setActive(CompletableFuture<?> future) {
            active.set(future);
            if (result.isDone()) future.cancel(true);
        }

        private void cancelActive() {
            CompletableFuture<?> future = active.get();
            if (future != null) future.cancel(true);
        }
    }

    private record LimitedBody(byte[] bytes, boolean truncated) {
    }

    /** Bounds the HTTP body while it is streaming, so a hostile Content-Length cannot exhaust memory. */
    private static final class LimitedBodySubscriber implements HttpResponse.BodySubscriber<LimitedBody> {
        private final int limit;
        private final ByteArrayOutputStream output;
        private final CompletableFuture<LimitedBody> body = new CompletableFuture<>();
        private Flow.Subscription subscription;

        private LimitedBodySubscriber(int limit) {
            this.limit = limit;
            this.output = new ByteArrayOutputStream(Math.min(limit, 32 * 1024));
        }

        @Override
        public CompletionStage<LimitedBody> getBody() {
            return body;
        }

        @Override
        public void onSubscribe(Flow.Subscription subscription) {
            this.subscription = subscription;
            subscription.request(Long.MAX_VALUE);
        }

        @Override
        public void onNext(List<ByteBuffer> buffers) {
            if (body.isDone()) return;
            for (ByteBuffer buffer : buffers) {
                int remaining = limit - output.size();
                int copy = Math.min(remaining, buffer.remaining());
                if (copy > 0) {
                    byte[] chunk = new byte[copy];
                    buffer.get(chunk);
                    output.writeBytes(chunk);
                }
                if (buffer.hasRemaining()) {
                    subscription.cancel();
                    body.complete(new LimitedBody(output.toByteArray(), true));
                    return;
                }
            }
        }

        @Override
        public void onError(Throwable throwable) {
            body.completeExceptionally(throwable);
        }

        @Override
        public void onComplete() {
            body.complete(new LimitedBody(output.toByteArray(), false));
        }
    }

    private record ParsedHtml(String title, String content, List<WebFetchLink> links) {
    }

    private static final class HtmlTextCallback extends HTMLEditorKit.ParserCallback {
        private static final int EXTRACTION_BUFFER_LIMIT = MAX_CONTENT_CHARS * 6;
        private final URI baseUri;
        private final StringBuilder title = new StringBuilder();
        private final StringBuilder content = new StringBuilder();
        private final Map<String, WebFetchLink> links = new LinkedHashMap<>();
        private int hiddenDepth;
        private int titleDepth;
        private String activeLink;
        private final StringBuilder activeLinkText = new StringBuilder();

        private HtmlTextCallback(URI baseUri) {
            this.baseUri = baseUri;
        }

        @Override
        public void handleStartTag(HTML.Tag tag, MutableAttributeSet attributes, int position) {
            String name = tag.toString().toLowerCase(Locale.ROOT);
            if (HIDDEN_TAGS.contains(name)) hiddenDepth++;
            if ("title".equals(name)) titleDepth++;
            if (BLOCK_TAGS.contains(name)) boundary();
            if (tag == HTML.Tag.A && hiddenDepth == 0) {
                Object href = attributes.getAttribute(HTML.Attribute.HREF);
                activeLink = href == null ? null : publicLink(baseUri, href.toString());
                activeLinkText.setLength(0);
            }
        }

        @Override
        public void handleSimpleTag(HTML.Tag tag, MutableAttributeSet attributes, int position) {
            String name = tag.toString().toLowerCase(Locale.ROOT);
            if (BLOCK_TAGS.contains(name)) boundary();
        }

        @Override
        public void handleEndTag(HTML.Tag tag, int position) {
            String name = tag.toString().toLowerCase(Locale.ROOT);
            if (tag == HTML.Tag.A) finishLink();
            if (BLOCK_TAGS.contains(name)) boundary();
            if ("title".equals(name) && titleDepth > 0) titleDepth--;
            if (HIDDEN_TAGS.contains(name) && hiddenDepth > 0) hiddenDepth--;
        }

        @Override
        public void handleText(char[] data, int position) {
            if (hiddenDepth > 0) return;
            String text = new String(data).replaceAll("\\s+", " ").trim();
            if (text.isEmpty()) return;
            if (titleDepth > 0) appendInline(title, text, 512);
            else appendInline(content, text, EXTRACTION_BUFFER_LIMIT);
            if (activeLink != null) appendInline(activeLinkText, text, 512);
        }

        private void finishLink() {
            if (activeLink != null && links.size() < MAX_LINKS) {
                String label = StringUtils.trimToNull(activeLinkText.toString());
                links.putIfAbsent(activeLink, new WebFetchLink(activeLink, label));
            }
            activeLink = null;
            activeLinkText.setLength(0);
        }

        private void boundary() {
            if (hiddenDepth > 0 || content.isEmpty() || content.length() >= EXTRACTION_BUFFER_LIMIT) return;
            int last = content.length() - 1;
            if (content.charAt(last) == ' ') content.setCharAt(last, '\n');
            else if (content.charAt(last) != '\n') content.append('\n');
        }

        private ParsedHtml result() {
            finishLink();
            return new ParsedHtml(title.toString(), normalizeText(content.toString()),
                    new ArrayList<>(links.values()));
        }

        private static void appendInline(StringBuilder target, String text, int limit) {
            if (target.length() >= limit) return;
            if (!target.isEmpty() && !Character.isWhitespace(target.charAt(target.length() - 1))) target.append(' ');
            int remaining = limit - target.length();
            target.append(text, 0, Math.min(text.length(), remaining));
        }

        private static String publicLink(URI baseUri, String raw) {
            try {
                URI link = baseUri.resolve(raw.trim()).normalize();
                String scheme = StringUtils.lowerCase(link.getScheme(), Locale.ROOT);
                if ((!"http".equals(scheme) && !"https".equals(scheme)) || link.getUserInfo() != null
                        || StringUtils.isBlank(link.getHost())) {
                    return null;
                }
                URI clean = new URI(scheme, null, link.getHost(), link.getPort(),
                        StringUtils.defaultIfEmpty(link.getPath(), "/"), link.getQuery(), null);
                return clean.toASCIIString();
            } catch (RuntimeException | URISyntaxException ignored) {
                return null;
            }
        }
    }
}
