package com.wjx.touhou_aifun.client.voice;

import com.github.tartaricacid.touhoulittlemaid.config.subconfig.AIConfig;
import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import com.wjx.touhou_aifun.chat.freechat.FreeChatDecision;
import com.wjx.touhou_aifun.chat.freechat.FreeChatCapturedFocus;
import com.wjx.touhou_aifun.vision.scan.VisionDirectionMath;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.monster.Enemy;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * A compact, player-perspective scene for free-chat judgement. It is intentionally bounded and
 * descriptive only: entity names, relative directions and visibility flags, never chat logs or
 * absolute coordinates.
 */
@OnlyIn(Dist.CLIENT)
public final class PlayerViewScene {
    private static final double VIEW_COS = Math.cos(Math.toRadians(55.0D));
    private static final double LOOK_COS = Math.cos(Math.toRadians(10.0D));
    private static final int MAX_ENTITIES = 8;
    private static final int MAX_CANDIDATES = 4;

    public record Capture(List<Integer> candidateMaidIds, String summary) {
    }

    private PlayerViewScene() {
    }

    @Nullable
    public static Capture capture(Minecraft minecraft) {
        LocalPlayer player = minecraft.player;
        if (player == null || minecraft.level == null) {
            return null;
        }
        int chatDistance = 12;
        try {
            chatDistance = Math.max(1, Math.min(256, AIConfig.MAID_CAN_CHAT_DISTANCE.get()));
        } catch (RuntimeException ignored) {
        }
        AABB candidateBox = player.getBoundingBox().inflate(chatDistance);
        List<EntityMaid> candidateMaids = new ArrayList<>(minecraft.level.getEntitiesOfClass(
                EntityMaid.class, candidateBox,
                maid -> maid.isAlive() && maid.isOwnedBy(player)));
        if (candidateMaids.isEmpty()) {
            return null;
        }

        AABB sceneBox = player.getBoundingBox().inflate(24.0D);
        List<Entity> visible = new ArrayList<>();
        for (Entity entity : minecraft.level.getEntities(player, sceneBox,
                entity -> entity.isAlive() && entity != player)) {
            if (dot(player, entity) >= VIEW_COS) {
                visible.add(entity);
            }
        }
        Entity lookTarget = lookTarget(minecraft, player, visible);

        candidateMaids.sort(Comparator
                .comparingInt((EntityMaid maid) -> maid == lookTarget ? 0 : 1)
                .thenComparingInt(maid -> inView(player, maid) ? 0 : 1)
                .thenComparingDouble(player::distanceToSqr));
        if (candidateMaids.size() > MAX_CANDIDATES) {
            candidateMaids = new ArrayList<>(candidateMaids.subList(0, MAX_CANDIDATES));
        }
        List<Integer> candidateIds = new ArrayList<>(candidateMaids.size());
        for (EntityMaid maid : candidateMaids) {
            candidateIds.add(maid.getId());
        }

        Set<Entity> ordered = new LinkedHashSet<>();
        if (lookTarget != null) {
            ordered.add(lookTarget);
        }
        for (EntityMaid maid : candidateMaids) {
            ordered.add(maid);
        }
        visible.sort(Comparator
                .comparingInt((Entity entity) -> entity instanceof Player ? 0
                        : entity instanceof Enemy ? 1
                        : entity instanceof EntityMaid ? 2 : 3)
                .thenComparingDouble(player::distanceToSqr));
        for (Entity entity : visible) {
            ordered.add(entity);
            if (ordered.size() >= MAX_ENTITIES) {
                break;
            }
        }
        if (lookTarget != null && ordered.size() < MAX_ENTITIES) {
            ordered.add(lookTarget);
        }

        StringBuilder summary = new StringBuilder(1200);
        summary.append("Player \"").append(FreeChatDecision.sanitize(player.getGameProfile().getName(), 32))
                .append("\" view at capture time.");
        if (lookTarget != null) {
            summary.append(" Look target: ").append(describe(lookTarget, player, candidateMaids)).append('.');
        } else {
            summary.append(" Look target: none.");
        }
        summary.append(" Candidate maids (only these are selectable):");
        for (int i = 0; i < candidateMaids.size(); i++) {
            EntityMaid maid = candidateMaids.get(i);
            summary.append("\n[candidate ").append(i).append("] ")
                    .append(describe(maid, player, candidateMaids));
        }
        summary.append("\nOther entities in the player's view:");
        int others = 0;
        for (Entity entity : ordered) {
            if (candidateMaids.contains(entity)) {
                continue;
            }
            summary.append('\n').append(describe(entity, player, candidateMaids));
            others++;
        }
        if (others == 0) {
            summary.append("\n(none)");
        }
        List<FreeChatCapturedFocus.Focus> focus = candidateMaids.stream()
                .map(maid -> new FreeChatCapturedFocus.Focus(maid.getId(), inView(player, maid),
                        playerLookingAt(player, maid) && player.hasLineOfSight(maid),
                        direction(player, maid))).toList();
        String text = FreeChatCapturedFocus.encode(summary.toString(), focus);
        return new Capture(List.copyOf(candidateIds), text);
    }

