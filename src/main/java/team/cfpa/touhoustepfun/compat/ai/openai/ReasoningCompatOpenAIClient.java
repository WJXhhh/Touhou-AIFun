package team.cfpa.touhoustepfun.compat.ai.openai;

import com.github.tartaricacid.touhoulittlemaid.TouhouLittleMaid;
import com.github.tartaricacid.touhoulittlemaid.ai.agent.tool.ITool;
import com.github.tartaricacid.touhoulittlemaid.ai.agent.tool.ToolRegister;
import com.github.tartaricacid.touhoulittlemaid.ai.manager.entity.LLMCallback;
import com.github.tartaricacid.touhoulittlemaid.ai.service.ErrorCode;
import com.github.tartaricacid.touhoulittlemaid.ai.service.function.schema.FunctionTool;
import com.github.tartaricacid.touhoulittlemaid.ai.service.function.schema.parameter.ObjectParameter;
import com.github.tartaricacid.touhoulittlemaid.ai.service.function.schema.parameter.Parameter;
import com.github.tartaricacid.touhoulittlemaid.ai.service.llm.DefaultLLMSite;
import com.github.tartaricacid.touhoulittlemaid.ai.service.llm.LLMMessage;
import com.github.tartaricacid.touhoulittlemaid.ai.service.llm.Role;
import com.github.tartaricacid.touhoulittlemaid.ai.service.llm.openai.LLMOpenAIClient;
import com.github.tartaricacid.touhoulittlemaid.ai.service.llm.openai.LLMOpenAISite;
import com.github.tartaricacid.touhoulittlemaid.ai.service.llm.openai.request.ResponseFormat;
import com.github.tartaricacid.touhoulittlemaid.ai.service.llm.openai.response.Message;
import com.github.tartaricacid.touhoulittlemaid.ai.service.llm.openai.response.Usage;
import com.github.tartaricacid.touhoulittlemaid.capability.ChatTokensCapabilityProvider;
import com.github.tartaricacid.touhoulittlemaid.config.subconfig.AIConfig;
import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import com.google.common.net.HttpHeaders;
import com.google.common.net.MediaType;
import net.minecraft.server.level.ServerPlayer;
import org.apache.commons.lang3.StringUtils;
import team.cfpa.touhoustepfun.compat.ai.openai.request.ReasoningChatCompletion;
import team.cfpa.touhoustepfun.compat.ai.openai.response.ReasoningOpenAIChatCompletionResponse;
import team.cfpa.touhoustepfun.compat.ai.openai.response.ReasoningOpenAIMessage;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.concurrent.TimeUnit;

public class ReasoningCompatOpenAIClient extends LLMOpenAIClient {
    public ReasoningCompatOpenAIClient(HttpClient httpClient, LLMOpenAISite site) {
        super(httpClient, site);
    }

    @Override
    public void chat(LLMCallback callback) {
        EntityMaid maid = callback.getMaid();
        URI url = URI.create(this.site.url());
        String apiKey = this.site.secretKey();
        String model = maid.getAiChatManager().getLLMModel();
        boolean isReasoningModel = this.site.isReasoningModel(model);

        ReasoningChatCompletion chatCompletion = ReasoningChatCompletion.create()
                .model(model)
                .setResponseFormat(ResponseFormat.text());
        chatCompletion = this.extraArgs(chatCompletion, model);

        for (LLMMessage message : callback.getMessages()) {
            if (message.role() == Role.USER) {
                chatCompletion.userChat(message.message());
            } else if (message.role() == Role.ASSISTANT) {
                ReasoningContentCodec.DecodedContent decoded = ReasoningContentCodec.decode(message.message());
                String content = ReasoningOpenAIResponseChat.normalizeRepeatedParts(decoded.content());
                if (message.toolCalls() == null || message.toolCalls().isEmpty()) {
                    chatCompletion.assistantChat(content, decoded.reasoningContent());
                } else {
                    chatCompletion.assistantChat(content, decoded.reasoningContent(), message.toolCalls());
                }
            } else if (message.role() == Role.SYSTEM) {
                if (isReasoningModel) {
                    chatCompletion.developerChat(message.message());
                } else {
                    chatCompletion.systemChat(message.message());
                }
            } else if (message.role() == Role.TOOL) {
                chatCompletion.toolChat(message.message(), message.toolCallId());
            }
        }

        if (callback.needAddTools) {
            for (var entry : ToolRegister.getAllTools().entrySet()) {
                String toolId = entry.getKey();
                ITool<?> tool = entry.getValue();
                if (tool == null || !tool.trigger(maid, null)) {
                    continue;
                }

                String summary = tool.summary(maid);
                ObjectParameter root = ObjectParameter.create();
                Parameter parameter = tool.parameters(root, maid);
                chatCompletion.addTool(FunctionTool.create()
                        .setName(toolId)
                        .setDescription(summary)
                        .setParameters(parameter)
                        .build());
            }
        }

        if (this.site.id().equals(DefaultLLMSite.MINIMAX.id())) {
            chatCompletion.mergeSystemMessages();
        }

        HttpRequest.Builder builder = HttpRequest.newBuilder()
                .header(HttpHeaders.CONTENT_TYPE, MediaType.JSON_UTF_8.toString())
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + apiKey)
                .POST(HttpRequest.BodyPublishers.ofString(GSON.toJson(chatCompletion)))
                .timeout(MAX_TIMEOUT)
                .uri(url);

