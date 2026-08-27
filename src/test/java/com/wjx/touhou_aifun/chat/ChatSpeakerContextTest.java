package com.wjx.touhou_aifun.chat;

import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ChatSpeakerContextTest {
    private static final UUID OWNER = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final UUID GUEST = UUID.fromString("00000000-0000-0000-0000-000000000002");

    @Test
    void guestIdentityIsTransientlyAttachedInsideContext() {
        ChatSpeakerContext.Snapshot guest = new ChatSpeakerContext.Snapshot(
                GUEST, "Visitor", OWNER, "Alice", "主人", false, true);

        String attached = ChatSpeakerContext.attach("<context>day\n</context>\n你好", guest);

        assertTrue(attached.contains("Current speaker player: Visitor"));
        assertTrue(attached.contains("Current speaker relationship: TRUSTED_COMPANION_NOT_OWNER"));
        assertTrue(attached.contains("close family or a trusted household companion"));
        assertTrue(attached.contains("do not default to calling them a guest"));
        assertTrue(attached.indexOf("Current speaker player") < attached.indexOf("</context>"));
        assertTrue(attached.endsWith("你好"));
    }

    @Test
    void ownerKeepsConfiguredOwnerAddress() {
        ChatSpeakerContext.Snapshot owner = new ChatSpeakerContext.Snapshot(
                OWNER, "Alice", OWNER, "Alice", "姐姐", true, true);

        String attached = ChatSpeakerContext.attach("<context>\n</context>\n跟随我", owner);

        assertTrue(attached.contains("Current speaker relationship: ACTUAL_OWNER"));
        assertTrue(attached.contains("Configured owner address/title: 姐姐"));
        assertTrue(attached.contains("may be used for this speaker"));
        assertFalse(attached.contains("TRUSTED_COMPANION_NOT_OWNER"));
    }
}
