package com.wjx.touhou_aifun.maid.management;

import com.github.tartaricacid.touhoulittlemaid.ai.manager.entity.MaidAIChatSerializable;
import com.github.tartaricacid.touhoulittlemaid.ai.manager.site.AvailableSites;
import com.github.tartaricacid.touhoulittlemaid.ai.service.SupportModelSelect;
import com.github.tartaricacid.touhoulittlemaid.ai.service.llm.LLMSite;
import com.github.tartaricacid.touhoulittlemaid.ai.service.tts.TTSSite;
import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import com.github.tartaricacid.touhoulittlemaid.world.data.MaidInfo;
import com.github.tartaricacid.touhoulittlemaid.world.data.MaidWorldData;
import com.wjx.touhou_aifun.maid.PublicMaidAccess;
import com.wjx.touhou_aifun.maid.PublicMaidData;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.Entity;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

public final class MaidManagementService {
    private static final int RECALL_COOLDOWN_TICKS = 20;
    private static final Map<UUID, Long> LAST_RECALL = new LinkedHashMap<>();

    private MaidManagementService() {
    }

    public static List<MaidManagementEntry> list(ServerPlayer player) {
        MinecraftServer server = player.server;
        UUID owner = player.getUUID();
        MaidManagementData directory = MaidManagementData.get(server);
        Map<UUID, MaidManagementEntry> result = new LinkedHashMap<>();

        for (ServerLevel level : server.getAllLevels()) {
            List<? extends EntityMaid> loaded = level.getEntities(EntityMaid.TYPE,
                    maid -> maid.isAlive() && owner.equals(maid.getOwnerUUID()));
            for (EntityMaid maid : loaded) {
                directory.snapshot(maid);
                result.put(maid.getUUID(), fromLoaded(player, maid));
            }
        }

        for (MaidDirectoryRecord record : directory.getAll(owner)) {
            result.putIfAbsent(record.maidId(), fromRecord(player, record));
        }

        MaidWorldData worldData = MaidWorldData.get(player.level());
        List<MaidInfo> unloaded = worldData == null ? null : worldData.getPlayerMaidInfos(player);
        if (unloaded != null) {
            for (MaidInfo info : unloaded) {
                result.computeIfAbsent(info.getEntityId(), ignored -> fromRecord(player, fallback(owner, info)));
            }
        }

        List<MaidManagementEntry> entries = new ArrayList<>(result.values());
        entries.sort(Comparator.comparingInt((MaidManagementEntry entry) -> entry.state().ordinal())
                .thenComparing(entry -> entry.name().getString().toLowerCase(Locale.ROOT))
                .thenComparing(MaidManagementEntry::maidId));
        return entries;
    }

    public static String saveConfig(ServerPlayer player, UUID maidId, MaidAIConfigSnapshot requested,
                                    boolean publicMaid, boolean friendlyFireAllowed, int requestedMask) {
        int mask = requestedMask & MaidAIConfigSnapshot.ALL;
        if (mask == 0) {
            return "no_changes";
        }
        MaidAIConfigSnapshot config = sanitize(requested);
        EntityMaid loaded = findLoaded(player.server, maidId);
        if (loaded != null) {
            if (!PublicMaidAccess.isActualOwner(loaded, player) || !loaded.isAlive()) {
                return "not_owner";
            }
            config.applyTo(loaded.getAiChatManager(), mask);
            PublicMaidData access = PublicMaidAccess.data(loaded);
            if ((mask & MaidAIConfigSnapshot.PUBLIC_MAID) != 0) {
                access.touhouAIFun$setPublicMaid(publicMaid);
            }
            if ((mask & MaidAIConfigSnapshot.FRIENDLY_FIRE) != 0) {
                access.touhouAIFun$setFriendlyFireAllowed(friendlyFireAllowed);
            }
            MaidManagementData.get(player.server).snapshot(loaded);
            return "config_saved";
        }

        MaidDirectoryRecord record = findRecord(player, maidId);
        if (record == null || !player.getUUID().equals(record.ownerId())) {
            return "not_found";
        }
        MaidManagementData.get(player.server).storePending(player.getUUID(), maidId, record, config,
                publicMaid, friendlyFireAllowed, mask);
        return "config_queued";
    }

