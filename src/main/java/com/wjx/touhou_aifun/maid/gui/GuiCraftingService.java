package com.wjx.touhou_aifun.maid.gui;

import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import com.google.gson.*;
import com.wjx.touhou_aifun.chat.ChatFlowManager;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.*;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.*;
import net.minecraftforge.common.crafting.IShapedRecipe;
import net.minecraftforge.registries.ForgeRegistries;

import java.util.*;
import java.util.concurrent.CompletableFuture;

/** Live RecipeManager queries and bounded workbench operations using real ingredient and menu rules. */
public final class GuiCraftingService {
    private GuiCraftingService() { }
    private static final int MAX_SEARCH = 20_000;
    private static final java.util.concurrent.ConcurrentMap<Object,String> RECIPE_VERSIONS =
            new com.google.common.collect.MapMaker().weakKeys().makeMap();
    private static final java.util.concurrent.atomic.AtomicLong TAG_VERSION = new java.util.concurrent.atomic.AtomicLong();
    static { net.minecraftforge.common.MinecraftForge.EVENT_BUS.addListener(GuiCraftingService::tagsUpdated); }
    private static void tagsUpdated(net.minecraftforge.event.TagsUpdatedEvent ignored) { TAG_VERSION.incrementAndGet(); }
    private static String version(Recipe<?> recipe) {
        return RECIPE_VERSIONS.computeIfAbsent(recipe, ignored -> UUID.randomUUID().toString())+":"+TAG_VERSION.get();
    }
    public static String recipeVersion(EntityMaid maid,String id) {
        return maid.level().getRecipeManager().byKey(new ResourceLocation(id)).map(GuiCraftingService::version).orElse("");
    }
    private static void checkRecipeEvidence(MaidGuiSession session,String id) {
        if(session.callback instanceof com.wjx.touhou_aifun.chat.agent.TaskCallback task)task.checkRecipeEvidence(id);
    }

