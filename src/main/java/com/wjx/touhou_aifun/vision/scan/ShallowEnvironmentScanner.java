package com.wjx.touhou_aifun.vision.scan;

import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.BlockTags;
import net.minecraft.tags.TagKey;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.item.FallingBlockEntity;
import net.minecraft.world.entity.item.PrimedTnt;
import net.minecraft.world.entity.decoration.ItemFrame;
import net.minecraft.world.entity.decoration.Painting;
import net.minecraft.world.entity.monster.Enemy;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.projectile.Projectile;
import net.minecraft.world.entity.vehicle.AbstractMinecart;
import net.minecraft.world.entity.vehicle.Boat;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.Property;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.chunk.ChunkStatus;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.registries.ForgeRegistries;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Server-only, conservative environment scanner. It deliberately reads only the current level and
 * never asks the server to load a chunk. The public asynchronous entry point is driven by
 * {@link VisionScanScheduler}; the synchronous method is retained for legacy contexts such as the
 * nearby-entities context and uses the exact same bounded job implementation.
 */
public final class ShallowEnvironmentScanner {
    private static final int RAYS_PER_FACE = 40;
    private static final int VISIBILITY_BUDGET = 16;
    private static final int MAX_SURFACE_GROUPS = 120;
    private static final int MAX_IMPORTANT_BLOCKS = 96;
    private static final int MAX_ENTITIES = 48;
    private static final int MAX_DDA_VISITS = 400_000;
    private static final Map<ScanDirection, Vec3[]> CUBEMAP_DIRECTIONS = precomputeCubemapDirections();

    private static final TagKey<Block> OPAQUE = tag("opaque");
    private static final TagKey<Block> LOW = tag("low_transparency");
    private static final TagKey<Block> MEDIUM = tag("medium_transparency");
    private static final TagKey<Block> HIGH = tag("high_transparency");
    private static final TagKey<Block> THIN = tag("thin");
    private static final TagKey<Block> IMPORTANT = TagKey.create(net.minecraft.core.registries.Registries.BLOCK,
            new ResourceLocation("touhou_aifun", "vision_scan_important"));

    private ShallowEnvironmentScanner() {
    }

    private static TagKey<Block> tag(String name) {
        return TagKey.create(net.minecraft.core.registries.Registries.BLOCK,
                new ResourceLocation("touhou_aifun", "vision_scan/" + name));
    }

    public static EnvironmentScanResult scan(EntityMaid maid, EnvironmentScanRequest request) {
        ScanJob job = begin(maid, request);
        while (!job.step(Integer.MAX_VALUE, MAX_DDA_VISITS, Integer.MAX_VALUE)) {
            // The legacy synchronous callers are few and still run on the server thread. The
            // important part is that they use the same capped state machine as the tick scheduler.
        }
        return job.result();
    }

    static ScanJob begin(EntityMaid maid, EnvironmentScanRequest request) {
        EnvironmentScanRequest normalized = request == null ? EnvironmentScanRequest.defaults() : request;
        return new ScanJob(maid, normalized);
    }

    /** Same classifier is used by block and entity visibility checks. */
    public static OpacityClass classify(BlockState state, Level level, BlockPos pos) {
        if (state.isAir()) {
            return null;
        }
        if (state.is(OPAQUE)) {
            return OpacityClass.OPAQUE;
        }
        if (state.is(LOW)) {
            return OpacityClass.LOW;
        }
        if (state.is(MEDIUM)) {
            return OpacityClass.MEDIUM;
        }
        if (state.is(HIGH)) {
            return OpacityClass.HIGH;
        }
        if (state.is(THIN)) {
            return OpacityClass.THIN;
        }

        FluidState fluid = state.getFluidState();
        if (!fluid.isEmpty()) {
            return fluid.isSource() ? OpacityClass.HIGH : OpacityClass.MEDIUM;
        }
        if (state.canOcclude()) {
            return OpacityClass.OPAQUE;
        }
        // Collision shapes distinguish leaves/glass-like blocks from plants and small decorations.
        if (!state.getCollisionShape(level, pos).isEmpty()) {
            return OpacityClass.LOW;
        }
        return state.canBeReplaced() ? OpacityClass.THIN : OpacityClass.MEDIUM;
    }

    /**
     * Incremental scan state. Each call to {@link #step(int, int, int)} only touches a bounded
     * number of primary rays, DDA cells and candidate sections, which keeps a tool call from
     * monopolising a server tick.
     */
    static final class ScanJob {
        private final EntityMaid maid;
        private final EnvironmentScanRequest request;
        private final Level level;
        private final ScanAccumulator accumulator;
        private final List<ScanDirection> faces;
        private final List<SectionWork> sections;
        private final Set<Long> blockEntityCandidates = new HashSet<>();
        private final Set<Long> importantFound = new HashSet<>();
        private int faceIndex;
        private int row;
        private int column;
        private int sectionIndex;
        private int sectionLocalX;
        private int sectionLocalY;
        private int sectionLocalZ;
        private boolean surfaceDone;
        private boolean importantDone;
        private boolean entitiesDone;
        private boolean finished;

