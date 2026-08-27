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
import net.minecraft.world.phys.shapes.VoxelShape;
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
    private static final int MAX_ENTITY_CANDIDATES = 512;
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
        private final List<Long> blockEntityWork;
        private final Set<Long> importantFound = new HashSet<>();
        private int faceIndex;
        private int row;
        private int column;
        private int sectionIndex;
        private int sectionLocalX;
        private int sectionLocalY;
        private int sectionLocalZ;
        private int blockEntityIndex;
        private List<Entity> entityCandidates = List.of();
        private int entityIndex;
        private boolean entitiesPrepared;
        private boolean surfaceDone;
        private boolean blockEntitiesDone;
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
            this.blockEntityWork = blockEntityCandidates.stream().sorted(Comparator.comparingDouble(packed -> {
                BlockPos pos = BlockPos.of(packed);
                double dx = pos.getX() + 0.5 - accumulator.originX;
                double dy = pos.getY() + 0.5 - accumulator.eye.y;
                double dz = pos.getZ() + 0.5 - accumulator.originZ;
                return dx * dx + dy * dy + dz * dz;
            })).toList();
            this.blockEntitiesDone = !request.mode().includesBlocks() || blockEntityWork.isEmpty();
            this.importantDone = !request.mode().includesBlocks()
                    || blockEntitiesDone && sections.isEmpty();
            this.entitiesDone = !request.mode().includesEntities();
        }

        boolean step(int rayBudget, int ddaBudget, int sectionBudget) {
            if (finished) {
                return true;
            }
            int rays = 0;
            int startVisits = accumulator.ddaVisits;
            int safeRayBudget = Math.max(0, rayBudget);
            int safeDdaBudget = Math.max(0, ddaBudget);
            int safeSectionBudget = Math.max(0, sectionBudget);
            int primaryRayReserve = maxDdaVisitsPerRay(request.maxDistance());

            while (!surfaceDone && faceIndex < faces.size() && rays < safeRayBudget
                    && accumulator.ddaVisits - startVisits + primaryRayReserve <= safeDdaBudget) {
                if (accumulator.ddaVisits >= MAX_DDA_VISITS) {
                    accumulator.markTruncated("dda_visit_limit");
                    surfaceDone = true;
                    break;
                }
                ScanDirection face = faces.get(faceIndex);
                Vec3 direction = VisionDirectionMath.rotateYaw(
                        cubemapDirection(face, row, column), accumulator.originYaw);
                if (trace(level, accumulator, direction, face.name().toLowerCase(Locale.ROOT))) {
                    accumulator.surfaceRaysWithHits++;
                }
                accumulator.primaryRays++;
                rays++;
                advanceRay();
            }
            if (faceIndex >= faces.size()) {
                surfaceDone = true;
            }

            while (surfaceDone && !blockEntitiesDone
                    && accumulator.ddaVisits - startVisits < safeDdaBudget) {
                if (blockEntityIndex >= blockEntityWork.size()) {
                    blockEntitiesDone = true;
                    break;
                }
                if (!processBlockEntityCandidate(blockEntityWork.get(blockEntityIndex), startVisits, safeDdaBudget)) {
                    break;
                }
                blockEntityIndex++;
            }
            if (blockEntityIndex >= blockEntityWork.size()) {
                blockEntitiesDone = true;
            }

            int expandedSections = 0;
            while (surfaceDone && blockEntitiesDone && !importantDone && expandedSections < safeSectionBudget
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
            if (surfaceDone && blockEntitiesDone && sectionIndex >= sections.size()) {
                importantDone = true;
            }

            if (surfaceDone && importantDone && !entitiesDone
                    && accumulator.ddaVisits - startVisits < safeDdaBudget) {
                if (!entitiesPrepared) {
                    prepareEntities();
                }
                processEntities(startVisits, safeDdaBudget);
                entitiesDone = entityIndex >= entityCandidates.size();
            }
            finished = surfaceDone && importantDone && entitiesDone;
            return finished;
        }

        void abort() {
            abort("cancelled");
        }

        void abort(String reason) {
            accumulator.markTruncated(reason);
            finished = true;
        }

        EntityMaid maid() {
            return maid;
        }

        int primaryRays() {
            return accumulator.primaryRays;
        }

        int ddaVisits() {
            return accumulator.ddaVisits;
        }

        int expandedSections() {
            return accumulator.importantSectionKeys.size();
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
                    if (faceIndex >= faces.size()) {
                        surfaceDone = true;
                    }
                }
            }
        }

        /** Query once, then spread the comparatively expensive visibility rays over later ticks. */
        private void prepareEntities() {
            entitiesPrepared = true;
            double radius = request.maxDistance();
            AABB box = new AABB(accumulator.originX - radius, accumulator.originY - radius,
                    accumulator.originZ - radius, accumulator.originX + radius,
                    accumulator.originY + radius, accumulator.originZ + radius);
            List<Entity> found = level.getEntities(maid, box,
                    entity -> entity.isAlive() && isVisualEntity(entity));
            found.removeIf(entity -> frozenDistanceSqr(entity.getBoundingBox().getCenter()) > radius * radius
                    || request.direction() != ScanDirection.ALL && !matchesDirection(request.direction(),
                    directionFor(accumulator, entity.getBoundingBox().getCenter())));
            found.sort(Comparator
                    .comparingInt((Entity entity) -> entity instanceof Player ? 0 : entity instanceof Enemy ? 1 : 2)
                    .thenComparingDouble(entity -> frozenDistanceSqr(entity.getBoundingBox().getCenter())));
            if (found.size() > MAX_ENTITY_CANDIDATES) {
                for (int index = MAX_ENTITY_CANDIDATES; index < found.size(); index++) {
                    accumulator.omitEntity(found.get(index));
                }
                accumulator.markTruncated("entity_candidate_limit");
                found = new ArrayList<>(found.subList(0, MAX_ENTITY_CANDIDATES));
            }
            entityCandidates = found;
        }

        private void processEntities(int startVisits, int ddaBudget) {
            // A normalized DDA ray can cross sqrt(3) voxels per travelled block. Reserving the
            // worst case for all three samples prevents one entity from blowing past this tick's
            // global DDA allowance.
            int maxVisitsPerRay = maxDdaVisitsPerRay(request.maxDistance() + 2);
            int reservePerEntity = 3 * maxVisitsPerRay;
            while (entityIndex < entityCandidates.size()) {
                int remaining = ddaBudget - (accumulator.ddaVisits - startVisits);
                if (remaining < reservePerEntity) {
                    return;
                }
                Entity entity = entityCandidates.get(entityIndex++);
                if (!entity.isAlive()) {
                    continue;
                }
                Vec3 center = entity.getBoundingBox().getCenter();
                double dx = center.x - accumulator.originX;
                double dy = center.y - accumulator.originY;
                double dz = center.z - accumulator.originZ;
                double radius = request.maxDistance();
                if (dx * dx + dy * dy + dz * dz > radius * radius) {
                    continue;
                }
                Visibility visibility = entityVisibility(level, accumulator.eye, entity, accumulator);
                accumulator.addEntity(entity, center, visibility);
            }
        }

        private double frozenDistanceSqr(Vec3 point) {
            double dx = point.x - accumulator.originX;
            double dy = point.y - accumulator.originY;
            double dz = point.z - accumulator.originZ;
            return dx * dx + dy * dy + dz * dz;
        }

        /** Block entities already have an indexed position list; never enumerate their section. */
        private boolean processBlockEntityCandidate(long packed, int startVisits, int ddaBudget) {
            BlockPos pos = BlockPos.of(packed);
            if (!level.hasChunkAt(pos) || importantFound.contains(packed)) {
                return true;
            }
            int visibilityReserve = 7 * maxDdaVisitsPerRay(request.maxDistance() + 2);
            if (accumulator.ddaVisits - startVisits + visibilityReserve > ddaBudget) {
                return false;
            }
            importantFound.add(packed);
            BlockState state = level.getBlockState(pos);
            Visibility visibility = blockVisibility(level, accumulator.eye, pos, accumulator);
            if (!visibility.visible) {
                return true;
            }
            String direction = directionFor(accumulator, center(pos));
            if (!matchesDirection(request.direction(), direction)) {
                return true;
            }
            accumulator.addImportant(state, pos, accumulator.eye.distanceTo(center(pos)),
                    direction, level.getBlockEntity(pos));
            return true;
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
                int dx = worldX - accumulator.maidPos.getX();
                int dy = worldY - accumulator.maidPos.getY();
                int dz = worldZ - accumulator.maidPos.getZ();
                int radius = request.maxDistance();
                if (dx * dx + dy * dy + dz * dz > radius * radius) {
                    advanceSectionPosition();
                    continue;
                }
                // Read the palette state using local coordinates; no world chunk lookup occurs in
                // this hot section loop beyond the candidate that may actually be returned.
                int localX = Math.floorMod(worldX, 16);
                int localY = Math.floorMod(worldY, 16);
                int localZ = Math.floorMod(worldZ, 16);
                BlockState state = work.section.getBlockState(localX, localY, localZ);
                long packed = BlockPos.asLong(worldX, worldY, worldZ);
                if (!potentialImportant(state)) {
                    advanceSectionPosition();
                    continue;
                }
                if (!importantFound.add(packed)) {
                    advanceSectionPosition();
                    continue;
                }
                BlockPos pos = BlockPos.of(packed);
                if (!isImportant(level, pos, state, false)) {
                    advanceSectionPosition();
                    continue;
                }
                int visibilityReserve = 7 * maxDdaVisitsPerRay(request.maxDistance() + 2);
                if (accumulator.ddaVisits - startVisits + visibilityReserve > ddaBudget) {
                    // Do not consume the cursor until this candidate has enough allowance. Also
                    // undo the de-dup insertion so it remains eligible on the next tick.
                    importantFound.remove(packed);
                    return false;
                }
                Visibility visibility = blockVisibility(level, accumulator.eye, pos, accumulator);
                if (!visibility.visible) {
                    advanceSectionPosition();
                    continue;
                }
                String direction = directionFor(accumulator, center(pos));
                if (!matchesDirection(request.direction(), direction)) {
                    advanceSectionPosition();
                    continue;
                }
                accumulator.addImportant(state, pos, accumulator.eye.distanceTo(center(pos)),
                        direction, level.getBlockEntity(pos));
                advanceSectionPosition();
            }
            return true;
        }

        private void advanceSectionPosition() {
            sectionLocalZ++;
            if (sectionLocalZ >= 16) {
                sectionLocalZ = 0;
                sectionLocalY++;
                if (sectionLocalY >= 16) {
                    sectionLocalY = 0;
                    sectionLocalX++;
                }
            }
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
                        if (section.maybeHas(ShallowEnvironmentScanner::potentialImportant)) {
                            discovered.add(new SectionWork(chunkX, sectionY, chunkZ, section));
                        }
                    }
                }
            }
            discovered.sort(Comparator.comparingDouble(work -> {
                double x = (work.chunkX * 16 + 8) - accumulator.originX;
                double y = (work.sectionY * 16 + 8) - accumulator.eye.y;
                double z = (work.chunkZ * 16 + 8) - accumulator.originZ;
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
                        accumulator.markTruncated("dda_visit_limit");
                        return;
                    }
                    Vec3 direction = VisionDirectionMath.rotateYaw(
                            cubemapDirection(face, row, column), accumulator.originYaw);
                    if (trace(level, accumulator, direction, face.name().toLowerCase(Locale.ROOT))) {
                        accumulator.surfaceRaysWithHits++;
                    }
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
                    directions[row * RAYS_PER_FACE + column] = VisionDirectionMath.rawCubemapDirection(
                            face, row, column, RAYS_PER_FACE);
                }
            }
            values.put(face, directions);
        }
        return Map.copyOf(values);
    }

    private static boolean trace(Level level, ScanAccumulator accumulator, Vec3 direction, String face) {
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
        boolean hit = false;
        BlockPos.MutableBlockPos mutable = new BlockPos.MutableBlockPos(blockX, blockY, blockZ);
        while (Math.min(tMaxX, Math.min(tMaxY, tMaxZ)) <= accumulator.request.maxDistance()) {
            double entryDistance = Math.min(tMaxX, Math.min(tMaxY, tMaxZ));
            if (tMaxX == entryDistance) {
                blockX += stepX;
                tMaxX += tDeltaX;
            }
            if (tMaxY == entryDistance) {
                blockY += stepY;
                tMaxY += tDeltaY;
            }
            if (tMaxZ == entryDistance) {
                blockZ += stepZ;
                tMaxZ += tDeltaZ;
            }
            accumulator.ddaVisits++;
            mutable.set(blockX, blockY, blockZ);
            if (!level.hasChunkAt(mutable)) {
                // Unknown world data is not transparent. Stop without loading a chunk or claiming
                // that something behind the unloaded boundary is visible.
                return hit;
            }
            BlockState state = level.getBlockState(mutable);
            OpacityClass opacity = accumulator.classify(level, state, mutable);
            if (opacity == null) {
                continue;
            }
            hit = true;
            accumulator.addSurface(state, mutable, entryDistance, face, opacity,
                    Math.max(0, budget - opacity.cost()));
            budget -= opacity.cost();
            if (budget <= 0 || opacity == OpacityClass.OPAQUE) {
                return true;
            }
        }
        return hit;
    }

    private static int floor(double value) {
        int integer = (int) value;
        return value < integer ? integer - 1 : integer;
    }

    private static int maxDdaVisitsPerRay(int distance) {
        return (int) Math.ceil(Math.sqrt(3.0) * distance) + 3;
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
            if (visibility(level, eye, point, pos.asLong(), accumulator).visible) visible++;
        }
        return new Visibility(visible > 0, visible == points.size(), visible);
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
            Visibility value = visibility(level, eye, point, Long.MIN_VALUE, accumulator);
            if (value.visible) {
                visible++;
            }
        }
        return new Visibility(visible > 0, visible == points.size(), visible);
    }

    private static Visibility visibility(Level level, Vec3 eye, Vec3 target, long targetBlock,
                                         ScanAccumulator accumulator) {
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
            double entryDistance = Math.min(tMaxX, Math.min(tMaxY, tMaxZ));
            if (tMaxX == entryDistance) {
                blockX += stepX;
                tMaxX += tDeltaX;
            }
            if (tMaxY == entryDistance) {
                blockY += stepY;
                tMaxY += tDeltaY;
            }
            if (tMaxZ == entryDistance) {
                blockZ += stepZ;
                tMaxZ += tDeltaZ;
            }
            accumulator.ddaVisits++;
            mutable.set(blockX, blockY, blockZ);
            // Visibility is measured up to the target surface. The target block must never
            // consume its own opacity budget (opaque chests/ores used to hide themselves here).
            if (targetBlock != Long.MIN_VALUE && mutable.asLong() == targetBlock) {
                return new Visibility(true, true, 1);
            }
            if (!level.hasChunkAt(mutable)) {
                return new Visibility(false, false, 0);
            }
            BlockState state = level.getBlockState(mutable);
            OpacityClass opacity = accumulator.classify(level, state, mutable);
            if (opacity == null) {
                continue;
            }
            if (!visibilityShapeIntersects(state, level, mutable, eye, target)) {
                // Important/entity visibility uses the actual outline. Empty space inside stairs,
                // fences and open doors must not behave like a completely filled opaque voxel.
                continue;
            }
            budget -= opacity.cost();
            if (budget <= 0 || opacity == OpacityClass.OPAQUE) {
                return new Visibility(false, false, 0);
            }
        }
        return new Visibility(true, true, 1);
    }

    private static boolean visibilityShapeIntersects(BlockState state, Level level, BlockPos pos,
                                                     Vec3 start, Vec3 end) {
        if (!state.getFluidState().isEmpty()) return true;
        VoxelShape shape = state.getShape(level, pos);
        return shape.isEmpty() || shape.clip(start, end, pos) != null;
    }

    private static String directionFor(ScanAccumulator accumulator, Vec3 point) {
        return VisionDirectionMath.relativeDirection(accumulator.originX, accumulator.eye.y,
                accumulator.originZ, accumulator.originYaw, point);
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
        private final double originX;
        private final double originY;
        private final double originZ;
        private final float originYaw;
        private final long originTick;
        private final Map<String, SurfaceAggregate> surfaceMap = new LinkedHashMap<>();
        private final Set<String> omittedSurfaceKeys = new HashSet<>();
        private final Map<String, Integer> surfaceDirectionSamples = new LinkedHashMap<>();
        private final Map<BlockState, OpacityClass> opacityByState = new HashMap<>();
        private final List<ImportantBlockHit> importantBlocks = new ArrayList<>();
        private final List<ScannedEntity> entities = new ArrayList<>();
        private final Set<Long> importantSectionKeys = new HashSet<>();
        private int primaryRays;
        private int surfaceRaysWithHits;
        private int surfaceHitSamples;
        private int ddaVisits;
        private int importantSections;
        private int omittedSurfaceGroups;
        private int omittedImportantBlocks;
        private int omittedEntities;
        private final Map<String, Integer> omittedEntityGroups = new LinkedHashMap<>();
        private final Map<String, Integer> importantDirectionCounts = new LinkedHashMap<>();
        private final Map<String, Integer> entityDirectionCounts = new LinkedHashMap<>();
        private boolean truncated;
        private final Set<String> truncationReasons = new java.util.LinkedHashSet<>();
        private int minSurfaceDx = Integer.MAX_VALUE;
        private int minSurfaceDy = Integer.MAX_VALUE;
        private int minSurfaceDz = Integer.MAX_VALUE;
        private int maxSurfaceDx = Integer.MIN_VALUE;
        private int maxSurfaceDy = Integer.MIN_VALUE;
        private int maxSurfaceDz = Integer.MIN_VALUE;
        private int minImportantDx = Integer.MAX_VALUE;
        private int minImportantDy = Integer.MAX_VALUE;
        private int minImportantDz = Integer.MAX_VALUE;
        private int maxImportantDx = Integer.MIN_VALUE;
        private int maxImportantDy = Integer.MIN_VALUE;
        private int maxImportantDz = Integer.MIN_VALUE;
        private double minEntityDx = Double.POSITIVE_INFINITY;
        private double minEntityDy = Double.POSITIVE_INFINITY;
        private double minEntityDz = Double.POSITIVE_INFINITY;
        private double maxEntityDx = Double.NEGATIVE_INFINITY;
        private double maxEntityDy = Double.NEGATIVE_INFINITY;
        private double maxEntityDz = Double.NEGATIVE_INFINITY;

        private ScanAccumulator(EntityMaid maid, EnvironmentScanRequest request, Vec3 eye, BlockPos maidPos) {
            this.maid = maid;
            this.request = request;
            this.eye = eye;
            this.maidPos = maidPos;
            this.originX = maid.getX();
            this.originY = maid.getY();
            this.originZ = maid.getZ();
            this.originYaw = maid.getYRot();
            this.originTick = maid.level().getGameTime();
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
            int dx = pos.getX() - maidPos.getX();
            int dy = pos.getY() - maidPos.getY();
            int dz = pos.getZ() - maidPos.getZ();
            surfaceHitSamples++;
            surfaceDirectionSamples.merge(direction, 1, Integer::sum);
            minSurfaceDx = Math.min(minSurfaceDx, dx);
            minSurfaceDy = Math.min(minSurfaceDy, dy);
            minSurfaceDz = Math.min(minSurfaceDz, dz);
            maxSurfaceDx = Math.max(maxSurfaceDx, dx);
            maxSurfaceDy = Math.max(maxSurfaceDy, dy);
            maxSurfaceDz = Math.max(maxSurfaceDz, dz);
            String key = id + "|" + direction + "|" + opacity + "|" + Math.min(20, (int) distance / 4);
            SurfaceAggregate aggregate = surfaceMap.get(key);
            if (aggregate == null) {
                if (surfaceMap.size() >= MAX_SURFACE_GROUPS) {
                    if (omittedSurfaceKeys.add(key)) {
                        omittedSurfaceGroups++;
                    }
                    markTruncated("surface_group_limit");
                    return;
                }
                aggregate = new SurfaceAggregate(id.toString(), direction, opacity);
                surfaceMap.put(key, aggregate);
            }
            aggregate.count++;
            aggregate.nearest = Math.min(aggregate.nearest, distance);
            aggregate.farthest = Math.max(aggregate.farthest, distance);
            if (aggregate.representatives.size() < 3) {
                aggregate.representatives.add(new SurfaceBlockHit.Representative(
                        dx, dy, dz, distance, remainingBudget));
            }
        }

        private void addImportant(BlockState state, BlockPos pos, double distance, String direction,
                                  net.minecraft.world.level.block.entity.BlockEntity blockEntity) {
            ResourceLocation id = ForgeRegistries.BLOCKS.getKey(state.getBlock());
            if (id == null) {
                return;
            }
            int dx = pos.getX() - maidPos.getX();
            int dy = pos.getY() - maidPos.getY();
            int dz = pos.getZ() - maidPos.getZ();
            importantDirectionCounts.merge(direction, 1, Integer::sum);
            minImportantDx = Math.min(minImportantDx, dx);
            minImportantDy = Math.min(minImportantDy, dy);
            minImportantDz = Math.min(minImportantDz, dz);
            maxImportantDx = Math.max(maxImportantDx, dx);
            maxImportantDy = Math.max(maxImportantDy, dy);
            maxImportantDz = Math.max(maxImportantDz, dz);
            if (importantBlocks.size() >= MAX_IMPORTANT_BLOCKS) {
                omittedImportantBlocks++;
                markTruncated("important_block_limit");
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
            importantBlocks.add(new ImportantBlockHit(id.toString(), dx, dy, dz, distance, direction,
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
            double dx = center.x - originX;
            double dy = center.y - eye.y;
            double dz = center.z - originZ;
            double distance = Math.sqrt(dx * dx + dy * dy + dz * dz);
            String elevation = Math.abs(dy) < 1.0 ? "level" : dy > 0 ? "above" : "below";
            String visibilityName = visibility.fullyVisible ? "visible" : visibility.visible ? "partial" : "occluded";
            if (!visibility.visible && request.mode() != ScanMode.ENTITIES) {
                return;
            }
            recordEntityExtent(category, directionFor(this, center), dx, dy, dz);
            entities.add(new ScannedEntity(id.toString(), entity.getId(), customName, category, dx, dy, dz,
                    distance, directionFor(this, center), elevation, visibilityName, health, maxHealth, pose,
                    List.copyOf(states), itemId, itemCount));
        }

        private void omitEntity(Entity entity) {
            Vec3 center = entity.getBoundingBox().getCenter();
            double dx = center.x - originX;
            double dy = center.y - eye.y;
            double dz = center.z - originZ;
            String direction = directionFor(this, center);
            recordEntityExtent(entityCategory(entity), direction, dx, dy, dz);
            omittedEntities++;
            omittedEntityGroups.merge(entityCategory(entity) + "|" + direction, 1, Integer::sum);
        }

        private void omitEntity(ScannedEntity entity) {
            omittedEntities++;
            omittedEntityGroups.merge(entity.category() + "|" + entity.direction(), 1, Integer::sum);
        }

        private void recordEntityExtent(String category, String direction, double dx, double dy, double dz) {
            entityDirectionCounts.merge(direction, 1, Integer::sum);
            minEntityDx = Math.min(minEntityDx, dx);
            minEntityDy = Math.min(minEntityDy, dy);
            minEntityDz = Math.min(minEntityDz, dz);
            maxEntityDx = Math.max(maxEntityDx, dx);
            maxEntityDy = Math.max(maxEntityDy, dy);
            maxEntityDz = Math.max(maxEntityDz, dz);
        }

        private EnvironmentScanResult finish(String dimension) {
            List<SurfaceBlockHit> surfaces = new ArrayList<>();
            for (SurfaceAggregate aggregate : surfaceMap.values()) {
                surfaces.add(new SurfaceBlockHit(aggregate.id, aggregate.direction, aggregate.opacity,
                        aggregate.count, aggregate.nearest, aggregate.farthest, aggregate.representatives));
            }
            String focus = request.focus().toLowerCase(Locale.ROOT);
            Comparator<String> focusFirst = (left, right) -> {
                boolean leftMatch = VisionFocusMatcher.matches(focus, left);
                boolean rightMatch = VisionFocusMatcher.matches(focus, right);
                return Boolean.compare(rightMatch, leftMatch);
            };
            surfaces.sort(Comparator.comparing(SurfaceBlockHit::registryId, focusFirst)
                    .thenComparingDouble(SurfaceBlockHit::nearestDistance));
            importantBlocks.sort(Comparator.comparing(ImportantBlockHit::registryId, focusFirst)
                    .thenComparingDouble(ImportantBlockHit::distance));
            entities.sort(Comparator.comparing(ScannedEntity::registryId, focusFirst)
                    .thenComparingInt(entity -> entityPriority(entity.category(), entity.visibility()))
                    .thenComparingDouble(ScannedEntity::distance));
            if (entities.size() > MAX_ENTITIES) {
                for (int index = MAX_ENTITIES; index < entities.size(); index++) {
                    omitEntity(entities.get(index));
                }
                entities.subList(MAX_ENTITIES, entities.size()).clear();
                markTruncated("entity_detail_limit");
            }
            return new EnvironmentScanResult(originTick, dimension, request, primaryRays,
                    ddaVisits, importantSectionKeys.size(), surfaces, importantBlocks, entities, omittedSurfaceGroups,
                    omittedImportantBlocks, omittedEntities, omittedEntityGroups, surfaceHitSamples,
                    surfaceRaysWithHits, surfaceDirectionSamples, surfaceBounds(), importantDirectionCounts,
                    importantBounds(), entityDirectionCounts, entityBounds(), truncated,
                    List.copyOf(truncationReasons));
        }

        private void markTruncated(String reason) {
            truncated = true;
            if (reason != null && !reason.isBlank()) truncationReasons.add(reason);
        }

        private int[] surfaceBounds() {
            return surfaceHitSamples == 0 ? new int[0] : new int[]{minSurfaceDx, minSurfaceDy, minSurfaceDz,
                    maxSurfaceDx, maxSurfaceDy, maxSurfaceDz};
        }

        private int[] importantBounds() {
            return importantDirectionCounts.isEmpty() ? new int[0] : new int[]{minImportantDx, minImportantDy,
                    minImportantDz, maxImportantDx, maxImportantDy, maxImportantDz};
        }

        private double[] entityBounds() {
            return entityDirectionCounts.isEmpty() ? new double[0] : new double[]{minEntityDx, minEntityDy,
                    minEntityDz, maxEntityDx, maxEntityDy, maxEntityDz};
        }

        private static long sectionKey(BlockPos pos) {
            return sectionKey(pos.getX() >> 4, pos.getY() >> 4, pos.getZ() >> 4);
        }

        private static long sectionKey(int sectionX, int sectionY, int sectionZ) {
            long x = ((long) sectionX) & 0x3ffffffL;
            long z = ((long) sectionZ) & 0x3ffffffL;
            long y = ((long) sectionY) & 0xfffL;
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
        private final List<SurfaceBlockHit.Representative> representatives = new ArrayList<>();
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