    public static String recall(ServerPlayer player, UUID maidId) {
        EntityMaid maid = findLoaded(player.server, maidId);
        if (maid == null) {
            return findRecord(player, maidId) == null ? "not_found" : "unloaded";
        }
        if (!maid.isAlive() || !PublicMaidAccess.isActualOwner(maid, player)) {
            return "not_owner";
        }
        if (maid.level() != player.level()) {
            return "other_dimension";
        }

        long now = player.serverLevel().getGameTime();
        long last = LAST_RECALL.getOrDefault(player.getUUID(), Long.MIN_VALUE / 2);
        if (now - last < RECALL_COOLDOWN_TICKS) {
            return "cooldown";
        }
        LAST_RECALL.put(player.getUUID(), now);

        maid.setHomeModeEnable(false);
        if (maid.isPassenger()) {
            maid.stopRiding();
        }
        maid.addEffect(new MobEffectInstance(MobEffects.GLOWING, 200, 1, true, false));
        maid.teleportTo(player.getX() + player.getRandom().nextInt(3) - 1, player.getY(),
                player.getZ() + player.getRandom().nextInt(3) - 1);
        maid.fallDistance = 0;
        MaidManagementData.get(player.server).snapshot(maid);
        return "recalled";
    }

    public static String copyConfigToAll(ServerPlayer player, UUID sourceMaidId) {
        MaidAIConfigSnapshot sourceConfig;
        EntityMaid loadedSource = findLoaded(player.server, sourceMaidId);
        if (loadedSource != null) {
            if (!loadedSource.isAlive() || !PublicMaidAccess.isActualOwner(loadedSource, player)) {
                return "not_owner";
            }
            sourceConfig = MaidAIConfigSnapshot.from(loadedSource.getAiChatManager());
        } else {
            MaidDirectoryRecord sourceRecord = findRecord(player, sourceMaidId);
            if (sourceRecord == null || !player.getUUID().equals(sourceRecord.ownerId())) {
                return "not_found";
            }
            if (!sourceRecord.configKnown()) {
                return "source_unknown";
            }
            sourceConfig = sourceRecord.config();
        }

        int configMask = MaidAIConfigSnapshot.LLM | MaidAIConfigSnapshot.TTS
                | MaidAIConfigSnapshot.TTS_LANGUAGE | MaidAIConfigSnapshot.CHAT_LANGUAGE
                | MaidAIConfigSnapshot.OWNER_NAME | MaidAIConfigSnapshot.CUSTOM_SETTING;
        int changed = 0;
        for (MaidManagementEntry target : list(player)) {
            if (target.maidId().equals(sourceMaidId)) {
                continue;
            }
            String result = saveConfig(player, target.maidId(), sourceConfig, target.publicMaid(),
                    target.friendlyFireAllowed(), configMask);
            if ("config_saved".equals(result) || "config_queued".equals(result)) {
                changed++;
            }
        }
        return changed == 0 ? "batch_empty" : "batch_saved";
    }

    @Nullable
    public static EntityMaid findLoaded(MinecraftServer server, UUID maidId) {
        for (ServerLevel level : server.getAllLevels()) {
            Entity entity = level.getEntity(maidId);
            if (entity instanceof EntityMaid maid) {
                return maid;
            }
        }
        return null;
    }

    @Nullable
    private static MaidDirectoryRecord findRecord(ServerPlayer player, UUID maidId) {
        MaidDirectoryRecord record = MaidManagementData.get(player.server).get(player.getUUID(), maidId);
        if (record != null) {
            return record;
        }
        MaidWorldData worldData = MaidWorldData.get(player.level());
        List<MaidInfo> infos = worldData == null ? null : worldData.getPlayerMaidInfos(player);
        if (infos == null) {
            return null;
        }
        return infos.stream().filter(info -> maidId.equals(info.getEntityId())).findFirst()
                .map(info -> fallback(player.getUUID(), info)).orElse(null);
    }

