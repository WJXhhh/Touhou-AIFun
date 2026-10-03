package com.wjx.touhou_aifun.maid.gui;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

public final class MaidGuiAdapters {
    private static final List<MaidGuiAdapter> GUI = new CopyOnWriteArrayList<>();
    private static final List<MaidGuiProcessAdapter> PROCESS = new CopyOnWriteArrayList<>(List.of(new FurnaceProcessAdapter()));
    private MaidGuiAdapters() { }
    public static void register(MaidGuiAdapter adapter) { GUI.add(0, adapter); }
    public static void registerProcess(MaidGuiProcessAdapter adapter) { PROCESS.add(0, adapter); }
    public static List<MaidGuiAdapter> gui() { return List.copyOf(GUI); }
    public static boolean processSupported(net.minecraft.world.inventory.AbstractContainerMenu menu) {
        return PROCESS.stream().anyMatch(adapter -> adapter.supports(menu));
    }
    public static GuiProcessState observe(MaidGuiSession session, String item, int remaining) {
        for (MaidGuiProcessAdapter adapter : PROCESS) {
            if (adapter.supports(session.menu())) return adapter.observe(session, item, remaining);
        }
        return GuiProcessState.unknown();
    }
}
