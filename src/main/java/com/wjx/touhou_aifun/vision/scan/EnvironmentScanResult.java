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
    private final boolean truncated;
    private final String focus;

    public EnvironmentScanResult(long gameTick, String dimension, EnvironmentScanRequest request,
                                 int primaryRays, int ddaVisits, int importantSections,
                                 List<SurfaceBlockHit> surfaces, List<ImportantBlockHit> importantBlocks,
                                 List<ScannedEntity> entities, int omittedSurfaceGroups,
                                 int omittedImportantBlocks, int omittedEntities, boolean truncated) {
        this(gameTick, dimension, request, primaryRays, ddaVisits, importantSections, surfaces, importantBlocks,
                entities, omittedSurfaceGroups, omittedImportantBlocks, omittedEntities, Map.of(), truncated);
    }

    public EnvironmentScanResult(long gameTick, String dimension, EnvironmentScanRequest request,
                                 int primaryRays, int ddaVisits, int importantSections,
                                 List<SurfaceBlockHit> surfaces, List<ImportantBlockHit> importantBlocks,
                                 List<ScannedEntity> entities, int omittedSurfaceGroups,
                                 int omittedImportantBlocks, int omittedEntities,
                                 Map<String, Integer> omittedEntityGroups, boolean truncated) {
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
        this.truncated = truncated;
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
        root.addProperty("dda_block_visits", ddaVisits);
        root.addProperty("important_sections", importantSections);
        if (!focus.isBlank()) {
            root.addProperty("focus", focus);
        }

        JsonArray surfaceArray = new JsonArray();
        int surfaceHitSamples = 0;
        for (SurfaceBlockHit hit : surfaces) {
            JsonObject value = new JsonObject();
            value.addProperty("registry_id", hit.registryId());
            addPosition(value, hit.dx(), hit.dy(), hit.dz());
            value.addProperty("distance", round(hit.distance()));
            value.addProperty("direction", hit.direction());
            value.addProperty("opacity", hit.opacity().name().toLowerCase());
            value.addProperty("remaining_visibility_budget", hit.remainingBudget());
            value.addProperty("count", hit.count());
            value.addProperty("farthest_distance", round(hit.farthestDistance()));
            surfaceHitSamples += hit.count();
            surfaceArray.add(value);
        }
        root.add("surface_blocks", surfaceArray);
        root.addProperty("surface_hit_samples", surfaceHitSamples);
        root.addProperty("surface_coverage_estimate", primaryRays == 0 ? 0.0
                : round(Math.min(1.0, surfaceHitSamples / (double) primaryRays)));

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

        root.addProperty("truncated", truncated);
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
        if (json.length() <= 16 * 1024) {
            return json;
        }
        JsonObject compact = new JsonObject();
        compact.addProperty("status", "ok");
        compact.addProperty("truncated", true);
        compact.addProperty("reason", "result exceeded 16 KiB; detailed entries were omitted");
        compact.addProperty("game_tick", gameTick);
        compact.addProperty("dimension", dimension);
        compact.addProperty("surface_groups", surfaces.size() + omittedSurfaceGroups);
        compact.addProperty("important_blocks", importantBlocks.size() + omittedImportantBlocks);
        compact.addProperty("entities", entities.size() + omittedEntities);
        if (!omittedEntityGroups.isEmpty()) {
            JsonObject groups = new JsonObject();
            omittedEntityGroups.forEach(groups::addProperty);
            compact.add("omitted_entity_groups", groups);
        }
        return GSON.toJson(compact);
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