    public static JsonObject query(EntityMaid maid, JsonObject request) {
        String requestedItem = MaidGuiSessionManager.string(request, "item", "");
        String item = requestedItem;
        String recipeId = MaidGuiSessionManager.string(request, "recipe_id", "");
        if (item.isBlank() && recipeId.isBlank()) throw new IllegalArgumentException("item_or_recipe_id_required");
        int offset = MaidGuiSessionManager.number(request, "offset", 0, 0, Integer.MAX_VALUE);
        int limit = MaidGuiSessionManager.number(request, "limit", 4, 1, 8);
        int count = MaidGuiSessionManager.number(request, "count", 1, 1, 4096);
        var level = maid.level();
        String requestedType = MaidGuiSessionManager.string(request, "recipe_type", "all");
        var candidates = new ArrayList<Recipe<?>>();
        for (RecipeType<?> type : List.of(RecipeType.CRAFTING, RecipeType.SMELTING, RecipeType.BLASTING, RecipeType.SMOKING, RecipeType.CAMPFIRE_COOKING)) {
            String typeId = net.minecraft.core.registries.BuiltInRegistries.RECIPE_TYPE.getKey(type).toString();
            if (requestedType.equals("all") || requestedType.equals(typeId))
                level.getRecipeManager().getRecipes().stream().filter(r -> r.getType() == type).forEach(candidates::add);
        }
        if (!Set.of("all", "minecraft:crafting", "minecraft:smelting", "minecraft:blasting", "minecraft:smoking", "minecraft:campfire_cooking").contains(requestedType))
            throw new IllegalArgumentException("unsupported_recipe_type");
        ItemResolution resolution = resolveItem(maid, item, candidates);
        item = resolution.item();
        JsonObject result = new JsonObject();
        result.addProperty("status", "ok");
        result.addProperty("source", "live_server_recipe_manager");
        result.addProperty("recipe_type", requestedType);
        result.addProperty("requested_count", count);
        if (!requestedItem.isBlank()) result.addProperty("requested_item", requestedItem);
        if (resolution.ambiguous()) {
            result.add("item_candidates", resolution.candidates());
            result.add("recipes", new JsonArray());
            result.addProperty("total_recipes", 0);
            result.addProperty("has_more", false);
            result.addProperty("notice", "Multiple registered items match the owner's phrase. Choose one item_id from item_candidates and query again; no recipe was guessed.");
            return result;
        }
        if (resolution.fuzzy()) {
            result.addProperty("resolved_item", resolution.item());
            result.addProperty("resolved_from", "fuzzy_name");
            result.add("item_candidates", resolution.candidates());
        }
        String resolvedItem = item;
        var recipes = candidates.stream()
                .filter(recipe -> recipeId.isBlank() || recipe.getId().toString().equals(recipeId))
                .filter(recipe -> resolvedItem.isBlank() || MaidGuiSession.itemId(recipe.getResultItem(level.registryAccess())).equals(resolvedItem))
                .sorted(Comparator.comparing(recipe -> recipe.getId().toString())).toList();
        result.addProperty("total_recipes", recipes.size());
        String notice = "Current loaded crafting and cooking recipes, including mod/datapack replacements. Each row identifies recipe_type and station. Cooking recipes require the actual furnace/smoker/blast furnace/campfire; craft_recipe is workbench-only. Fuel is separate from ingredients. Inventory availability is live. Query again after a recipe reload.";
        if (resolution.fuzzy() && recipes.isEmpty()) {
            notice += " The fuzzy owner phrase resolved to " + resolution.item() + ", but it has no loaded crafting or cooking recipe; inspect another source or use the exact item id.";
        } else if (resolution.fuzzy()) {
            notice += " The requested item id was resolved from the owner's fuzzy phrase; verify resolved_item before crafting.";
        }
        result.addProperty("notice", notice);
        JsonArray rows = new JsonArray();
        int end = Math.min(offset, recipes.size());
        for (; end < recipes.size() && rows.size() < limit; end++) {
            Recipe<?> recipe = recipes.get(end);
            JsonObject row = new JsonObject();
            row.addProperty("recipe_id", recipe.getId().toString());
            row.addProperty("recipe_type", net.minecraft.core.registries.BuiltInRegistries.RECIPE_TYPE.getKey(recipe.getType()).toString());
            row.addProperty("recipe_version", version(recipe));
            ItemStack output = recipe.getResultItem(level.registryAccess());
            row.add("output", MaidGuiSession.stack(output));
            if (!output.isEmpty()) row.addProperty("crafts_for_requested_count", (count + output.getCount() - 1) / output.getCount());
            JsonArray ingredients = new JsonArray();
            try {
                if (recipe instanceof AbstractCookingRecipe cooking) {
                    row.addProperty("station", recipe.getType() == RecipeType.SMELTING ? "minecraft:furnace" : recipe.getType() == RecipeType.BLASTING ? "minecraft:blast_furnace" : recipe.getType() == RecipeType.SMOKING ? "minecraft:smoker" : "minecraft:campfire");
                    row.addProperty("cooking_time_ticks", cooking.getCookingTime());
                    row.addProperty("experience", cooking.getExperience());
                    row.addProperty("auto_fill_supported", false);
                    row.addProperty("operation", "Use actual station GUI input/fuel slots and wait_gui; never craft_recipe for cooking.");
                    for (Ingredient input : cooking.getIngredients()) {
                        var ingredient = new JsonObject(); ingredient.add("definition", input.toJson());
                        var alternatives = input.getItems(); var examples = new JsonArray();
                        for (int n = 0; n < Math.min(8, alternatives.length); n++) examples.add(MaidGuiSession.itemId(alternatives[n]));
                        ingredient.add("accepted_examples", examples); ingredient.addProperty("accepted_example_count", alternatives.length);
                        ingredient.addProperty("examples_truncated", alternatives.length > 8);
                        ingredient.addProperty("matching_backpack_count", backpack(maid).stream().filter(input::test).mapToInt(ItemStack::getCount).sum());
                        ingredients.add(ingredient);
                    }
                } else {
                    CraftingRecipe crafting = (CraftingRecipe) recipe;
                    row.addProperty("station", "minecraft:crafting_table");
                    List<Cell> cells = layout(crafting);
                    row.addProperty("auto_fill_supported", true);
                    for (Cell cell : cells) {
                        JsonObject ingredient = new JsonObject();
                        ingredient.addProperty("grid_slot", cell.slot());
                        JsonElement definition = cell.ingredient().toJson();
                        if (definition.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8).length <= 512) ingredient.add("definition", definition);
                        else ingredient.addProperty("definition_omitted", true);
                        JsonArray examples = new JsonArray();
                        ItemStack[] alternatives = cell.ingredient().getItems();
                        for (int n = 0; n < Math.min(8, alternatives.length); n++) examples.add(MaidGuiSession.itemId(alternatives[n]));
                        ingredient.add("accepted_examples", examples);
                        ingredient.addProperty("accepted_example_count", alternatives.length);
                        ingredient.addProperty("examples_truncated", alternatives.length > 8);
                        ingredient.addProperty("matching_backpack_count", backpack(maid).stream().filter(cell.ingredient()::test).mapToInt(ItemStack::getCount).sum());
                        ingredients.add(ingredient);
                    }
                    Plan plan = plan(crafting, backpack(maid), maid);
                    boolean active = false;
                    if (plan != null) {
                        var selected = maid.level().getRecipeManager().getRecipeFor(RecipeType.CRAFTING, sampleGrid(plan.grid()), maid.level());
                        active = selected.isPresent() && selected.get().getId().equals(recipe.getId());
                        if (!active && selected.isPresent()) row.addProperty("shadowed_by_recipe", selected.get().getId().toString());
                    }
                    row.addProperty("can_fill_one_craft", active);
                }
                row.add("output_backpack_state",MaidBackpackSpace.describe(maid.getAvailableBackpackInv(),output,output.getCount()));
            } catch (IllegalArgumentException unsupported) {
                row.addProperty("auto_fill_supported", false);
                row.addProperty("reason", unsupported.getMessage());
            }
            row.add("ingredients", ingredients);
            rows.add(row);
            if (rows.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8).length > 12 * 1024 && rows.size() > 1) {
                rows.remove(rows.size() - 1); break;
            }
        }
        result.add("recipes", rows);
        result.addProperty("has_more", end < recipes.size());
        if (end < recipes.size()) result.addProperty("next_offset", end);
        return result;
    }

    /**
     * Resolves an exact item id or a fuzzy owner phrase against the loaded registry. A fuzzy phrase
     * is auto-accepted only when exactly one matching registered item has a currently loaded recipe
     * (or only one item matches at all); several equally plausible items return candidates instead
     * of guessing, so the executor can ask or choose explicitly.
     */
    private static ItemResolution resolveItem(EntityMaid maid, String item, List<Recipe<?>> candidates) {
        if (item == null || item.isBlank()) {
            return new ItemResolution("", false, false, null);
        }
        ResourceLocation exact = ResourceLocation.tryParse(item);
        if (exact != null && ForgeRegistries.ITEMS.containsKey(exact)) {
            return new ItemResolution(item, false, false, null);
        }
        Set<String> outputIds = new HashSet<>();
        for (Recipe<?> recipe : candidates) {
            ItemStack output = recipe.getResultItem(maid.level().registryAccess());
            if (!output.isEmpty()) outputIds.add(MaidGuiSession.itemId(output));
        }
        List<RegisteredItemSearch.Match> matches = RegisteredItemSearch.search(item, 8);
        if (matches.isEmpty()) {
            throw new IllegalArgumentException("unknown_item");
        }
        JsonArray rows = itemCandidateRows(matches, outputIds);
        List<RegisteredItemSearch.Match> craftable = matches.stream()
                .filter(match -> outputIds.contains(match.id().toString())).toList();
        if (craftable.size() == 1) {
            return new ItemResolution(craftable.get(0).id().toString(), true, false, rows);
        }
        if (craftable.size() > 1) {
            return new ItemResolution("", true, true, rows);
        }
        if (matches.size() == 1) {
            return new ItemResolution(matches.get(0).id().toString(), true, false, rows);
        }
        return new ItemResolution("", true, true, rows);
    }

    private static JsonArray itemCandidateRows(List<RegisteredItemSearch.Match> matches, Set<String> outputIds) {
        JsonArray rows = new JsonArray();
        for (RegisteredItemSearch.Match match : matches) {
            JsonObject row = new JsonObject();
            row.addProperty("item_id", match.id().toString());
            row.addProperty("display_name", match.displayName());
            row.addProperty("has_recipe", outputIds.contains(match.id().toString()));
            rows.add(row);
        }
        return rows;
    }

    private record ItemResolution(String item, boolean fuzzy, boolean ambiguous, JsonArray candidates) {
    }

    private static CraftingRecipe recipe(EntityMaid maid, String id) {
        var recipe = maid.level().getRecipeManager().byKey(new ResourceLocation(id)).orElseThrow(() -> new IllegalArgumentException("recipe_not_found_query_again"));
        if (!(recipe instanceof CraftingRecipe crafting)) throw new IllegalArgumentException("not_a_workbench_recipe");
        layout(crafting);
        return crafting;
    }

    private record Cell(int slot, Ingredient ingredient) { }
    private record Plan(List<ItemStack> grid, ItemStack output, List<ItemStack> remainders) { }

    private static List<Cell> layout(CraftingRecipe recipe) {
        if (!recipe.canCraftInDimensions(3, 3)) throw new IllegalArgumentException("recipe_does_not_fit_workbench");
        var ingredients = recipe.getIngredients();
        if (ingredients.isEmpty() || ingredients.size() > 9) throw new IllegalArgumentException("special_recipe_has_no_declared_grid");
        int width = recipe instanceof IShapedRecipe<?> shaped ? shaped.getRecipeWidth() : 3;
        int height = recipe instanceof IShapedRecipe<?> shaped ? shaped.getRecipeHeight() : (ingredients.size() + 2) / 3;
        if (width < 1 || width > 3 || height < 1 || height > 3) throw new IllegalArgumentException("recipe_does_not_fit_workbench");
        List<Cell> cells = new ArrayList<>();
        for (int i = 0; i < ingredients.size(); i++) if (!ingredients.get(i).isEmpty()) {
            cells.add(new Cell(1 + i / width * 3 + i % width, ingredients.get(i)));
        }
        if (cells.isEmpty()) throw new IllegalArgumentException("special_recipe_has_no_declared_grid");
        return cells;
    }

    private static List<ItemStack> backpack(EntityMaid maid) {
        List<ItemStack> contents = new ArrayList<>();
        var inventory = maid.getAvailableBackpackInv();
        for (int i = 0; i < inventory.getSlots(); i++) contents.add(inventory.getStackInSlot(i).copy());
        return contents;
    }

    private static Plan plan(CraftingRecipe recipe, List<ItemStack> available, EntityMaid maid) {
        List<Cell> cells = new ArrayList<>(layout(recipe));
        cells.sort(Comparator.comparingInt(cell -> (int) available.stream().filter(stack -> !stack.isEmpty() && cell.ingredient().test(stack)).count()));
        List<ItemStack> grid = new ArrayList<>(Collections.nCopies(9, ItemStack.EMPTY));
        int[] search = {0};
        if (!assign(cells, 0, available, grid, search)) return null;
        CraftingContainer container = sampleGrid(grid);
        if (!recipe.matches(container, maid.level())) throw new IllegalArgumentException("declared_layout_does_not_match_recipe");
        ItemStack output = recipe.assemble(container, maid.level().registryAccess());
        if (output.isEmpty()) throw new IllegalArgumentException("recipe_assembled_empty_output");
        return new Plan(List.copyOf(grid), output, recipe.getRemainingItems(container).stream().map(ItemStack::copy).toList());
    }

    private static boolean assign(List<Cell> cells, int index, List<ItemStack> available, List<ItemStack> grid, int[] search) {
        if (++search[0] > MAX_SEARCH) throw new IllegalArgumentException("ingredient_allocation_search_limit");
        if (index == cells.size()) return true;
        Cell cell = cells.get(index);
        Set<String> tried = new HashSet<>();
        for (ItemStack stack : available) {
            if (stack.isEmpty() || !cell.ingredient().test(stack)) continue;
            // Equal stacks in different backpack slots are interchangeable for this one unit.
            if (!tried.add(stack.copyWithCount(1).save(new net.minecraft.nbt.CompoundTag()).toString())) continue;
            ItemStack unit = stack.copyWithCount(1);
            stack.shrink(1); grid.set(cell.slot() - 1, unit);
            if (assign(cells, index + 1, available, grid, search)) return true;
            stack.grow(1); grid.set(cell.slot() - 1, ItemStack.EMPTY);
        }
        return false;
    }

    private static CraftingContainer sampleGrid(List<ItemStack> grid) {
        var dummy = new AbstractContainerMenu(null, -1) {
            @Override public ItemStack quickMoveStack(Player player, int slot) { return ItemStack.EMPTY; }
            @Override public boolean stillValid(Player player) { return false; }
        };
        CraftingContainer container = new TransientCraftingContainer(dummy, 3, 3);
        for (int i = 0; i < 9; i++) container.setItem(i, grid.get(i).copy());
        return container;
    }

    private static void check(MaidGuiSession session) {
        if (!(session.menu() instanceof CraftingMenu)) throw new IllegalArgumentException("not_a_workbench");
        if (!session.menu().getCarried().isEmpty()) throw new IllegalArgumentException("cursor_not_empty");
        if (session.maid.isRemoved() || !session.maid.isAlive() || !session.menu().stillValid(session.actor)
                || !Objects.equals(session.owner, session.maid.getOwnerUUID())
                || ChatFlowManager.isSuperseded(session.maid.getUUID(), session.callback)) throw new IllegalArgumentException("crafting_session_unavailable");
        if (session.callback instanceof com.wjx.touhou_aifun.chat.agent.TaskCallback task
                && (task.stopped() || !task.task.pendingAmendment.isBlank())) throw new IllegalArgumentException("crafting_task_stopped_or_amended");
        for (int i = 0; i <= 9; i++) MaidGuiSessionManager.checkObserved(session, i);
        var inventory = session.maid.getAvailableBackpackInv();
        for (int i = 0; i < inventory.getSlots(); i++) if (!session.observedBackpack.containsKey(i)
                || !ItemStack.matches(session.observedBackpack.get(i), inventory.getStackInSlot(i))) throw new IllegalArgumentException("stale_slots_reinspect");
    }

    /** Simulate actual IItemHandler slot permissions; never drop items to make room. */
    private static boolean insert(EntityMaid maid, List<ItemStack> inventory, ItemStack item) {
        ItemStack remaining = item.copy();
        var handler = maid.getAvailableBackpackInv();
        for (int i = 0; i < inventory.size() && !remaining.isEmpty(); i++) {
            ItemStack stored = inventory.get(i);
            if (!handler.isItemValid(i, remaining) || !stored.isEmpty() && !ItemStack.isSameItemSameTags(stored, remaining)) continue;
            int amount = Math.min(remaining.getCount(), Math.min(handler.getSlotLimit(i), remaining.getMaxStackSize()) - stored.getCount());
            if (amount <= 0) continue;
            if (stored.isEmpty()) inventory.set(i, remaining.copyWithCount(amount)); else stored.grow(amount);
            remaining.shrink(amount);
        }
        return remaining.isEmpty();
    }

    private static void depositCursor(MaidGuiSession session) {
        var inventory = session.maid.getAvailableBackpackInv();
        ItemStack remaining = session.menu().getCarried();
        for (int i = 0; i < inventory.getSlots() && !remaining.isEmpty(); i++) remaining = inventory.insertItem(i, remaining, false);
        session.menu().setCarried(remaining);
        if (!remaining.isEmpty()) throw new StorageFailure(MaidBackpackSpace.failure(MaidBackpackSpace.describe(inventory,remaining,remaining.getCount()),"backpack_full_cursor_retained","backpack_rejects_cursor_item"));
    }

    private static Plan prepare(MaidGuiSession session, CraftingRecipe recipe, boolean collecting) {
        check(session);
        List<ItemStack> available = backpack(session.maid);
        for (int i = 1; i <= 9; i++) {
            var stack=session.menu().getSlot(i).getItem();
            var storage=MaidBackpackSpace.describe(session.maid.getAvailableBackpackInv(),available,stack,stack.getCount());
            if(!insert(session.maid,available,stack))throw new StorageFailure(MaidBackpackSpace.failure(storage,"backpack_has_no_room_to_clear_grid","backpack_rejects_grid_item"));
        }
        Plan plan = plan(recipe, available, session.maid);
        if (plan == null) throw new IllegalArgumentException("missing_recipe_ingredients");
        // assign consumed the simulated ingredients. Reserve room for the output AND all native
        // remaining items before moving anything, including buckets or reusable mod tools.
        var outputStorage=MaidBackpackSpace.describe(session.maid.getAvailableBackpackInv(),available,plan.output(),plan.output().getCount());
        if (collecting && !insert(session.maid, available, plan.output()))
            throw new StorageFailure(MaidBackpackSpace.failure(outputStorage,"backpack_full_for_crafting_output","backpack_rejects_crafting_output"));
        for (ItemStack remainder : plan.remainders()) {
            var storage=MaidBackpackSpace.describe(session.maid.getAvailableBackpackInv(),available,remainder,remainder.getCount());
            if(!insert(session.maid,available,remainder))throw new StorageFailure(MaidBackpackSpace.failure(storage,"backpack_full_for_recipe_remainders","backpack_rejects_recipe_remainder"));
        }
        for (int i = 1; i <= 9; i++) if (!session.menu().getSlot(i).getItem().isEmpty()) {
            if (!session.menu().getSlot(i).mayPickup(session.actor)) throw new IllegalArgumentException("grid_slot_pickup_denied");
            session.menu().clicked(i, 0, ClickType.PICKUP, session.actor); depositCursor(session);
        }
        var inventory = session.maid.getAvailableBackpackInv();
        for (int i = 0; i < 9; i++) {
            ItemStack required = plan.grid().get(i);
            if (required.isEmpty()) continue;
            if (!session.menu().getSlot(i + 1).mayPlace(required)) throw new IllegalArgumentException("grid_slot_rejected_ingredient");
            boolean moved = false;
            for (int slot = 0; slot < inventory.getSlots(); slot++) {
                if (!ItemStack.isSameItemSameTags(required, inventory.getStackInSlot(slot))) continue;
                ItemStack extracted = inventory.extractItem(slot, 1, false);
                if (extracted.isEmpty()) continue;
                session.menu().setCarried(extracted);
                session.menu().clicked(i + 1, 0, ClickType.PICKUP, session.actor);
                if (!session.menu().getCarried().isEmpty()) { depositCursor(session); throw new IllegalArgumentException("ingredient_placement_rejected"); }
                moved = true; break;
            }
            if (!moved) throw new IllegalArgumentException("ingredients_changed_during_fill");
        }
        var grid = (CraftingContainer) session.menu().getSlot(1).container;
        var active = session.maid.level().getRecipeManager().getRecipeFor(RecipeType.CRAFTING, grid, session.maid.level());
        if (active.isEmpty() || !active.get().getId().equals(recipe.getId())) throw new IllegalArgumentException("recipe_shadowed_by_another_recipe");
        if (!ItemStack.matches(plan.output(), session.menu().getSlot(0).getItem())) throw new IllegalArgumentException("unexpected_recipe_output_reinspect");
        session.menu().broadcastChanges();
        return plan;
    }

    public static JsonObject fill(MaidGuiSession session, JsonObject request) {
        checkRecipeEvidence(session,MaidGuiSessionManager.string(request,"recipe_id",""));
        CraftingRecipe recipe = recipe(session.maid, MaidGuiSessionManager.string(request, "recipe_id", ""));
        Plan plan = prepare(session, recipe, false);
        JsonObject result = session.snapshot("ok");
        result.addProperty("recipe_id", recipe.getId().toString()); result.addProperty("crafts_filled", 1);
        result.add("recipe_output", MaidGuiSession.stack(plan.output()));
        return result;
    }

    public static CompletableFuture<JsonObject> start(MaidGuiSession session, JsonObject request) {
        check(session);
        checkRecipeEvidence(session,MaidGuiSessionManager.string(request,"recipe_id",""));
        CraftingRecipe recipe = recipe(session.maid, MaidGuiSessionManager.string(request, "recipe_id", ""));
        int count = MaidGuiSessionManager.number(request, "count", 1, 1, 4096);
        MaidGuiSessionManager.checkTaskTransferAllowance(session,MaidGuiSession.itemId(recipe.getResultItem(session.maid.level().registryAccess())),count,true,false);
        session.crafting = new CraftJob(recipe, count);
        return session.crafting.future;
    }

    public static final class CraftJob {
        private final CraftingRecipe recipe;
        private final int requested;
        private final CompletableFuture<JsonObject> future = new CompletableFuture<>();
        private int crafted, crafts;
        private String outputId = "";
        private CraftJob(CraftingRecipe recipe, int requested) { this.recipe = recipe; this.requested = requested; }
        public void tick(MaidGuiSession session) {
            try {
                checkRecipeEvidence(session,recipe.getId().toString());
                if (recipe(session.maid, recipe.getId().toString()) != recipe) throw new IllegalArgumentException("recipes_reloaded_query_again");
                Plan plan = prepare(session, recipe, true);
                if (!outputId.isEmpty() && !outputId.equals(MaidGuiSession.itemId(plan.output()))) throw new IllegalArgumentException("recipe_output_changed");
                outputId = MaidGuiSession.itemId(plan.output());
                if (!session.menu().getSlot(0).mayPickup(session.actor)) throw new IllegalArgumentException("recipe_output_pickup_denied");
                MaidGuiSessionManager.checkTaskTransferAllowance(session,outputId,plan.output().getCount(),true,true);
                // ResultSlot.onTake consumes materials, returns containers and invokes Forge/mod hooks.
                session.menu().clicked(0, 0, ClickType.PICKUP, session.actor);
                crafts++;
                ItemStack taken = session.menu().getCarried().copy();
                if (!ItemStack.matches(taken, plan.output())) throw new IllegalArgumentException("unexpected_crafted_stack_cursor_retained");
                try { depositCursor(session); }
                finally {
                    int received = taken.getCount() - session.menu().getCarried().getCount();
                    crafted += received;
                    if (received > 0) MaidGuiSessionManager.recordCraftedOutput(session, outputId, received);
                }
                session.lastAction = "craft_recipe";
                session.snapshot("ok"); // Capture our own changes; later external changes still fail freshness checks.
                if (crafted >= requested) finish(session, "completed", "");
            } catch (StorageFailure failure) { finish(session,"partial",failure.getMessage(),failure.detail); }
            catch (RuntimeException failure) { finish(session, "partial", failure.getMessage()); }
        }
        public void cancel(MaidGuiSession session, String reason) { finish(session, "partial", reason); }
        private void finish(MaidGuiSession session, String status, String error) {
            finish(session,status,error,null);
        }
        private void finish(MaidGuiSession session,String status,String error,JsonObject storageFailure) {
            session.crafting = null;
            JsonObject result = session.snapshot(status);
            result.addProperty("recipe_id", recipe.getId().toString()); result.addProperty("requested_count", requested);
            result.addProperty("crafted_count", crafted); result.addProperty("crafts_executed", crafts);
            result.addProperty("surplus_count", Math.max(0, crafted - requested)); result.addProperty("crafted_item", outputId);
            result.addProperty("batch_notice", "count is newly delivered output for this call; native recipe batches may produce surplus, reported explicitly. Retries must subtract crafted_count.");
            if (error != null && !error.isBlank()) result.addProperty("error", error);
            if(storageFailure!=null)for(var entry:storageFailure.entrySet())if(!entry.getKey().equals("status"))result.add(entry.getKey(),entry.getValue());
            future.complete(result);
        }
    }
    static final class StorageFailure extends IllegalArgumentException {
        final JsonObject detail;
        StorageFailure(JsonObject detail){super(detail.get("error").getAsString());this.detail=detail;}
    }
}