        private ScanJob(EntityMaid maid, EnvironmentScanRequest request) {
            this.maid = maid;
            this.request = request;
            this.level = maid.level();
            BlockPos maidPos = maid.blockPosition();
            this.accumulator = new ScanAccumulator(maid, request, maid.getEyePosition(1.0F), maidPos);
            this.faces = request.mode().includesBlocks() ? faces(request.direction()) : List.of();
            this.surfaceDone = faces.isEmpty();
            this.sections = request.mode().includesBlocks() ? discoverSections() : List.of();
            this.importantDone = !request.mode().includesBlocks() || sections.isEmpty();
            this.entitiesDone = !request.mode().includesEntities();
        }

        boolean step(int rayBudget, int ddaBudget, int sectionBudget) {
            if (finished) {
                return true;
            }
            int rays = 0;
            int startVisits = accumulator.ddaVisits;
            int safeRayBudget = Math.max(1, rayBudget);
            int safeDdaBudget = Math.max(1, ddaBudget);
            int safeSectionBudget = Math.max(1, sectionBudget);

            while (!surfaceDone && rays < safeRayBudget
                    && accumulator.ddaVisits - startVisits < safeDdaBudget) {
                if (accumulator.ddaVisits >= MAX_DDA_VISITS) {
                    accumulator.truncated = true;
                    surfaceDone = true;
                    break;
                }
                ScanDirection face = faces.get(faceIndex);
                Vec3 direction = rotateYaw(cubemapDirection(face, row, column), maid.getYRot());
                trace(level, accumulator, direction, face.name().toLowerCase(Locale.ROOT));
                accumulator.primaryRays++;
                rays++;
                advanceRay();
            }
            if (!surfaceDone && faceIndex >= faces.size()) {
                surfaceDone = true;
            }

            int expandedSections = 0;
            while (surfaceDone && !importantDone && expandedSections < safeSectionBudget
                    && accumulator.ddaVisits - startVisits < safeDdaBudget) {
                if (sectionIndex >= sections.size()) {
                    importantDone = true;
                    break;
                }
                SectionWork section = sections.get(sectionIndex);
                if (!section.started) {
                    section.started = true;
                    expandedSections++;
                    accumulator.importantSectionKeys.add(section.key);
                }
                if (processSectionPosition(section, startVisits, safeDdaBudget)) {
                    sectionIndex++;
                    sectionLocalX = sectionLocalY = sectionLocalZ = 0;
                }
                if (accumulator.ddaVisits - startVisits >= safeDdaBudget) {
                    break;
                }
            }
            if (surfaceDone && sectionIndex >= sections.size()) {
                importantDone = true;
            }

            if (surfaceDone && importantDone && !entitiesDone
                    && accumulator.ddaVisits - startVisits < safeDdaBudget) {
                scanEntities(level, accumulator);
                entitiesDone = true;
            }
            finished = surfaceDone && importantDone && entitiesDone;
            return finished;
        }

        void abort() {
            accumulator.truncated = true;
            finished = true;
        }

        EntityMaid maid() {
            return maid;
        }

        EnvironmentScanResult result() {
            return accumulator.finish(level.dimension().location().toString());
        }

        private void advanceRay() {
            column++;
            if (column >= RAYS_PER_FACE) {
                column = 0;
                row++;
                if (row >= RAYS_PER_FACE) {
                    row = 0;
                    faceIndex++;
                }
            }
        }

        /** Process one section's local palette without asking the level to load anything. */
        private boolean processSectionPosition(SectionWork work, int startVisits, int ddaBudget) {
            while (sectionLocalX < 16) {
                if (accumulator.ddaVisits - startVisits >= ddaBudget) {
                    return false;
                }
                int worldX = (work.chunkX << 4) + sectionLocalX;
                int worldY = (work.sectionY << 4) + sectionLocalY;
                int worldZ = (work.chunkZ << 4) + sectionLocalZ;
                BlockPos pos = new BlockPos(worldX, worldY, worldZ);
                sectionLocalZ++;
                if (sectionLocalZ >= 16) {
                    sectionLocalZ = 0;
                    sectionLocalY++;
                    if (sectionLocalY >= 16) {
                        sectionLocalY = 0;
                        sectionLocalX++;
                    }
                }
                int dx = worldX - accumulator.maidPos.getX();
                int dy = worldY - accumulator.maidPos.getY();
                int dz = worldZ - accumulator.maidPos.getZ();
                int radius = request.maxDistance();
                if (dx * dx + dy * dy + dz * dz > radius * radius) {
                    continue;
                }
                // Read the palette state using local coordinates; no world chunk lookup occurs in
                // this hot section loop beyond the candidate that may actually be returned.
                int localX = Math.floorMod(worldX, 16);
                int localY = Math.floorMod(worldY, 16);
                int localZ = Math.floorMod(worldZ, 16);
                BlockState state = work.section.getBlockState(localX, localY, localZ);
                long packed = pos.asLong();
                if (!potentialImportant(state) && !blockEntityCandidates.contains(packed)) {
                    continue;
                }
                if (!importantFound.add(packed)) {
                    continue;
                }
                if (!isImportant(level, pos, state, blockEntityCandidates.contains(packed))) {
                    continue;
                }
                Visibility visibility = blockVisibility(level, accumulator.eye, pos, accumulator);
                if (!visibility.visible) {
                    continue;
                }
                String direction = directionFor(accumulator, center(pos));
                if (!matchesDirection(request.direction(), direction)) {
                    continue;
                }
                accumulator.addImportant(state, pos, accumulator.eye.distanceTo(center(pos)),
                        direction, level.getBlockEntity(pos));
            }
            return true;
        }

