package com.wjx.touhou_aifun.chat.agent;

import com.google.gson.Gson;
import net.minecraft.nbt.*;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.saveddata.SavedData;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** Separate versioned world data; never changes TLM memory or restores executable GUI state. */
public final class AgentArchiveData extends SavedData {
    private static final Gson GSON = new Gson();
    private static final int MAX_TASKS = 64;
    private static final int MAX_ARCHIVE_BYTES = 8 * 1024 * 1024;
    private final Map<String, AgentTaskArchive> archives = new LinkedHashMap<>();
    public static AgentArchiveData get(ServerLevel level) {
        return level.getServer().overworld().getDataStorage().computeIfAbsent(
                AgentArchiveData::load, AgentArchiveData::new, "touhou_aifun_agent_archive_v1");
    }
    public AgentTaskArchive task(UUID maid, String taskId) {
        AgentTaskArchive existing = archives.get(taskId);
        if (existing != null) {
            if (!maid.toString().equals(existing.maidId)) throw new IllegalArgumentException("task_archive_owner_mismatch");
            return existing;
        }
        if (archives.size() >= MAX_TASKS) {
            var oldest = archives.values().stream().filter(a -> a.terminal)
                    .min(Comparator.comparingLong(a -> a.updatedTick));
            if (oldest.isEmpty()) throw new IllegalStateException("world_task_archive_full");
            archives.remove(oldest.get().taskId);
        }
        AgentTaskArchive result = new AgentTaskArchive(maid.toString(), taskId);
        archives.put(taskId, result); setDirty(); return result;
    }
    public void terminal(UUID maid,String id,String status,long tick) {
        AgentTaskArchive archive=archives.get(id);
        if(archive!=null && archive.maidId.equals(maid.toString()) && !archive.terminal) {
            archive.terminal=true; archive.updatedTick=tick;
            if(archive.canRecord()) archive.append(tick,0,"state",null,null,null,null,status);
            setDirty();
        }
    }
    public String read(UUID maid,String taskId,String ref,int offset) {
        for(AgentTaskArchive archive:archives.values()) {
            if(!archive.maidId.equals(maid.toString()) || taskId!=null && !taskId.equals(archive.taskId)) continue;
            if(ref.equals("task_trace") && taskId!=null) return TaskResultStore.page(GSON.toJson(archive.records),offset);
            if(archive.results!=null && ref.startsWith("result_"+archive.results.prefix()+"_")) return new TaskResultStore(archive.results).read(ref,offset);
        }
        return "result_expired: reference is unknown or its task archive has been retired; actions must not be replayed automatically.";
    }
    public static AgentArchiveData load(CompoundTag tag) {
        if (tag.getInt("version") != 1) throw new IllegalArgumentException("unsupported_agent_archive");
        AgentArchiveData data = new AgentArchiveData();
        for (Tag entry : tag.getList("tasks", Tag.TAG_COMPOUND)) {
            byte[] bytes = ((CompoundTag) entry).getByteArray("data");
            if (bytes.length > MAX_ARCHIVE_BYTES) throw new IllegalArgumentException("task_archive_too_large");
            AgentTaskArchive archive = GSON.fromJson(new String(bytes, StandardCharsets.UTF_8), AgentTaskArchive.class);
            if (archive == null || archive.version != 1 || archive.records == null || archive.records.size() > 8192
                    || archive.taskId == null || archive.maidId == null) throw new IllegalArgumentException("invalid_task_archive");
            if (archive.results != null) new TaskResultStore(archive.results); // validate bounded input
            archive.validateSize();
            data.archives.put(archive.taskId, archive);
            if (data.archives.size() > MAX_TASKS) throw new IllegalArgumentException("world_task_archive_full");
        }
        return data;
    }
    @Override public CompoundTag save(CompoundTag tag) {
        tag.putInt("version", 1); ListTag tasks = new ListTag();
        for (AgentTaskArchive archive : archives.values()) {
            CompoundTag row = new CompoundTag();
            byte[] bytes = GSON.toJson(archive).getBytes(StandardCharsets.UTF_8);
            if (bytes.length > MAX_ARCHIVE_BYTES) throw new IllegalStateException("task_archive_too_large");
            row.putByteArray("data", bytes); tasks.add(row);
        }
        tag.put("tasks", tasks); return tag;
    }
}