    private static MaidDirectoryRecord fallback(UUID owner, MaidInfo info) {
        return new MaidDirectoryRecord(owner, info.getEntityId(), Component.Serializer.toJson(info.getName()),
                info.getDimension(), info.getChunkPos(), info.getTimestamp(), "", 0, 0, "", false,
                false, true, false, false, 0, MaidAIConfigSnapshot.EMPTY);
    }

    private static MaidManagementEntry fromLoaded(ServerPlayer player, EntityMaid maid) {
        MaidManagementEntry.State state = maid.level() == player.level()
                ? MaidManagementEntry.State.LOADED_HERE
                : MaidManagementEntry.State.LOADED_OTHER_DIMENSION;
        return new MaidManagementEntry(maid.getUUID(), maid.getDisplayName(),
                maid.level().dimension().location().toString(), maid.blockPosition(), System.currentTimeMillis(),
                state, maid.getModelId(), maid.getHealth(), maid.getMaxHealth(),
                maid.getTask().getUid().toString(), maid.isHomeModeEnable(), PublicMaidAccess.isPublic(maid),
                PublicMaidAccess.isFriendlyFireAllowed(maid), true, false,
                MaidAIConfigSnapshot.from(maid.getAiChatManager()));
    }

    private static MaidManagementEntry fromRecord(ServerPlayer player, MaidDirectoryRecord record) {
        return new MaidManagementEntry(record.maidId(), record.displayName(), record.dimension(),
                record.position(), record.lastSeen(), MaidManagementEntry.State.UNLOADED, record.modelId(),
                record.health(), record.maxHealth(), record.taskId(), record.homeMode(), record.publicMaid(),
                record.friendlyFireAllowed(), record.configKnown(), record.configPending(), record.config());
    }

    private static MaidAIConfigSnapshot sanitize(MaidAIConfigSnapshot source) {
        String llmSite = validLlmSite(source.llmSite());
        String llmModel = validModel(AvailableSites.getLLMSite(llmSite), source.llmModel());
        String ttsSite = validTtsSite(source.ttsSite());
        String ttsModel = MaidAIChatSerializable.isNoTTSSite(ttsSite) ? ""
                : validModel(AvailableSites.getTTSSite(ttsSite), source.ttsModel());
        return new MaidAIConfigSnapshot(llmSite, llmModel, ttsSite, ttsModel,
                validLanguage(source.ttsLanguage()), validLanguage(source.chatLanguage()),
                truncate(source.ownerName(), 128), truncate(source.customSetting(), 4096));
    }

    private static String validLlmSite(String siteId) {
        if (siteId == null || siteId.isBlank()) {
            return "";
        }
        LLMSite site = AvailableSites.getLLMSite(siteId);
        return site != null && site.enabled() ? siteId : "";
    }

    private static String validTtsSite(String siteId) {
        if (MaidAIChatSerializable.isNoTTSSite(siteId)) {
            return MaidAIChatSerializable.NO_TTS_SITE;
        }
        if (siteId == null || siteId.isBlank()) {
            return "";
        }
        TTSSite site = AvailableSites.getTTSSite(siteId);
        return site != null && site.enabled() ? siteId : "";
    }

    private static String validModel(Object site, String modelId) {
        if (!(site instanceof SupportModelSelect select) || modelId == null || modelId.isBlank()) {
            return "";
        }
        return select.models().containsKey(modelId) ? modelId : "";
    }

    private static String validLanguage(String value) {
        if (value == null || value.isBlank()) {
            return "";
        }
        return value.matches("[a-z]{2}_[a-z]{2}") ? value : "";
    }

    private static String truncate(String value, int maxLength) {
        if (value == null) {
            return "";
        }
        return value.length() <= maxLength ? value : value.substring(0, maxLength);
    }
}