        private List<SectionWork> discoverSections() {
            List<SectionWork> discovered = new ArrayList<>();
            int radius = request.maxDistance();
            int minChunkX = Math.floorDiv(accumulator.maidPos.getX() - radius, 16);
            int maxChunkX = Math.floorDiv(accumulator.maidPos.getX() + radius, 16);
            int minChunkZ = Math.floorDiv(accumulator.maidPos.getZ() - radius, 16);
            int maxChunkZ = Math.floorDiv(accumulator.maidPos.getZ() + radius, 16);
            for (int chunkX = minChunkX; chunkX <= maxChunkX; chunkX++) {
                for (int chunkZ = minChunkZ; chunkZ <= maxChunkZ; chunkZ++) {
                    final int currentChunkX = chunkX;
                    final int currentChunkZ = chunkZ;
                    BlockPos probe = new BlockPos(chunkX << 4, accumulator.maidPos.getY(), chunkZ << 4);
                    if (!level.hasChunkAt(probe)) {
                        continue;
                    }
                    ChunkAccess access = level.getChunk(chunkX, chunkZ, ChunkStatus.FULL, false);
                    if (!(access instanceof LevelChunk chunk)) {
                        continue;
                    }
                    for (BlockPos blockEntityPos : chunk.getBlockEntities().keySet()) {
                        int dx = blockEntityPos.getX() - accumulator.maidPos.getX();
                        int dy = blockEntityPos.getY() - accumulator.maidPos.getY();
                        int dz = blockEntityPos.getZ() - accumulator.maidPos.getZ();
                        if (dx * dx + dy * dy + dz * dz <= radius * radius) {
                            blockEntityCandidates.add(blockEntityPos.asLong());
                        }
                    }
                    LevelChunkSection[] chunkSections = chunk.getSections();
                    for (int index = 0; index < chunkSections.length; index++) {
                        LevelChunkSection section = chunkSections[index];
                        int sectionY = level.getMinSection() + index;
                        int sectionMinY = sectionY << 4;
                        if (sectionMinY > accumulator.maidPos.getY() + radius
                                || sectionMinY + 15 < accumulator.maidPos.getY() - radius) {
                            continue;
                        }
                        boolean hasBlockEntity = blockEntityCandidates.stream().anyMatch(packed -> {
                            BlockPos pos = BlockPos.of(packed);
                            return pos.getX() >> 4 == currentChunkX && pos.getZ() >> 4 == currentChunkZ
                                    && pos.getY() >> 4 == sectionY;
                        });
                        if (hasBlockEntity || section.maybeHas(ShallowEnvironmentScanner::potentialImportant)) {
                            discovered.add(new SectionWork(chunkX, sectionY, chunkZ, section));
                        }
                    }
                }
            }
            discovered.sort(Comparator.comparingDouble(work -> {
                double x = (work.chunkX * 16 + 8) - accumulator.maid.getX();
                double y = (work.sectionY * 16 + 8) - accumulator.eye.y;
                double z = (work.chunkZ * 16 + 8) - accumulator.maid.getZ();
                return x * x + y * y + z * z;
            }));
            return discovered;
        }
    }

    private static final class SectionWork {
        private final int chunkX;
        private final int sectionY;
        private final int chunkZ;
        private final LevelChunkSection section;
        private final long key;
        private boolean started;

        private SectionWork(int chunkX, int sectionY, int chunkZ, LevelChunkSection section) {
            this.chunkX = chunkX;
            this.sectionY = sectionY;
            this.chunkZ = chunkZ;
            this.section = section;
            this.key = (((long) chunkX) << 42) ^ (((long) sectionY) << 21) ^ (chunkZ & 0x1fffffL);
        }
    }

    private static boolean potentialImportant(BlockState state) {
        if (state.is(IMPORTANT) || state.is(BlockTags.DOORS) || state.is(BlockTags.TRAPDOORS)
                || state.is(BlockTags.RAILS) || state.is(BlockTags.BEDS) || state.is(BlockTags.CLIMBABLE)) {
            return true;
        }
        ResourceLocation id = ForgeRegistries.BLOCKS.getKey(state.getBlock());
        if (id == null) {
            return false;
        }
        String path = id.getPath();
        return path.contains("ore") || path.contains("ancient_debris") || path.contains("beacon")
                || path.contains("enchant") || path.contains("portal") || path.contains("lava")
                || path.contains("fire") || path.contains("magma") || path.contains("tnt")
                || path.contains("cactus") || path.contains("powder_snow");
    }

