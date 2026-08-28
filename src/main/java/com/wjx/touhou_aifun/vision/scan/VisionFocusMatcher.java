package com.wjx.touhou_aifun.vision.scan;

import java.util.List;
import java.util.Locale;

/** Small deterministic alias table for sorting scan summaries; it never changes visibility. */
final class VisionFocusMatcher {
    private static final List<Rule> RULES = List.of(
            rule("cake", "蛋糕", "cake"),
            rule("snack_cabinet", "零食柜", "零食箱", "零食架", "snack cabinet"),
            rule("chest|barrel|shulker_box", "箱子", "容器", "chest", "container"),
            rule("furnace|smoker|blast_furnace", "熔炉", "炉子", "furnace"),
            rule("crafting_table|smithing_table|stonecutter|loom", "工作台", "合成台", "workstation"),
            rule("creeper", "苦力怕", "爬行者", "creeper"),
            rule("zombie", "僵尸", "zombie"),
            rule("skeleton", "骷髅", "skeleton"),
            rule("player", "玩家", "主人", "player"),
            rule("item", "掉落物", "物品", "item"),
            rule("ore|ancient_debris", "矿", "矿石", "资源", "ore"),
            rule("lava|fire|magma|tnt|cactus|powder_snow", "危险", "岩浆", "火", "炸药", "danger", "hazard"),
            rule("door|trapdoor", "门", "door"),
            rule("ladder|vine", "梯子", "攀爬", "ladder"),
            rule("rail|minecart", "轨道", "矿车", "rail"),
            rule("bed", "床", "bed"),
            rule("portal", "传送门", "portal"),
            rule("spawner", "刷怪笼", "spawner"),
            rule("beacon", "信标", "beacon"),
            rule("enchant", "附魔台", "附魔", "enchant")
    );

    private VisionFocusMatcher() {
    }

    static boolean matches(String focus, String registryId) {
        String normalizedFocus = normalize(focus);
        String normalizedId = normalize(registryId);
        if (normalizedFocus.isBlank() || normalizedId.isBlank()) return false;
        if (normalizedId.contains(normalizedFocus)) return true;
        for (Rule rule : RULES) {
            if (rule.aliases.stream().noneMatch(normalizedFocus::contains)) continue;
            for (String fragment : rule.idFragments) {
                if (normalizedId.contains(fragment)) return true;
            }
        }
        return false;
    }

    private static Rule rule(String fragments, String... aliases) {
        return new Rule(List.of(fragments.split("\\|")), List.of(aliases));
    }

    private static String normalize(String value) {
        return value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
    }

    private record Rule(List<String> idFragments, List<String> aliases) {
    }
}
