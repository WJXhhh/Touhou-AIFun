package com.wjx.touhou_aifun.client.gui;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ChatGPTSubscriptionScreenTest {
    @Test void lifecycleDoesNotRequireApiKeyEditorInputs() {
        var screen = new ChatGPTSubscriptionScreen(null);
        // The actual crash happened on the first tick after opening the authorization screen.
        assertDoesNotThrow(screen::tick);
        assertDoesNotThrow(() -> { for (int i = 0; i < 80; i++) screen.tick(); });
        assertDoesNotThrow(() -> screen.mouseScrolled(0, 0, 1));
        assertDoesNotThrow(() -> screen.mouseScrolled(0, 0, -1));
        assertFalse(screen.isPauseScreen());
    }

    @Test void browserOnlyOpensOfficialAuthorizationEndpoint() {
        assertTrue(ChatGPTSubscriptionScreen.isAuthorizationUrl("https://auth.openai.com/api/accounts/authorize?state=test"));
        for (String url : new String[] {"http://auth.openai.com/api/accounts/authorize", "https://auth.openai.com.evil.test/api/accounts/authorize",
                "https://evil.test@auth.openai.com/api/accounts/authorize", "file:///C:/secret", "https://auth.openai.com:444/api/accounts/authorize",
                "https://auth.openai.com/other", "malformed URI"})
            assertFalse(ChatGPTSubscriptionScreen.isAuthorizationUrl(url), url);
    }
}