    private static void scanSurface(Level level, ScanAccumulator accumulator) {
        for (ScanDirection face : faces(accumulator.request.direction())) {
            for (int row = 0; row < RAYS_PER_FACE; row++) {
                for (int column = 0; column < RAYS_PER_FACE; column++) {
                    if (accumulator.ddaVisits >= MAX_DDA_VISITS) {
                        accumulator.truncated = true;
                        return;
                    }
                    Vec3 direction = rotateYaw(cubemapDirection(face, row, column), accumulator.maid.getYRot());
                    trace(level, accumulator, direction, face.name().toLowerCase(Locale.ROOT));
                    accumulator.primaryRays++;
                }
            }
        }
    }

    private static List<ScanDirection> faces(ScanDirection direction) {
        if (direction == ScanDirection.ALL) {
            return List.of(ScanDirection.FRONT, ScanDirection.RIGHT, ScanDirection.BACK,
                    ScanDirection.LEFT, ScanDirection.UP, ScanDirection.DOWN);
        }
        return List.of(direction);
    }

    /** Seam-free cubemap direction, with pixel centres mapped to [-1, 1]. */
    private static Vec3 cubemapDirection(ScanDirection face, int row, int column) {
        return CUBEMAP_DIRECTIONS.get(face)[row * RAYS_PER_FACE + column];
    }

    private static Map<ScanDirection, Vec3[]> precomputeCubemapDirections() {
        Map<ScanDirection, Vec3[]> values = new LinkedHashMap<>();
        for (ScanDirection face : faces(ScanDirection.ALL)) {
            Vec3[] directions = new Vec3[RAYS_PER_FACE * RAYS_PER_FACE];
            for (int row = 0; row < RAYS_PER_FACE; row++) {
                for (int column = 0; column < RAYS_PER_FACE; column++) {
                    directions[row * RAYS_PER_FACE + column] = rawCubemapDirection(face, row, column);
                }
            }
            values.put(face, directions);
        }
        return Map.copyOf(values);
    }

    private static Vec3 rawCubemapDirection(ScanDirection face, int row, int column) {
        double u = ((column + 0.5) / RAYS_PER_FACE) * 2.0 - 1.0;
        double v = ((row + 0.5) / RAYS_PER_FACE) * 2.0 - 1.0;
        Vec3 direction = switch (face) {
            case FRONT -> new Vec3(u, -v, 1);
            case RIGHT -> new Vec3(1, -v, -u);
            case BACK -> new Vec3(-u, -v, -1);
            case LEFT -> new Vec3(-1, -v, u);
            case UP -> new Vec3(u, 1, v);
            case DOWN -> new Vec3(u, -1, -v);
            case ALL -> new Vec3(0, 0, 1);
        };
        return direction.normalize();
    }

    private static Vec3 rotateYaw(Vec3 local, float yawDegrees) {
        double yaw = Math.toRadians(yawDegrees);
        double cos = Math.cos(yaw);
        double sin = Math.sin(yaw);
        return new Vec3(cos * local.x - sin * local.z, local.y, sin * local.x + cos * local.z);
    }

    private static void trace(Level level, ScanAccumulator accumulator, Vec3 direction, String face) {
        double x = accumulator.eye.x;
        double y = accumulator.eye.y;
        double z = accumulator.eye.z;
        int blockX = floor(x);
        int blockY = floor(y);
        int blockZ = floor(z);
        int stepX = direction.x > 0 ? 1 : -1;
        int stepY = direction.y > 0 ? 1 : -1;
        int stepZ = direction.z > 0 ? 1 : -1;
        double tDeltaX = direction.x == 0 ? Double.POSITIVE_INFINITY : Math.abs(1.0 / direction.x);
        double tDeltaY = direction.y == 0 ? Double.POSITIVE_INFINITY : Math.abs(1.0 / direction.y);
        double tDeltaZ = direction.z == 0 ? Double.POSITIVE_INFINITY : Math.abs(1.0 / direction.z);
        double tMaxX = direction.x > 0 ? (blockX + 1 - x) * tDeltaX : (x - blockX) * tDeltaX;
        double tMaxY = direction.y > 0 ? (blockY + 1 - y) * tDeltaY : (y - blockY) * tDeltaY;
        double tMaxZ = direction.z > 0 ? (blockZ + 1 - z) * tDeltaZ : (z - blockZ) * tDeltaZ;
        int budget = VISIBILITY_BUDGET;
        BlockPos.MutableBlockPos mutable = new BlockPos.MutableBlockPos(blockX, blockY, blockZ);
        Set<Long> visited = new HashSet<>();

        while (Math.min(tMaxX, Math.min(tMaxY, tMaxZ)) <= accumulator.request.maxDistance()) {
            if (tMaxX < tMaxY && tMaxX < tMaxZ) {
                blockX += stepX;
                tMaxX += tDeltaX;
            } else if (tMaxY < tMaxZ) {
                blockY += stepY;
                tMaxY += tDeltaY;
            } else {
                blockZ += stepZ;
                tMaxZ += tDeltaZ;
            }
            accumulator.ddaVisits++;
            mutable.set(blockX, blockY, blockZ);
            if (!level.hasChunkAt(mutable)) {
                // Loaded chunks are not a requirement for a visual probe. Treat the boundary as empty.
                continue;
            }
            BlockState state = level.getBlockState(mutable);
            OpacityClass opacity = accumulator.classify(level, state, mutable);
            if (opacity == null) {
                continue;
            }
            long packed = mutable.asLong();
            double distance = Math.min(tMaxX, Math.min(tMaxY, tMaxZ));
            if (visited.add(packed)) {
                accumulator.addSurface(state, mutable, distance, face, opacity,
                        Math.max(0, budget - opacity.cost()));
            }
            budget -= opacity.cost();
            if (budget <= 0 || opacity == OpacityClass.OPAQUE) {
                return;
            }
        }
    }

