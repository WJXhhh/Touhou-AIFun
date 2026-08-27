package com.wjx.touhou_aifun.vision.scan;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.nio.charset.StandardCharsets;

/** Result envelope deliberately kept bounded before it is put in an LLM tool message. */
public final class EnvironmentScanResult {
    private static final Gson GSON = new GsonBuilder().disableHtmlEscaping().create();

    private final long gameTick;
    private final String dimension;
    private final String mode;
    private final String direction;
    private final int maxDistance;
    private final int primaryRays;
    private final int ddaVisits;
    private final int importantSections;
    private final List<SurfaceBlockHit> surfaces;
    private final List<ImportantBlockHit> importantBlocks;
    private final List<ScannedEntity> entities;
    private final int omittedSurfaceGroups;
    private final int omittedImportantBlocks;
    private final int omittedEntities;
    private final Map<String, Integer> omittedEntityGroups;
    private final int surfaceHitSamples;
    private final int surfaceRaysWithHits;
    private final Map<String, Integer> surfaceDirectionSamples;
    private final int[] surfaceBounds;
    private final Map<String, Integer> importantDirectionCounts;
    private final int[] importantBounds;
    private final Map<String, Integer> entityDirectionCounts;
    private final double[] entityBounds;
    private final boolean truncated;
    private final List<String> truncationReasons;
    private final String focus;

    public EnvironmentScanResult(long gameTick, String dimension, EnvironmentScanRequest request,
                                 int primaryRays, int ddaVisits, int importantSections,
                                 List<SurfaceBlockHit> surfaces, List<ImportantBlockHit> importantBlocks,
                                 List<ScannedEntity> entities, int omittedSurfaceGroups,
                                 int omittedImportantBlocks, int omittedEntities,
                                 Map<String, Integer> omittedEntityGroups, int surfaceHitSamples,
                                 int surfaceRaysWithHits, Map<String, Integer> surfaceDirectionSamples,
                                 int[] surfaceBounds, Map<String, Integer> importantDirectionCounts,
                                 int[] importantBounds, Map<String, Integer> entityDirectionCounts,
                                 double[] entityBounds, boolean truncated) {
        this(gameTick, dimension, request, primaryRays, ddaVisits, importantSections, surfaces,
                importantBlocks, entities, omittedSurfaceGroups, omittedImportantBlocks, omittedEntities,
                omittedEntityGroups, surfaceHitSamples, surfaceRaysWithHits, surfaceDirectionSamples,
                surfaceBounds, importantDirectionCounts, importantBounds, entityDirectionCounts, entityBounds,
                truncated, truncated ? List.of("detail_or_work_limit") : List.of());
    }

    public EnvironmentScanResult(long gameTick, String dimension, EnvironmentScanRequest request,
                                 int primaryRays, int ddaVisits, int importantSections,
                                 List<SurfaceBlockHit> surfaces, List<ImportantBlockHit> importantBlocks,
                                 List<ScannedEntity> entities, int omittedSurfaceGroups,
                                 int omittedImportantBlocks, int omittedEntities,
                                 Map<String, Integer> omittedEntityGroups, int surfaceHitSamples,
                                 int surfaceRaysWithHits, Map<String, Integer> surfaceDirectionSamples,
                                 int[] surfaceBounds, Map<String, Integer> importantDirectionCounts,
                                 int[] importantBounds, Map<String, Integer> entityDirectionCounts,
                                 double[] entityBounds, boolean truncated, List<String> truncationReasons) {
        this.gameTick = gameTick;
        this.dimension = dimension;
        this.mode = request.mode().name().toLowerCase();
        this.direction = request.direction().name().toLowerCase();
        this.maxDistance = request.maxDistance();
        this.primaryRays = primaryRays;
        this.ddaVisits = ddaVisits;
        this.importantSections = importantSections;
        this.surfaces = Collections.unmodifiableList(new ArrayList<>(surfaces));
        this.importantBlocks = Collections.unmodifiableList(new ArrayList<>(importantBlocks));
        this.entities = Collections.unmodifiableList(new ArrayList<>(entities));
        this.omittedSurfaceGroups = omittedSurfaceGroups;
        this.omittedImportantBlocks = omittedImportantBlocks;
        this.omittedEntities = omittedEntities;
        this.omittedEntityGroups = Collections.unmodifiableMap(new LinkedHashMap<>(omittedEntityGroups));
        this.surfaceHitSamples = surfaceHitSamples;
        this.surfaceRaysWithHits = surfaceRaysWithHits;
        this.surfaceDirectionSamples = Collections.unmodifiableMap(new LinkedHashMap<>(surfaceDirectionSamples));
        this.surfaceBounds = surfaceBounds == null ? new int[0] : surfaceBounds.clone();
        this.importantDirectionCounts = Collections.unmodifiableMap(new LinkedHashMap<>(importantDirectionCounts));
        this.importantBounds = importantBounds == null ? new int[0] : importantBounds.clone();
        this.entityDirectionCounts = Collections.unmodifiableMap(new LinkedHashMap<>(entityDirectionCounts));
        this.entityBounds = entityBounds == null ? new double[0] : entityBounds.clone();
        this.truncated = truncated;
        this.truncationReasons = truncationReasons == null ? List.of()
                : List.copyOf(truncationReasons.stream().filter(reason -> reason != null && !reason.isBlank()).toList());
        this.focus = request.focus();
    }

