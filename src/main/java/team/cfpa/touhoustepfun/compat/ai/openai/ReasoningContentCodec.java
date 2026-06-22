package team.cfpa.touhoustepfun.compat.ai.openai;

import org.apache.commons.lang3.StringUtils;

import javax.annotation.Nullable;
import java.nio.charset.StandardCharsets;
import java.util.Base64;

public final class ReasoningContentCodec {
    private static final String PREFIX = "[[TLM_REASONING_CONTENT_BASE64:";
    private static final String SUFFIX = "]]";

    private ReasoningContentCodec() {
    }

    public static String encode(String content, @Nullable String reasoningContent) {
        if (StringUtils.isBlank(reasoningContent)) {
            return StringUtils.defaultString(content);
        }
        String base64 = Base64.getEncoder().encodeToString(reasoningContent.getBytes(StandardCharsets.UTF_8));
        return StringUtils.defaultString(content) + PREFIX + base64 + SUFFIX;
    }

    public static DecodedContent decode(@Nullable String raw) {
        String value = StringUtils.defaultString(raw);
        int prefixIndex = value.lastIndexOf(PREFIX);
        if (prefixIndex < 0 || !value.endsWith(SUFFIX)) {
            return new DecodedContent(value, null);
        }

        int base64Start = prefixIndex + PREFIX.length();
        int base64End = value.length() - SUFFIX.length();
        if (base64Start > base64End) {
            return new DecodedContent(value, null);
        }

        String encoded = value.substring(base64Start, base64End);
        try {
            String reasoning = new String(Base64.getDecoder().decode(encoded), StandardCharsets.UTF_8);
            return new DecodedContent(value.substring(0, prefixIndex), reasoning);
        } catch (IllegalArgumentException e) {
            return new DecodedContent(value, null);
        }
    }

    public record DecodedContent(String content, @Nullable String reasoningContent) {
    }
}