    private static int floor(double value) {
        int integer = (int) value;
        return value < integer ? integer - 1 : integer;
    }

    private static void scanImportant(Level level, ScanAccumulator accumulator) {
        int radius = accumulator.request.maxDistance();
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        Set<Long> found = new HashSet<>();
        // This bounded neighbourhood is intentionally only a fallback. It checks loaded chunks and
        // never calls getChunk(), so an unloaded edge cannot be pulled into memory by a scan.
        for (int x = -radius; x <= radius; x++) {
            for (int y = -radius; y <= radius; y++) {
                for (int z = -radius; z <= radius; z++) {
                    if (x * x + y * y + z * z > radius * radius) {
                        continue;
                    }
                    pos.set(accumulator.maidPos.getX() + x, accumulator.maidPos.getY() + y,
                            accumulator.maidPos.getZ() + z);
                    if (!level.hasChunkAt(pos)) {
                        continue;
                    }
                    accumulator.importantSectionKeys.add(ScanAccumulator.sectionKey(pos));
                    BlockState state = level.getBlockState(pos);
                    if (!isImportant(level, pos, state)) {
                        continue;
                    }
                    if (!found.add(pos.asLong())) {
                        continue;
                    }
                    Visibility visibility = blockVisibility(level, accumulator.eye, pos, accumulator);
                    if (!visibility.visible) {
                        continue;
                    }
                    String direction = directionFor(accumulator, center(pos));
                    if (!matchesDirection(accumulator.request.direction(), direction)) {
                        continue;
                    }
                    accumulator.addImportant(state, pos, accumulator.eye.distanceTo(center(pos)),
                            direction, level.getBlockEntity(pos));
                }
            }
        }
    }

    private static boolean isImportant(Level level, BlockPos pos, BlockState state) {
        return isImportant(level, pos, state, level.getBlockEntity(pos) != null);
    }

    private static boolean isImportant(Level level, BlockPos pos, BlockState state, boolean hasBlockEntity) {
        if (state.is(IMPORTANT) || hasBlockEntity) {
            return true;
        }
        if (state.is(BlockTags.DOORS) || state.is(BlockTags.TRAPDOORS) || state.is(BlockTags.RAILS)
                || state.is(BlockTags.BEDS) || state.is(BlockTags.CLIMBABLE)) {
            return true;
        }
        ResourceLocation id = ForgeRegistries.BLOCKS.getKey(state.getBlock());
        if (id == null) {
            return false;
        }
        String path = id.getPath();
        return path.contains("ore") || path.contains("ancient_debris") || path.contains("beacon")
                || path.contains("enchant") || path.contains("portal") || path.contains("lava")
                || path.contains("fire") || path.contains("magma") || path.contains("tnt")
                || path.contains("cactus") || path.contains("powder_snow");
    }

    private static Vec3 center(BlockPos pos) {
        return Vec3.atCenterOf(pos);
    }

    /** Check the center plus each face center so a one-block target is not rejected by a ray seam. */
    private static Visibility blockVisibility(Level level, Vec3 eye, BlockPos pos, ScanAccumulator accumulator) {
        Vec3 center = center(pos);
        List<Vec3> points = List.of(center,
                center.add(0.48, 0, 0), center.add(-0.48, 0, 0),
                center.add(0, 0.48, 0), center.add(0, -0.48, 0),
                center.add(0, 0, 0.48), center.add(0, 0, -0.48));
        int visible = 0;
        for (Vec3 point : points) {
            if (visibility(level, eye, point, accumulator).visible) visible++;
        }
        return new Visibility(visible > 0, visible == points.size(), visible);
    }

    private static void scanEntities(Level level, ScanAccumulator accumulator) {
        double radius = accumulator.request.maxDistance();
        AABB box = accumulator.maid.getBoundingBox().inflate(radius);
        List<Entity> entities = level.getEntities(accumulator.maid, box,
                entity -> entity.isAlive() && isVisualEntity(entity));
        entities.removeIf(entity -> accumulator.maid.distanceToSqr(entity) > radius * radius);
        entities.sort(Comparator.comparingInt((Entity entity) -> entity instanceof Player ? 0 : entity instanceof Enemy ? 1 : 2)
                .thenComparingDouble(entity -> accumulator.maid.distanceToSqr(entity)));

        for (Entity entity : entities) {
            Vec3 origin = entity.getBoundingBox().getCenter();
            if (accumulator.request.direction() != ScanDirection.ALL
                    && !matchesDirection(accumulator.request.direction(), directionFor(accumulator, origin))) {
                continue;
            }
            if (accumulator.entities.size() >= MAX_ENTITIES) {
                accumulator.omittedEntities++;
                String direction = directionFor(accumulator, origin);
                accumulator.omittedEntityGroups.merge(entityCategory(entity) + "|" + direction, 1, Integer::sum);
                continue;
            }
            Visibility visibility = entityVisibility(level, accumulator.eye, entity, accumulator);
            accumulator.addEntity(entity, origin, visibility);
        }
    }