    public List<SurfaceBlockHit> surfaces() {
        return surfaces;
    }

    public long gameTick() {
        return gameTick;
    }

    public List<ImportantBlockHit> importantBlocks() {
        return importantBlocks;
    }

    public List<ScannedEntity> entities() {
        return entities;
    }

    public boolean truncated() {
        return truncated;
    }

    /** Serialize with an explicit truncation marker; never silently emits an incomplete normal list. */
    public String toJson() {
        JsonObject root = new JsonObject();
        root.addProperty("status", "ok");
        root.addProperty("game_tick", gameTick);
        root.addProperty("dimension", dimension);
        root.addProperty("mode", mode);
        root.addProperty("direction", direction);
        root.addProperty("max_distance", maxDistance);
        root.addProperty("primary_rays", primaryRays);
        int expectedRays = expectedPrimaryRays();
        root.addProperty("expected_primary_rays", expectedRays);
        root.addProperty("unprocessed_primary_rays", Math.max(0, expectedRays - primaryRays));
        root.addProperty("dda_block_visits", ddaVisits);
        root.addProperty("important_sections", importantSections);
        if (!focus.isBlank()) {
            root.addProperty("focus", focus);
        }

        JsonArray surfaceArray = new JsonArray();
        for (SurfaceBlockHit hit : surfaces) {
            JsonObject value = new JsonObject();
            value.addProperty("registry_id", hit.registryId());
            value.addProperty("direction", hit.direction());
            value.addProperty("opacity", hit.opacity().name().toLowerCase());
            value.addProperty("count", hit.count());
            value.addProperty("nearest_distance", round(hit.nearestDistance()));
            value.addProperty("farthest_distance", round(hit.farthestDistance()));
            JsonArray representatives = new JsonArray();
            for (SurfaceBlockHit.Representative representative : hit.representatives()) {
                JsonObject sample = new JsonObject();
                addPosition(sample, representative.dx(), representative.dy(), representative.dz());
                sample.addProperty("distance", round(representative.distance()));
                sample.addProperty("remaining_visibility_budget", representative.remainingBudget());
                representatives.add(sample);
            }
            value.add("representatives", representatives);
            surfaceArray.add(value);
        }
        root.add("surface_blocks", surfaceArray);
        root.addProperty("surface_hit_samples", surfaceHitSamples);
        root.addProperty("surface_rays_with_hits", surfaceRaysWithHits);
        root.addProperty("surface_coverage_estimate", primaryRays == 0 ? 0.0
                : round(Math.min(1.0, surfaceRaysWithHits / (double) primaryRays)));
        addCounts(root, "surface_direction_samples", surfaceDirectionSamples);
        addBounds(root, "surface_bounds", surfaceBounds);

        JsonArray importantArray = new JsonArray();
        for (ImportantBlockHit hit : importantBlocks) {
            JsonObject value = new JsonObject();
            value.addProperty("registry_id", hit.registryId());
            addPosition(value, hit.dx(), hit.dy(), hit.dz());
            value.addProperty("distance", round(hit.distance()));
            value.addProperty("direction", hit.direction());
            JsonObject properties = new JsonObject();
            hit.properties().forEach(properties::addProperty);
            value.add("state", properties);
            if (hit.blockEntityType() != null && !hit.blockEntityType().isBlank()) {
                value.addProperty("block_entity_type", hit.blockEntityType());
            }
            importantArray.add(value);
        }
        root.add("important_blocks", importantArray);
        addCounts(root, "important_direction_counts", importantDirectionCounts);
        addBounds(root, "important_bounds", importantBounds);

        JsonArray entityArray = new JsonArray();
        for (ScannedEntity entity : entities) {
            JsonObject value = new JsonObject();
            value.addProperty("registry_id", entity.registryId());
            value.addProperty("entity_id", entity.entityId());
            if (entity.customName() != null && !entity.customName().isBlank()) {
                value.addProperty("name", entity.customName());
            }
            value.addProperty("category", entity.category());
            value.addProperty("dx", round(entity.dx()));
            value.addProperty("dy", round(entity.dy()));
            value.addProperty("dz", round(entity.dz()));
            value.addProperty("distance", round(entity.distance()));
            value.addProperty("direction", entity.direction());
            value.addProperty("elevation", entity.elevation());
            value.addProperty("visibility", entity.visibility());
            if (entity.health() != null) {
                value.addProperty("health", round(entity.health()));
                value.addProperty("max_health", round(entity.maxHealth()));
            }
            if (entity.pose() != null && !entity.pose().isBlank()) {
                value.addProperty("pose", entity.pose());
            }
            if (!entity.states().isEmpty()) {
                JsonArray states = new JsonArray();
                entity.states().forEach(states::add);
                value.add("states", states);
            }
            if (entity.itemId() != null && !entity.itemId().isBlank()) {
                value.addProperty("item_id", entity.itemId());
                value.addProperty("item_count", entity.itemCount());
            }
            entityArray.add(value);
        }
        root.add("entities", entityArray);
        addCounts(root, "entity_direction_counts", entityDirectionCounts);
        addBounds(root, "entity_bounds", entityBounds);

        root.addProperty("truncated", truncated);
        addReasons(root);
        if (omittedSurfaceGroups > 0 || omittedImportantBlocks > 0 || omittedEntities > 0) {
            JsonObject omitted = new JsonObject();
            omitted.addProperty("surface_groups", omittedSurfaceGroups);
            omitted.addProperty("important_blocks", omittedImportantBlocks);
            omitted.addProperty("entities", omittedEntities);
            if (!omittedEntityGroups.isEmpty()) {
                JsonObject groups = new JsonObject();
                omittedEntityGroups.forEach(groups::addProperty);
                omitted.add("entity_groups", groups);
            }
            root.add("omitted", omitted);
        }

        String json = GSON.toJson(root);
        // Defensive final bound. Keep a valid JSON envelope rather than chopping bytes mid-object.
        if (json.getBytes(StandardCharsets.UTF_8).length <= 16 * 1024) {
            return json;
        }
        return toCompactJson();
    }

