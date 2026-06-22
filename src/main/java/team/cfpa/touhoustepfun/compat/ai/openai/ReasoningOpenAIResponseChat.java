package team.cfpa.touhoustepfun.compat.ai.openai;

import com.github.tartaricacid.touhoulittlemaid.ai.manager.response.ResponseChat;
import org.apache.commons.lang3.StringUtils;

import javax.annotation.Nullable;
import java.util.Arrays;

final class ReasoningOpenAIResponseChat extends ResponseChat {
    @Nullable
    private final String reasoningContent;

    ReasoningOpenAIResponseChat(String content, @Nullable String reasoningContent) {
        super(normalizeRepeatedParts(content));
        this.reasoningContent = reasoningContent;
    }

    static String normalizeRepeatedParts(@Nullable String content) {
        String value = StringUtils.defaultString(content).trim();
        String[] parts = Arrays.stream(value.split("---", -1))
                .map(String::trim)
                .toArray(String[]::new);

        for (int period = 1; period <= parts.length / 2; period++) {
            if (parts.length % period != 0 || !isRepeated(parts, period)) {
                continue;
            }
            return String.join("\n---\n", Arrays.copyOf(parts, period));
        }
        return value;
    }

    private static boolean isRepeated(String[] parts, int period) {
        for (int index = period; index < parts.length; index++) {
            if (!parts[index].equals(parts[index % period])) {
                return false;
            }
        }
        return true;
    }

    @Override
    public String toString() {
        return ReasoningContentCodec.encode(super.toString(), reasoningContent);
    }
}