    private static boolean isVisualEntity(Entity entity) {
        return entity instanceof LivingEntity || entity instanceof ItemEntity || entity instanceof Boat
                || entity instanceof AbstractMinecart || entity instanceof Projectile
                || entity instanceof FallingBlockEntity || entity instanceof ItemFrame
                || entity instanceof Painting || entity instanceof PrimedTnt;
    }

    private static String entityCategory(Entity entity) {
        return entity instanceof Player ? "player"
                : entity instanceof Enemy ? "hostile"
                : entity instanceof ItemEntity ? "item"
                : entity instanceof Boat || entity instanceof AbstractMinecart ? "vehicle"
                : entity instanceof Projectile ? "projectile"
                : entity instanceof LivingEntity ? "passive" : "other";
    }

    private static Visibility entityVisibility(Level level, Vec3 eye, Entity entity, ScanAccumulator accumulator) {
        List<Vec3> points = List.of(entity.getEyePosition(1.0F), entity.getBoundingBox().getCenter(),
                new Vec3(entity.getX(), entity.getBoundingBox().maxY, entity.getZ()));
        int visible = 0;
        for (Vec3 point : points) {
            Visibility value = visibility(level, eye, point, accumulator);
            if (value.visible) {
                visible++;
            }
        }
        return new Visibility(visible > 0, visible == points.size(), visible);
    }

    private static Visibility visibility(Level level, Vec3 eye, Vec3 target, ScanAccumulator accumulator) {
        // Clip is the authoritative fast path for opaque shapes. The explicit weighted walk below
        // handles leaves/glass/fluids so that visual and entity scans agree about partial cover.
        Vec3 delta = target.subtract(eye);
        double length = delta.length();
        if (length < 0.01) {
            return new Visibility(true, true, 1);
        }
        Vec3 direction = delta.scale(1.0 / length);
        int blockX = floor(eye.x);
        int blockY = floor(eye.y);
        int blockZ = floor(eye.z);
        int stepX = direction.x > 0 ? 1 : -1;
        int stepY = direction.y > 0 ? 1 : -1;
        int stepZ = direction.z > 0 ? 1 : -1;
        double tDeltaX = direction.x == 0 ? Double.POSITIVE_INFINITY : Math.abs(1.0 / direction.x);
        double tDeltaY = direction.y == 0 ? Double.POSITIVE_INFINITY : Math.abs(1.0 / direction.y);
        double tDeltaZ = direction.z == 0 ? Double.POSITIVE_INFINITY : Math.abs(1.0 / direction.z);
        double tMaxX = direction.x > 0 ? (blockX + 1 - eye.x) * tDeltaX : (eye.x - blockX) * tDeltaX;
        double tMaxY = direction.y > 0 ? (blockY + 1 - eye.y) * tDeltaY : (eye.y - blockY) * tDeltaY;
        double tMaxZ = direction.z > 0 ? (blockZ + 1 - eye.z) * tDeltaZ : (eye.z - blockZ) * tDeltaZ;
        BlockPos.MutableBlockPos mutable = new BlockPos.MutableBlockPos(blockX, blockY, blockZ);
        int budget = VISIBILITY_BUDGET;
        while (Math.min(tMaxX, Math.min(tMaxY, tMaxZ)) < length) {
            if (tMaxX < tMaxY && tMaxX < tMaxZ) {
                blockX += stepX;
                tMaxX += tDeltaX;
            } else if (tMaxY < tMaxZ) {
                blockY += stepY;
                tMaxY += tDeltaY;
            } else {
                blockZ += stepZ;
                tMaxZ += tDeltaZ;
            }
            accumulator.ddaVisits++;
            mutable.set(blockX, blockY, blockZ);
            if (!level.hasChunkAt(mutable)) {
                continue;
            }
            OpacityClass opacity = accumulator.classify(level, level.getBlockState(mutable), mutable);
            if (opacity == null) {
                continue;
            }
            budget -= opacity.cost();
            if (budget <= 0 || opacity == OpacityClass.OPAQUE) {
                return new Visibility(false, false, 0);
            }
        }
        return new Visibility(true, true, 1);
    }