    /** Always-small summary used when this scan is embedded beside a visual-model response. */
    public String toCompactJson() {
        JsonObject compact = new JsonObject();
        compact.addProperty("status", "ok");
        compact.addProperty("truncated", true);
        compact.addProperty("reason", "compact scan summary; detailed entries were omitted");
        compact.addProperty("game_tick", gameTick);
        compact.addProperty("dimension", dimension);
        int expectedRays = expectedPrimaryRays();
        compact.addProperty("primary_rays", primaryRays);
        compact.addProperty("expected_primary_rays", expectedRays);
        compact.addProperty("unprocessed_primary_rays", Math.max(0, expectedRays - primaryRays));
        addReasons(compact);
        compact.addProperty("surface_groups", surfaces.size() + omittedSurfaceGroups);
        compact.addProperty("surface_hit_samples", surfaceHitSamples);
        compact.addProperty("surface_rays_with_hits", surfaceRaysWithHits);
        compact.addProperty("surface_coverage_estimate", primaryRays == 0 ? 0.0
                : round(Math.min(1.0, surfaceRaysWithHits / (double) primaryRays)));
        addCounts(compact, "surface_direction_samples", surfaceDirectionSamples);
        addBounds(compact, "surface_bounds", surfaceBounds);
        compact.addProperty("important_blocks", importantBlocks.size() + omittedImportantBlocks);
        addCounts(compact, "important_direction_counts", importantDirectionCounts);
        addBounds(compact, "important_bounds", importantBounds);
        compact.addProperty("entities", entities.size() + omittedEntities);
        addCounts(compact, "entity_direction_counts", entityDirectionCounts);
        addBounds(compact, "entity_bounds", entityBounds);
        if (!omittedEntityGroups.isEmpty()) {
            JsonObject groups = new JsonObject();
            omittedEntityGroups.forEach(groups::addProperty);
            compact.add("omitted_entity_groups", groups);
        }
        String json = GSON.toJson(compact);
        if (json.getBytes(StandardCharsets.UTF_8).length <= 16 * 1024) return json;

        // Absolute boundary: even hostile/invalid resource identifiers or oversized aggregate-map
        // keys must not escape the tool result limit.
        JsonObject emergency = new JsonObject();
        emergency.addProperty("status", "ok");
        emergency.addProperty("truncated", true);
        emergency.addProperty("reason", "scan summary exceeded 16 KiB; all variable text was omitted");
        emergency.addProperty("game_tick", gameTick);
        emergency.addProperty("primary_rays", primaryRays);
        emergency.addProperty("expected_primary_rays", expectedRays);
        emergency.addProperty("unprocessed_primary_rays", Math.max(0, expectedRays - primaryRays));
        emergency.addProperty("surface_groups", surfaces.size() + omittedSurfaceGroups);
        emergency.addProperty("important_blocks", importantBlocks.size() + omittedImportantBlocks);
        emergency.addProperty("entities", entities.size() + omittedEntities);
        return GSON.toJson(emergency);
    }

