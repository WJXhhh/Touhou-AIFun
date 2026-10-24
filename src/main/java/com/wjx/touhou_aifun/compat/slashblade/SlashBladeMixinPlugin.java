package com.wjx.touhou_aifun.compat.slashblade;

import net.minecraftforge.fml.loading.FMLLoader;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.spongepowered.asm.mixin.extensibility.IMixinConfigPlugin;
import org.spongepowered.asm.mixin.extensibility.IMixinInfo;
import java.nio.file.Files;
import java.util.List;
import java.util.Set;

/** Does not resolve a single optional SlashBlade type while the mod is absent. */
public final class SlashBladeMixinPlugin implements IMixinConfigPlugin {
    private boolean installed;
    private boolean legacy;
    private static boolean compatible;

    public static boolean supported() { return compatible; }

    @Override public void onLoad(String mixinPackage) {
        var mods = FMLLoader.getLoadingModList();
        if (mods != null) {
            installed = mods.getMods().stream().anyMatch(m -> m.getModId().equals("slashblade"));
        }
        if (!installed) return;
        try {
            ClassNode state = read("mods.flammpfeil.slashblade.capability.slashblade.ISlashBladeState");
            ClassNode selector = read("mods.flammpfeil.slashblade.util.TargetSelector");
            ClassNode sword = read("mods.flammpfeil.slashblade.entity.EntityAbstractSummonedSword");
            compatible = has(state, "progressCombo", "(Lnet/minecraft/world/entity/LivingEntity;)Lnet/minecraft/resources/ResourceLocation;")
                    && has(state, "getSlashArtsKey", "()Lnet/minecraft/resources/ResourceLocation;")
                    && has(selector, "getTargettableEntitiesWithinAABB", "(Lnet/minecraft/world/level/Level;Lnet/minecraft/world/entity/LivingEntity;Lnet/minecraft/world/phys/AABB;D)Ljava/util/List;")
                    && has(selector, "getTargettableEntitiesWithinAABB", "(Lnet/minecraft/world/level/Level;DLnet/minecraft/world/entity/Entity;)Ljava/util/List;")
                    && has(sword, "doForceHitEntity", "(Lnet/minecraft/world/entity/Entity;)V");
            ClassNode attacks = read("mods.flammpfeil.slashblade.util.AttackManager");
            // Detect the actual legacy damage branch, never infer it from a version string or branding.
            for (var method : attacks.methods) if (method.name.equals("doMeleeAttack") && method.desc.endsWith("ZZF)V")) {
                for (var instruction : method.instructions) if (instruction instanceof MethodInsnNode call
                        && call.owner.equals(attacks.name) && call.name.equals("doAttackWith")) legacy = true;
            }
        } catch (Exception | LinkageError error) {
            compatible = false;
            org.apache.logging.log4j.LogManager.getLogger("TouhouAIFun/SlashBlade").warn("Cannot inspect optional sword interfaces", error);
        }
        if (!compatible) org.apache.logging.log4j.LogManager.getLogger("TouhouAIFun/SlashBlade")
                .warn("Native SlashBlade interfaces changed; maid sword AI is disabled for this installation");
    }
    private static ClassNode read(String name) throws java.io.IOException {
        // Mod jars are discovered here, but are not yet visible to Mixin's class provider.
        var file = FMLLoader.getLoadingModList().getModFileById("slashblade").getFile();
        try (var stream = Files.newInputStream(file.findResource(name.replace('.', '/') + ".class"))) {
            ClassNode node = new ClassNode();
            new ClassReader(stream).accept(node, ClassReader.SKIP_DEBUG);
            return node;
        }
    }
    private static boolean has(ClassNode node, String name, String descriptor) {
        return node.methods.stream().anyMatch(m -> m.name.equals(name) && m.desc.equals(descriptor));
    }
    @Override public boolean shouldApplyMixin(String target, String mixin) {
        if (!mixin.contains(".mixin.slashblade.")) return true;
        return installed && compatible && (!mixin.endsWith("SlashBladeLegacyDamageMixin") || legacy);
    }
    @Override public String getRefMapperConfig() { return null; }
    @Override public void acceptTargets(Set<String> mine, Set<String> others) {}
    @Override public List<String> getMixins() { return null; }
    @Override public void preApply(String target, ClassNode node, String mixin, IMixinInfo info) {}
    @Override public void postApply(String target, ClassNode node, String mixin, IMixinInfo info) {}
}
