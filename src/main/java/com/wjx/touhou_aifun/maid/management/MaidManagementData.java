package com.wjx.touhou_aifun.maid.management;

import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import com.wjx.touhou_aifun.maid.PublicMaidAccess;
import com.wjx.touhou_aifun.maid.PublicMaidData;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraft.world.level.storage.DimensionDataStorage;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

public final class MaidManagementData extends SavedData {
    private static final String IDENTIFIER = "touhou_aifun_maid_management";
    private static final String RECORDS_TAG = "Records";

    private final Map<UUID, Map<UUID, MaidDirectoryRecord>> records = new LinkedHashMap<>();

    public static MaidManagementData get(MinecraftServer server) {
        ServerLevel overworld = server.getLevel(Level.OVERWORLD);
        if (overworld == null) {
            throw new IllegalStateException("Overworld is unavailable");
        }
        DimensionDataStorage storage = overworld.getDataStorage();
        return storage.computeIfAbsent(MaidManagementData::load, MaidManagementData::new, IDENTIFIER);
    }

    public static MaidManagementData load(CompoundTag root) {
        MaidManagementData data = new MaidManagementData();
        ListTag list = root.getList(RECORDS_TAG, Tag.TAG_COMPOUND);
        for (int i = 0; i < list.size(); i++) {
            try {
                MaidDirectoryRecord record = MaidDirectoryRecord.fromTag(list.getCompound(i));
                data.put(record);
            } catch (RuntimeException ignored) {
                // Ignore a single malformed entry rather than losing the whole directory.
            }
        }
        return data;
    }

    @Override
    public CompoundTag save(CompoundTag root) {
        ListTag list = new ListTag();
        records.values().forEach(ownerRecords -> ownerRecords.values()
                .forEach(record -> list.add(record.toTag())));
        root.put(RECORDS_TAG, list);
        return root;
    }

    public void snapshot(EntityMaid maid) {
        if (maid.getOwnerUUID() == null) {
            return;
        }
        put(MaidDirectoryRecord.fromMaid(maid));
        setDirty();
    }

    public boolean applyPending(EntityMaid maid) {
        UUID owner = maid.getOwnerUUID();
        if (owner == null) {
            return false;
        }
        MaidDirectoryRecord record = get(owner, maid.getUUID());
        if (record == null || !record.configPending() || !record.configKnown()) {
            return false;
        }
        record.config().applyTo(maid.getAiChatManager(), record.pendingMask());
        PublicMaidData access = PublicMaidAccess.data(maid);
        if ((record.pendingMask() & MaidAIConfigSnapshot.PUBLIC_MAID) != 0) {
            access.touhouAIFun$setPublicMaid(record.publicMaid());
        }
        if ((record.pendingMask() & MaidAIConfigSnapshot.FRIENDLY_FIRE) != 0) {
            access.touhouAIFun$setFriendlyFireAllowed(record.friendlyFireAllowed());
        }
        snapshot(maid);
        return true;
    }

    public void storePending(UUID owner, UUID maidId, MaidDirectoryRecord fallback,
                             MaidAIConfigSnapshot config, boolean publicMaid,
                             boolean friendlyFireAllowed, int mask) {
        MaidDirectoryRecord current = get(owner, maidId);
        MaidDirectoryRecord base = current != null ? current : fallback;
        if (base == null || !owner.equals(base.ownerId())) {
            return;
        }
        put(base.withPendingConfig(config, publicMaid, friendlyFireAllowed, mask));
        setDirty();
    }

    public MaidDirectoryRecord get(UUID owner, UUID maidId) {
        Map<UUID, MaidDirectoryRecord> ownerRecords = records.get(owner);
        return ownerRecords == null ? null : ownerRecords.get(maidId);
    }

    public Collection<MaidDirectoryRecord> getAll(UUID owner) {
        Map<UUID, MaidDirectoryRecord> ownerRecords = records.get(owner);
        return ownerRecords == null ? java.util.List.of() : java.util.List.copyOf(ownerRecords.values());
    }

    public Collection<MaidDirectoryRecord> getPublic() {
        return records.values().stream()
                .flatMap(ownerRecords -> ownerRecords.values().stream())
                .filter(MaidDirectoryRecord::publicMaid)
                .toList();
    }

    public MaidDirectoryRecord findPublic(UUID maidId) {
        return records.values().stream()
                .map(ownerRecords -> ownerRecords.get(maidId))
                .filter(java.util.Objects::nonNull)
                .filter(MaidDirectoryRecord::publicMaid)
                .findFirst()
                .orElse(null);
    }

    public void remove(EntityMaid maid) {
        UUID owner = maid.getOwnerUUID();
        if (owner == null) {
            return;
        }
        Map<UUID, MaidDirectoryRecord> ownerRecords = records.get(owner);
        if (ownerRecords != null && ownerRecords.remove(maid.getUUID()) != null) {
            if (ownerRecords.isEmpty()) {
                records.remove(owner);
            }
            setDirty();
        }
    }

    private void put(MaidDirectoryRecord record) {
        records.computeIfAbsent(record.ownerId(), ignored -> new LinkedHashMap<>())
                .put(record.maidId(), record);
    }
}
