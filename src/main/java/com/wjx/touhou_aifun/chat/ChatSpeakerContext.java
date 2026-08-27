package com.wjx.touhou_aifun.chat;

import com.github.tartaricacid.touhoulittlemaid.ai.manager.setting.papi.PapiReplacer;
import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import com.mojang.authlib.GameProfile;
import com.wjx.touhou_aifun.maid.PublicMaidAccess;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;

import javax.annotation.Nullable;
import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * Carries the player who initiated a chat through TLM's optional asynchronous history-summary
 * hop. TLM receives {@code sender} in {@code chat(...)} but drops it before constructing the user
 * message, which makes a public maid unable to distinguish its owner from a guest.
 */
public final class ChatSpeakerContext {
    private static final int MAX_PENDING_PER_MAID = 8;
    private static final long MAX_PENDING_AGE_MILLIS = 5 * 60_000L;
    private static final Map<UUID, ArrayDeque<Pending>> PENDING = new HashMap<>();

    private ChatSpeakerContext() {
    }

    public static synchronized void remember(EntityMaid maid, String message, ServerPlayer sender) {
        UUID maidId = maid.getUUID();
        ArrayDeque<Pending> queue = PENDING.computeIfAbsent(maidId, ignored -> new ArrayDeque<>());
        long now = System.currentTimeMillis();
        while (!queue.isEmpty() && now - queue.peekFirst().createdAtMillis() > MAX_PENDING_AGE_MILLIS) {
            queue.removeFirst();
        }
        while (queue.size() >= MAX_PENDING_PER_MAID) {
            queue.removeFirst();
        }
        queue.addLast(new Pending(message, capture(maid, sender), now));
    }

    @Nullable
    public static synchronized Snapshot take(EntityMaid maid, String message) {
        ArrayDeque<Pending> queue = PENDING.get(maid.getUUID());
        if (queue == null) {
            return null;
        }

        // The same String instance is captured by TLM's history-summary continuation. Prefer
        // identity so two players sending identical text cannot exchange speaker identities.
        Pending match = removeMatching(queue, message, true);
        if (match == null) {
            match = removeMatching(queue, message, false);
        }
        if (queue.isEmpty()) {
            PENDING.remove(maid.getUUID());
        }
        return match == null ? null : match.snapshot();
    }

    private static Pending removeMatching(ArrayDeque<Pending> queue, String message, boolean identityOnly) {
        Iterator<Pending> iterator = queue.iterator();
        while (iterator.hasNext()) {
            Pending pending = iterator.next();
            boolean matches = identityOnly ? pending.message() == message
                    : Objects.equals(pending.message(), message);
            if (matches) {
                iterator.remove();
                return pending;
            }
        }
        return null;
    }

    private static Snapshot capture(EntityMaid maid, ServerPlayer sender) {
        UUID ownerId = maid.getOwnerUUID();
        boolean actualOwner = ownerId != null && ownerId.equals(sender.getUUID());
        String ownerPlayerName = resolveOwnerPlayerName(maid, ownerId);
        return new Snapshot(sender.getUUID(), sender.getGameProfile().getName(), ownerId,
                ownerPlayerName, PapiReplacer.getOwnerName(maid), actualOwner,
                PublicMaidAccess.isPublic(maid));
    }

    private static String resolveOwnerPlayerName(EntityMaid maid, @Nullable UUID ownerId) {
        if (ownerId == null) {
            return "unknown";
        }
        if (maid.getOwner() instanceof ServerPlayer owner) {
            return owner.getGameProfile().getName();
        }
        if (maid.level() instanceof ServerLevel level) {
            return level.getServer().getProfileCache().get(ownerId)
                    .map(GameProfile::getName).orElse("unknown");
        }
        return "unknown";
    }

    /** Adds transient identity facts inside the latest context block; chat history keeps raw text. */
    public static String attach(String messageWithContext, @Nullable Snapshot snapshot) {
        if (snapshot == null || messageWithContext == null) {
            return messageWithContext;
        }
        String facts = snapshot.toPromptFacts();
        int contextEnd = messageWithContext.indexOf("</context>");
        if (contextEnd < 0) {
            return "<context>\n" + facts + "</context>\n" + messageWithContext;
        }
        return messageWithContext.substring(0, contextEnd) + facts
                + messageWithContext.substring(contextEnd);
    }

    public record Snapshot(UUID speakerId, String speakerName, @Nullable UUID ownerId,
                           String ownerPlayerName, String configuredOwnerAddress,
                           boolean actualOwner, boolean publicAccess) {
        public String toPromptFacts() {
            String relation = actualOwner ? "ACTUAL_OWNER" : "TRUSTED_COMPANION_NOT_OWNER";
            String authority = publicAccess
                    ? "Public access gives this speaker normal chat and tool authority."
                    : "This maid is not in public-access mode.";
            String addressRule = actualOwner
                    ? "The configured owner address may be used for this speaker."
                    : "Treat this speaker warmly like close family or a trusted household companion. Use their player name or a natural affectionate address; do not default to calling them a guest. Do not call them master/owner or claim a second master-servant bond.";
            return "\nCurrent speaker player: " + safe(speakerName) + " (" + speakerId + ")\n"
                    + "Actual maid owner player: " + safe(ownerPlayerName) + " ("
                    + (ownerId == null ? "unknown" : ownerId) + ")\n"
                    + "Current speaker relationship: " + relation + "\n"
                    + "Configured owner address/title: " + safe(configuredOwnerAddress) + "\n"
                    + "Authority: " + authority + " Equal operational authority does not imply ownership.\n"
                    + "Addressing: " + addressRule + "\n";
        }

        private static String safe(String value) {
            if (value == null) {
                return "unknown";
            }
            String normalized = value.replace('\r', ' ').replace('\n', ' ')
                    .replace('<', '[').replace('>', ']').trim();
            return normalized.length() <= 128 ? normalized : normalized.substring(0, 128);
        }
    }

    private record Pending(String message, Snapshot snapshot, long createdAtMillis) {
    }
}