        if (TouhouLittleMaid.DEBUG) {
            TouhouLittleMaid.LOGGER.info(GSON.toJson(chatCompletion));
        }

        this.site.headers().forEach(builder::header);
        HttpRequest httpRequest = builder.build();
        this.httpClient.sendAsync(httpRequest, HttpResponse.BodyHandlers.ofString())
                .orTimeout(MAX_TIMEOUT.toSeconds() + 5, TimeUnit.SECONDS)
                .whenComplete((response, throwable) -> complete(callback, response, throwable, httpRequest));
    }

    protected ReasoningChatCompletion extraArgs(ReasoningChatCompletion chatCompletion, String model) {
        if (this.site.hasThinkingField()) {
            return chatCompletion.disableThinking();
        }
        return chatCompletion;
    }

    private void complete(LLMCallback callback, HttpResponse<String> response,
                          Throwable throwable, HttpRequest request) {
        try {
            this.handle(callback, response, throwable, request);
        } catch (RuntimeException e) {
            TouhouLittleMaid.LOGGER.error("Failed to process LLM response from {}", request.uri(), e);
            callback.onFailure(request, e, ErrorCode.JSON_DECODE_ERROR);
        }
    }

    protected void handle(LLMCallback callback, HttpResponse<String> response, Throwable throwable, HttpRequest request) {
        EntityMaid maid = callback.getMaid();
        if (this.shouldStopChat(maid)) {
            return;
        }

        this.<ReasoningOpenAIChatCompletionResponse>handleResponse(callback, response, throwable, request, chat -> {
            if (TouhouLittleMaid.DEBUG) {
                TouhouLittleMaid.LOGGER.info(GSON.toJson(chat));
            }

            Usage usage = chat.getUsage();
            if (usage != null) {
                int totalTokens = usage.getTotalTokens();
                if (totalTokens > 0 && callback.shouldCacheTokenUsage()) {
                    callback.getMaid().getAiChatManager().setLastChatTokenUsage(totalTokens);
                }
                if (totalTokens > 0 && callback.getMaid().getOwner() instanceof ServerPlayer serverPlayer) {
                    int tokenCount = serverPlayer.getCapability(ChatTokensCapabilityProvider.CHAT_TOKENS_CAP).map(tokens -> {
                        tokens.addCount(totalTokens);
                        return tokens.getCount();
                    }).orElse(0);

                    int tokenLimit = AIConfig.MAX_TOKENS_PER_PLAYER.get();
                    if (tokenCount > tokenLimit) {
                        String message = "Token Limit Exceeded: %d tokens used, limit is %d".formatted(tokenCount, tokenLimit);
                        callback.onFailure(request, new Throwable(message), ErrorCode.CHAT_TOKEN_LIMIT_EXCEEDED);
                        return;
                    }
                }
            }

            ReasoningOpenAIMessage firstChoice = chat.getFirstChoice();
            if (firstChoice == null) {
                String message = "No Choice Found: %s".formatted(response);
                callback.onFailure(request, new Throwable(message), ErrorCode.CHAT_CHOICE_IS_EMPTY);
                return;
            }
            if (firstChoice.hasToolCall()) {
                callback.onFunctionCall(firstChoice, this);
            } else {
                this.onTextCall(callback, firstChoice);
            }
        }, ReasoningOpenAIChatCompletionResponse.class);
    }

    protected void onTextCall(LLMCallback callback, ReasoningOpenAIMessage firstChoice) {
        String content = firstChoice.getVisibleContent();
        if (StringUtils.isBlank(content)) {
            callback.onSuccess(new ReasoningOpenAIResponseChat(StringUtils.EMPTY, firstChoice.getReasoningContent()));
            return;
        }
        callback.onSuccess(new ReasoningOpenAIResponseChat(content, firstChoice.getReasoningContent()));
    }
}