    private int expectedPrimaryRays() {
        if ("entities".equals(mode) || "none".equals(mode)) return 0;
        return "all".equals(direction) ? 6 * 40 * 40 : 40 * 40;
    }

    private void addReasons(JsonObject root) {
        if (truncationReasons.isEmpty()) return;
        JsonArray reasons = new JsonArray();
        truncationReasons.forEach(reasons::add);
        root.add("truncation_reasons", reasons);
    }

    private static void addCounts(JsonObject root, String name, Map<String, Integer> counts) {
        if (counts.isEmpty()) return;
        JsonObject object = new JsonObject();
        counts.forEach(object::addProperty);
        root.add(name, object);
    }

    private static void addBounds(JsonObject root, String name, int[] bounds) {
        if (bounds.length != 6) return;
        JsonObject value = new JsonObject();
        JsonArray min = new JsonArray();
        min.add(bounds[0]);
        min.add(bounds[1]);
        min.add(bounds[2]);
        JsonArray max = new JsonArray();
        max.add(bounds[3]);
        max.add(bounds[4]);
        max.add(bounds[5]);
        value.add("min_relative", min);
        value.add("max_relative", max);
        root.add(name, value);
    }

    private static void addBounds(JsonObject root, String name, double[] bounds) {
        if (bounds.length != 6) return;
        JsonObject value = new JsonObject();
        JsonArray min = new JsonArray();
        min.add(round(bounds[0]));
        min.add(round(bounds[1]));
        min.add(round(bounds[2]));
        JsonArray max = new JsonArray();
        max.add(round(bounds[3]));
        max.add(round(bounds[4]));
        max.add(round(bounds[5]));
        value.add("min_relative", min);
        value.add("max_relative", max);
        root.add(name, value);
    }

    private static void addPosition(JsonObject object, int dx, int dy, int dz) {
        JsonArray position = new JsonArray();
        position.add(dx);
        position.add(dy);
        position.add(dz);
        object.add("relative_position", position);
    }

    private static double round(double value) {
        return Math.round(value * 10.0) / 10.0;
    }
}