    private static String directionFor(ScanAccumulator accumulator, Vec3 point) {
        double dx = point.x - accumulator.maid.getX();
        double dy = point.y - accumulator.eye.y;
        double dz = point.z - accumulator.maid.getZ();
        double yaw = Math.toRadians(accumulator.maid.getYRot());
        double front = -Math.sin(yaw) * dx + Math.cos(yaw) * dz;
        double right = Math.cos(yaw) * dx + Math.sin(yaw) * dz;
        if (Math.abs(dy) > Math.max(Math.abs(front), Math.abs(right)) * 0.7) {
            return dy > 0 ? "up" : "down";
        }
        if (Math.abs(front) >= Math.abs(right)) {
            return front >= 0 ? "front" : "back";
        }
        return right >= 0 ? "right" : "left";
    }

    private static boolean matchesDirection(ScanDirection requested, String direction) {
        return requested == ScanDirection.ALL || requested.name().equalsIgnoreCase(direction);
    }

    private record Visibility(boolean visible, boolean fullyVisible, int visibleSamples) {
    }

    private static final class ScanAccumulator {
        private final EntityMaid maid;
        private final EnvironmentScanRequest request;
        private final Vec3 eye;
        private final BlockPos maidPos;
        private final Map<String, SurfaceAggregate> surfaceMap = new LinkedHashMap<>();
        private final Map<BlockState, OpacityClass> opacityByState = new HashMap<>();
        private final List<ImportantBlockHit> importantBlocks = new ArrayList<>();
        private final List<ScannedEntity> entities = new ArrayList<>();
        private final Set<Long> importantSectionKeys = new HashSet<>();
        private int primaryRays;
        private int ddaVisits;
        private int importantSections;
        private int omittedSurfaceGroups;
        private int omittedImportantBlocks;
        private int omittedEntities;
        private final Map<String, Integer> omittedEntityGroups = new LinkedHashMap<>();
        private boolean truncated;

        private ScanAccumulator(EntityMaid maid, EnvironmentScanRequest request, Vec3 eye, BlockPos maidPos) {
            this.maid = maid;
            this.request = request;
            this.eye = eye;
            this.maidPos = maidPos;
        }

        private OpacityClass classify(Level level, BlockState state, BlockPos pos) {
            if (state.isAir()) return null;
            OpacityClass cached = opacityByState.get(state);
            if (cached != null) return cached;
            OpacityClass value = ShallowEnvironmentScanner.classify(state, level, pos);
            if (value != null) opacityByState.put(state, value);
            return value;
        }

        private void addSurface(BlockState state, BlockPos pos, double distance, String face,
                                OpacityClass opacity, int remainingBudget) {
            ResourceLocation id = ForgeRegistries.BLOCKS.getKey(state.getBlock());
            if (id == null) {
                return;
            }
            String direction = directionFor(this, center(pos));
            if (!matchesDirection(request.direction(), direction)) {
                return;
            }
            String key = id + "|" + direction + "|" + Math.min(20, (int) distance / 4);
            SurfaceAggregate aggregate = surfaceMap.get(key);
            if (aggregate == null) {
                if (surfaceMap.size() >= MAX_SURFACE_GROUPS) {
                    omittedSurfaceGroups++;
                    truncated = true;
                    return;
                }
                aggregate = new SurfaceAggregate(id.toString(), direction, opacity);
                surfaceMap.put(key, aggregate);
            }
            aggregate.count++;
            aggregate.nearest = Math.min(aggregate.nearest, distance);
            aggregate.farthest = Math.max(aggregate.farthest, distance);
            if (aggregate.representatives.size() < 3) {
                aggregate.representatives.add(new SurfaceBlockHit(id.toString(),
                        pos.getX() - maidPos.getX(), pos.getY() - maidPos.getY(), pos.getZ() - maidPos.getZ(),
                        distance, direction, opacity, remainingBudget, 1, distance));
            }
        }

        private void addImportant(BlockState state, BlockPos pos, double distance, String direction,
                                  net.minecraft.world.level.block.entity.BlockEntity blockEntity) {
            if (importantBlocks.size() >= MAX_IMPORTANT_BLOCKS) {
                omittedImportantBlocks++;
                truncated = true;
                return;
            }
            ResourceLocation id = ForgeRegistries.BLOCKS.getKey(state.getBlock());
            if (id == null) {
                return;
            }
            Map<String, String> properties = new LinkedHashMap<>();
            for (Map.Entry<Property<?>, Comparable<?>> property : state.getValues().entrySet()) {
                properties.put(property.getKey().getName(), String.valueOf(property.getValue()));
            }
            String blockEntityType = "";
            if (blockEntity != null && ForgeRegistries.BLOCK_ENTITY_TYPES.getKey(blockEntity.getType()) != null) {
                blockEntityType = ForgeRegistries.BLOCK_ENTITY_TYPES.getKey(blockEntity.getType()).toString();
            }
            importantBlocks.add(new ImportantBlockHit(id.toString(), pos.getX() - maidPos.getX(),
                    pos.getY() - maidPos.getY(), pos.getZ() - maidPos.getZ(), distance, direction,
                    properties, blockEntityType));
        }