    @Nullable
    private static Entity lookTarget(Minecraft minecraft, LocalPlayer player, List<Entity> visible) {
        Entity crosshair = minecraft.crosshairPickEntity;
        if (crosshair != null && crosshair.isAlive() && crosshair != player
                && player.distanceToSqr(crosshair) <= 16.0D * 16.0D) {
            return crosshair;
        }
        Vec3 eye = player.getEyePosition(1.0F);
        Vec3 view = player.getViewVector(1.0F);
        Entity best = null;
        double bestDot = LOOK_COS;
        for (Entity entity : visible) {
            double dot = dot(player, entity);
            if (dot < bestDot || !player.hasLineOfSight(entity)
                    || player.distanceToSqr(entity) > 16.0D * 16.0D) {
                continue;
            }
            if (!VisionDirectionMath.lookHitsBox(eye, view, entity.getBoundingBox(), 16.0D)) {
                continue;
            }
            bestDot = dot;
            best = entity;
        }
        return best;
    }

    private static String describe(Entity entity, LocalPlayer player, List<EntityMaid> candidates) {
        StringBuilder line = new StringBuilder(180);
        line.append(category(entity)).append(" \"")
                .append(FreeChatDecision.sanitize(entity.getName().getString(), 32)).append("\" ")
                .append(String.format(Locale.ROOT, "%.1f", player.distanceTo(entity))).append("m ")
                .append(direction(player, entity));
        if (entity instanceof EntityMaid maid) {
            line.append(", owned=").append(maid.isOwnedBy(player));
            int index = candidates.indexOf(maid);
            if (index >= 0) {
                line.append(", candidate=").append(index);
            }
            // Unambiguous labels: "player_looking_at_her" is the owner's gaze, "maid_looking_at_player"
            // is the maid's own facing. The old shared "looking_at_player" label was easy to misread
            // and made the decider doubt that the owner was addressing her.
            line.append(", player_looking_at_her=").append(playerLookingAt(player, entity));
            line.append(", maid_looking_at_player=").append(lookingAtPlayer(entity, player));
        } else if (entity instanceof LivingEntity) {
            line.append(", player_looking_at=").append(playerLookingAt(player, entity));
            line.append(", entity_looking_at_player=").append(lookingAtPlayer(entity, player));
        } else {
            line.append(", player_looking_at=").append(playerLookingAt(player, entity));
        }
        line.append(", in_view=").append(inView(player, entity))
                .append(", line_of_sight=").append(player.hasLineOfSight(entity));
        return line.toString();
    }

    private static String category(Entity entity) {
        if (entity instanceof EntityMaid) {
            return "maid";
        }
        if (entity instanceof Player) {
            return "player";
        }
        if (entity instanceof Enemy) {
            return "hostile";
        }
        if (entity instanceof LivingEntity) {
            return "animal";
        }
        return "other";
    }

    private static String direction(LocalPlayer player, Entity entity) {
        return VisionDirectionMath.relativeDirection(player.getX(), player.getEyeY(), player.getZ(),
                player.getYRot(), entity.getBoundingBox().getCenter());
    }

    private static boolean inView(LocalPlayer player, Entity entity) {
        return dot(player, entity) >= VIEW_COS;
    }

    private static double dot(LocalPlayer player, Entity entity) {
        Vec3 view = player.getViewVector(1.0F).normalize();
        Vec3 delta = entity.getBoundingBox().getCenter().subtract(player.getEyePosition(1.0F));
        double length = delta.length();
        if (length < 0.01D) {
            return 1.0D;
        }
        return view.dot(delta.scale(1.0D / length));
    }

    private static boolean lookingAtPlayer(Entity entity, LocalPlayer player) {
        Vec3 toPlayer = player.getEyePosition(1.0F).subtract(entity.getEyePosition(1.0F));
        double length = toPlayer.length();
        if (length < 0.01D) {
            return true;
        }
        return entity.getViewVector(1.0F).normalize().dot(toPlayer.scale(1.0D / length)) > 0.45D;
    }

    /** Whether the owner's own view ray actually hits the entity's bounding box. */
    private static boolean playerLookingAt(LocalPlayer player, Entity entity) {
        return VisionDirectionMath.lookHitsBox(player.getEyePosition(1.0F),
                player.getViewVector(1.0F), entity.getBoundingBox(),
                Math.max(1.0D, player.distanceTo(entity) + 2.0D));
    }
}