        private void addEntity(Entity entity, Vec3 center, Visibility visibility) {
            ResourceLocation id = ForgeRegistries.ENTITY_TYPES.getKey(entity.getType());
            if (id == null) {
                return;
            }
            String category = entityCategory(entity);
            String customName = entity.getCustomName() == null ? "" : entity.getCustomName().getString();
            Float health = null;
            Float maxHealth = null;
            String pose = "";
            List<String> states = new ArrayList<>();
            String itemId = "";
            int itemCount = 0;
            if (entity instanceof LivingEntity living) {
                health = living.getHealth();
                maxHealth = living.getMaxHealth();
                pose = living.getPose().name().toLowerCase(Locale.ROOT);
                if (living.isOnFire()) states.add("on_fire");
                if (living.isCrouching()) states.add("crouching");
                if (living.isSprinting()) states.add("sprinting");
                if (living.isBaby()) states.add("baby");
                if (living.isInWater()) states.add("in_water");
                if (living.isSwimming()) states.add("swimming");
                if (living.isInvisible()) states.add("invisible");
                if (living.isCurrentlyGlowing()) states.add("glowing");
                if (living.isPassenger()) states.add("passenger");
                if (living.isSleeping()) states.add("sleeping");
            }
            if (entity instanceof ItemEntity item) {
                ResourceLocation itemKey = ForgeRegistries.ITEMS.getKey(item.getItem().getItem());
                itemId = itemKey == null ? "" : itemKey.toString();
                itemCount = item.getItem().getCount();
            }
            double dx = center.x - maid.getX();
            double dy = center.y - eye.y;
            double dz = center.z - maid.getZ();
            double distance = Math.sqrt(dx * dx + dy * dy + dz * dz);
            String elevation = Math.abs(dy) < 1.0 ? "level" : dy > 0 ? "above" : "below";
            String visibilityName = visibility.fullyVisible ? "visible" : visibility.visible ? "partial" : "occluded";
            if (!visibility.visible && request.mode() != ScanMode.ENTITIES) {
                return;
            }
            entities.add(new ScannedEntity(id.toString(), entity.getId(), customName, category, dx, dy, dz,
                    distance, directionFor(this, center), elevation, visibilityName, health, maxHealth, pose,
                    List.copyOf(states), itemId, itemCount));
        }

        private EnvironmentScanResult finish(String dimension) {
            List<SurfaceBlockHit> surfaces = new ArrayList<>();
            for (SurfaceAggregate aggregate : surfaceMap.values()) {
                for (SurfaceBlockHit representative : aggregate.representatives) {
                    surfaces.add(new SurfaceBlockHit(representative.registryId(), representative.dx(), representative.dy(),
                            representative.dz(), representative.distance(), representative.direction(), representative.opacity(),
                            representative.remainingBudget(), aggregate.count, aggregate.farthest));
                }
            }
            String focus = request.focus().toLowerCase(Locale.ROOT);
            Comparator<String> focusFirst = (left, right) -> {
                boolean leftMatch = !focus.isBlank() && left.toLowerCase(Locale.ROOT).contains(focus);
                boolean rightMatch = !focus.isBlank() && right.toLowerCase(Locale.ROOT).contains(focus);
                return Boolean.compare(rightMatch, leftMatch);
            };
            surfaces.sort(Comparator.comparing(SurfaceBlockHit::registryId, focusFirst)
                    .thenComparingDouble(SurfaceBlockHit::distance));
            importantBlocks.sort(Comparator.comparing(ImportantBlockHit::registryId, focusFirst)
                    .thenComparingDouble(ImportantBlockHit::distance));
            entities.sort(Comparator.comparing(ScannedEntity::registryId, focusFirst)
                    .thenComparingInt(entity -> entityPriority(entity.category(), entity.visibility()))
                    .thenComparingDouble(ScannedEntity::distance));
            return new EnvironmentScanResult(maid.level().getGameTime(), dimension, request, primaryRays,
                    ddaVisits, importantSectionKeys.size(), surfaces, importantBlocks, entities, omittedSurfaceGroups,
                    omittedImportantBlocks, omittedEntities, omittedEntityGroups, truncated);
        }

        private static long sectionKey(BlockPos pos) {
            long x = ((long) (pos.getX() >> 4)) & 0x3ffffffL;
            long z = ((long) (pos.getZ() >> 4)) & 0x3ffffffL;
            long y = ((long) (pos.getY() >> 4)) & 0xfffL;
            return x | (z << 26) | (y << 52);
        }

        private static int entityPriority(String category, String visibility) {
            int base = switch (category) {
                case "player" -> 0;
                case "hostile" -> 1;
                case "item", "vehicle", "projectile", "other" -> 3;
                default -> 4;
            };
            if ("visible".equals(visibility)) return Math.min(base, 2);
            if ("partial".equals(visibility)) return Math.min(base + 1, 3);
            return base + 5;
        }
    }

    private static final class SurfaceAggregate {
        private final String id;
        private final String direction;
        private final OpacityClass opacity;
        private final List<SurfaceBlockHit> representatives = new ArrayList<>();
        private int count;
        private double nearest = Double.MAX_VALUE;
        private double farthest;

        private SurfaceAggregate(String id, String direction, OpacityClass opacity) {
            this.id = id;
            this.direction = direction;
            this.opacity = opacity;
        }
    }
}
