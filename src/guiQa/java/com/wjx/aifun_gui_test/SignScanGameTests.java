package com.wjx.aifun_gui_test;

import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import com.github.tartaricacid.touhoulittlemaid.init.InitEntities;
import com.google.gson.JsonParser;
import com.wjx.touhou_aifun.vision.scan.EnvironmentScanRequest;
import com.wjx.touhou_aifun.vision.scan.EnvironmentScanResult;
import com.wjx.touhou_aifun.vision.scan.ScanDirection;
import com.wjx.touhou_aifun.vision.scan.ScanMode;
import com.wjx.touhou_aifun.vision.scan.ScanArea;
import com.wjx.touhou_aifun.vision.scan.ScannedSign;
import com.wjx.touhou_aifun.vision.scan.ShallowEnvironmentScanner;
import com.wjx.touhou_aifun.vision.scan.VisionScanCache;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.WallSignBlock;
import net.minecraft.world.level.block.entity.SignBlockEntity;
import net.minecraft.world.level.block.entity.SignText;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;

import java.util.List;

/** Real server-world checks, opt-in with the existing -PguiQa fixture (excluded from the jar). */
@GameTestHolder("aifun_gui_test")
@PrefixGameTestTemplate(false)
public final class SignScanGameTests {
    @GameTest(template="empty",templateNamespace="aifun_gui_test")
    public static void observesUntaggedModBlockAboveCraftingTable(GameTestHelper helper) {
        EntityMaid maid = maid(helper);
        helper.setBlock(new BlockPos(2, 1, 4), Blocks.CRAFTING_TABLE);
        helper.setBlock(new BlockPos(2, 2, 4), GuiFixtureMod.MACHINE.get());
        BlockPos target = helper.absolutePos(new BlockPos(2, 2, 4));
        helper.assertTrue(helper.getLevel().getBlockEntity(target) == null, "Fixture has no block entity");
        var result = VisionScanCache.scan(maid, BLOCKS);
        helper.assertTrue(result.importantBlocks().stream().noneMatch(block -> block.registryId().equals("aifun_gui_test:machine")),
                "Ordinary machine is not rescued by old important-block heuristics");
        helper.assertTrue(result.observedBlocks().stream().anyMatch(block -> block.registryId().equals("aifun_gui_test:machine")
                && block.dx() + maid.blockPosition().getX() == target.getX()
                && block.dy() + maid.blockPosition().getY() == target.getY()), "Panorama retains exact ordinary mod block identity and coordinates");
        var compact = JsonParser.parseString(result.toCompactJson()).getAsJsonObject();
        helper.assertTrue(compact.getAsJsonArray("observed_blocks").asList().stream()
                .anyMatch(row -> row.getAsJsonObject().has("registry_id")
                        && row.getAsJsonObject().get("registry_id").getAsString().equals("aifun_gui_test:machine")),
                "Mod block survives summary compression without any tag");
        helper.succeed();
    }

    @GameTest(template="empty",templateNamespace="aifun_gui_test")
    public static void preciseAreaFindsModBlockAndAirAndPreservesSavedPagesAfterMovement(GameTestHelper helper) {
        EntityMaid maid = maid(helper);
        helper.setBlock(new BlockPos(2, 1, 4), Blocks.CRAFTING_TABLE);
        helper.setBlock(new BlockPos(2, 2, 4), GuiFixtureMod.MACHINE.get());
        BlockPos target = helper.absolutePos(new BlockPos(2, 2, 4));
        var area = new ScanArea(target.getX(), target.getY(), target.getZ(), target.getX() + 1,
                target.getY(), target.getZ(), "world");
        var request = new EnvironmentScanRequest(ScanMode.BLOCKS, ScanDirection.ALL, 5, "", "inspect_area", "summary", area, 0, 1, "");
        var result = VisionScanCache.scan(maid, request);
        helper.assertTrue(result.observedBlocks().size() == 2, "Every requested cell is enumerated");
        helper.assertTrue(result.observedBlocks().stream().anyMatch(block -> block.registryId().equals("aifun_gui_test:machine")), "Area does not filter ordinary mod blocks");
        helper.assertTrue(result.observedBlocks().stream().anyMatch(block -> block.status().equals("air")), "Visible empty cell is explicitly air");
        var first = JsonParser.parseString(result.toJson()).getAsJsonObject();
        helper.assertTrue(first.get("primary_rays").getAsInt() == 0, "Area inspection skips panorama");
        helper.assertTrue(first.get("has_more").getAsBoolean(), "Small page exposes continuation");
        helper.setBlock(new BlockPos(2, 2, 4), Blocks.AIR);
        maid.setPos(maid.getX() + 1, maid.getY(), maid.getZ());
        var next = VisionScanCache.scan(maid, new EnvironmentScanRequest(ScanMode.BOTH, ScanDirection.ALL, 20,
                "", "overview", "summary", null, first.get("next_offset").getAsInt(), 1, result.observationId()));
        var second = JsonParser.parseString(next.toJson()).getAsJsonObject();
        helper.assertTrue(first.get("observation_id").equals(second.get("observation_id")), "Pages keep one observation identity");
        helper.assertTrue(first.get("maid_position_at_scan_start").equals(second.get("maid_position_at_scan_start")), "Movement does not rewrite original origin");
        helper.assertTrue(next.observedBlocks().stream().anyMatch(block -> block.registryId().equals("aifun_gui_test:machine")), "Page read uses captured state even after block changes");
        var fresh = VisionScanCache.scan(maid, request);
        helper.assertTrue(fresh.observedBlocks().stream().noneMatch(block -> block.registryId().equals("aifun_gui_test:machine")), "Fresh precise scan sees changed state");
        helper.succeed();
    }

    @GameTest(template="empty",templateNamespace="aifun_gui_test")
    public static void preciseAreaDoesNotExposeOccludedIdentitiesOrClaimUnknownCellsAreAir(GameTestHelper helper) {
        EntityMaid maid = maid(helper);
        helper.setBlock(new BlockPos(2, 2, 4), GuiFixtureMod.MACHINE.get());
        for (BlockPos wall : BlockPos.betweenClosed(new BlockPos(0, 1, 3), new BlockPos(5, 4, 3))) helper.setBlock(wall, Blocks.STONE);
        BlockPos target = helper.absolutePos(new BlockPos(2, 2, 4));
        var area = new ScanArea(target.getX(), target.getY(), target.getZ(), target.getX() + 1, target.getY(), target.getZ(), "world");
        var result = VisionScanCache.scan(maid, new EnvironmentScanRequest(ScanMode.BLOCKS, ScanDirection.ALL, 5,
                "", "inspect_area", "full", area, 0, 32, ""));
        helper.assertTrue(result.observedBlocks().size() == 2, "Occluded cells are accounted for");
        helper.assertTrue(result.observedBlocks().stream().allMatch(block -> block.status().equals("occluded")
                && block.registryId().isEmpty() && block.properties().isEmpty()), "No identities or air claims through an opaque wall");
        var json = JsonParser.parseString(result.toJson()).getAsJsonObject();
        helper.assertTrue(json.get("area_enumeration_complete").getAsBoolean()
                && !json.get("area_visibility_complete").getAsBoolean(), "Enumeration and visual completeness are distinct");
        var shortRange = ShallowEnvironmentScanner.scan(maid, new EnvironmentScanRequest(ScanMode.BLOCKS, ScanDirection.ALL, 1,
                "", "inspect_area", "full", area, 0, 32, ""));
        helper.assertTrue(shortRange.observedBlocks().stream().allMatch(block -> block.status().equals("out_of_range")), "Distant region never bypasses radius");
        helper.succeed();
    }

    @GameTest(template="empty",templateNamespace="aifun_gui_test",timeoutTicks=100)
    public static void preciseAreaRunsIncrementallyAndDoesNotShareAnotherRegionCache(GameTestHelper helper) {
        EntityMaid maid = maid(helper);
        BlockPos min = helper.absolutePos(new BlockPos(0, 1, 0));
        var area = new ScanArea(min.getX(), min.getY(), min.getZ(), min.getX() + 5, min.getY() + 4, min.getZ() + 5, "world");
        var future = VisionScanCache.scanAsync(maid, new EnvironmentScanRequest(ScanMode.BLOCKS, ScanDirection.ALL, 10,
                "", "inspect_area", "summary", area, 0, 32, ""));
        helper.succeedWhen(() -> {
            helper.assertTrue(future.isDone(), "Incremental area scan completed");
            var result = future.join();
            helper.assertTrue(result.observedBlocks().size() == 180, "No cells dropped across tick boundaries");
            helper.assertTrue(result.completedTick() > result.gameTick(), "Work is spread across ticks");
            var single = new ScanArea(min.getX(), min.getY(), min.getZ(), min.getX(), min.getY(), min.getZ(), "world");
            var other = VisionScanCache.scan(maid, new EnvironmentScanRequest(ScanMode.BLOCKS, ScanDirection.ALL, 10,
                    "", "inspect_area", "full", single, 0, 32, ""));
            helper.assertTrue(other.observedBlocks().size() == 1 && !other.observationId().equals(result.observationId()), "Different bounds never reuse wrong cached geometry");
        });
    }

    @GameTest(template="empty",templateNamespace="aifun_gui_test")
    public static void preciseAreaDoesNotLoadChunksOrSharePagesBetweenMaids(GameTestHelper helper) {
        EntityMaid maid = maid(helper);
        var area = new ScanArea(0, 0, 0, 0, 0, 0, "maid_relative");
        var request = new EnvironmentScanRequest(ScanMode.BLOCKS, ScanDirection.ALL, 5, "", "inspect_area", "full", area, 0, 32, "");
        var saved = VisionScanCache.scan(maid, request);
        EntityMaid other = maid(helper);
        boolean rejected = false;
        try { VisionScanCache.scan(other, new EnvironmentScanRequest(ScanMode.BLOCKS, ScanDirection.ALL, 5,
                "", "overview", "full", null, 0, 32, saved.observationId())); }
        catch (IllegalArgumentException expected) { rejected = true; }
        helper.assertTrue(rejected, "Saved pages are bound to their maid");
        maid.setOwnerUUID(java.util.UUID.randomUUID());
        rejected = false;
        try { VisionScanCache.scan(maid, new EnvironmentScanRequest(ScanMode.BLOCKS, ScanDirection.ALL, 5,
                "", "overview", "full", null, 0, 32, saved.observationId())); }
        catch (IllegalArgumentException expected) { rejected = true; }
        helper.assertTrue(rejected, "Changed owner cannot access earlier saved pages");
        var owned = VisionScanCache.scan(maid, request);
        VisionScanCache.clearMaid(maid.getUUID());
        rejected = false;
        try { VisionScanCache.scan(maid, new EnvironmentScanRequest(ScanMode.BLOCKS, ScanDirection.ALL, 5,
                "", "overview", "full", null, 0, 32, owned.observationId())); }
        catch (IllegalArgumentException expected) { rejected = true; }
        helper.assertTrue(rejected, "Clearing maid memory clears saved scan pages");
        maid.setPos(1_000_000.5, maid.getY(), 1_000_000.5);
        helper.assertTrue(!helper.getLevel().hasChunkAt(maid.blockPosition()), "Destination is unloaded");
        int loaded = helper.getLevel().getChunkSource().getLoadedChunksCount();
        var result = ShallowEnvironmentScanner.scan(maid, request);
        helper.assertTrue(result.observedBlocks().size() == 1 && result.observedBlocks().get(0).status().equals("unloaded"), "Unloaded cell has no invented identity");
        helper.assertTrue(helper.getLevel().getChunkSource().getLoadedChunksCount() == loaded, "Precise inspection never loads chunks");
        helper.succeed();
    }

    @GameTest(batch="agentScanLatency",template="empty",templateNamespace="aifun_gui_test",timeoutTicks=120)
    public static void multipleMaidsFinishBoundedScansAtLowTickRate(GameTestHelper helper) {
        var scans=new java.util.ArrayList<java.util.concurrent.CompletableFuture<EnvironmentScanResult>>();
        for(int i=0;i<6;i++) {
            EntityMaid maid=maid(helper);
            scans.add(com.wjx.touhou_aifun.vision.scan.VisionScanScheduler.request(maid,
                    new EnvironmentScanRequest(ScanMode.BLOCKS,ScanDirection.ALL,5,"")));
        }
        boolean[] active={true};long started=System.nanoTime();
        helper.onEachTick(()-> {
            if(!active[0]) return;
            try { Thread.sleep(100); } catch(InterruptedException error) { Thread.currentThread().interrupt();active[0]=false;helper.fail("Scan fixture interrupted");return; }
            if(scans.stream().allMatch(java.util.concurrent.CompletableFuture::isDone)) {
                active[0]=false;
                helper.assertTrue(System.nanoTime()-started>=300_000_000L,"Fixture actually slowed ticks instead of advancing instantly");
                for(var future:scans) {
                    var json=JsonParser.parseString(future.join().toJson()).getAsJsonObject();
                    helper.assertTrue(json.get("completed_tick").getAsLong()>=json.get("game_tick").getAsLong(),"All maids retain start and completion ticks");
                    helper.assertTrue(!json.has("truncation_reasons") || !json.get("truncation_reasons").toString().contains("hard_timeout"),"Shared ray budget progresses fairly before bounded timeout");
                }
                helper.succeed();
            }
        });
    }

    @GameTest(template="empty",templateNamespace="aifun_gui_test")
    public static void unloadedMaidCancelsPendingScanWithoutPublishingObservation(GameTestHelper helper) {
        EntityMaid maid=maid(helper);
        var future=com.wjx.touhou_aifun.vision.scan.VisionScanScheduler.request(maid,BLOCKS);
        maid.discard();
        helper.runAfterDelay(1,()-> {
            helper.assertTrue(future.isCompletedExceptionally(),"Removed maid's unfinished observation cannot become usable evidence");
            helper.succeed();
        });
    }
    private static final EnvironmentScanRequest BLOCKS = new EnvironmentScanRequest(
            ScanMode.BLOCKS, ScanDirection.ALL, 5, "告示牌");

    @GameTest(template = "empty", templateNamespace = "aifun_gui_test")
    public static void readsStandingWallAndHangingSignsBothFaces(GameTestHelper helper) {
        EntityMaid maid = maid(helper);
        sign(helper, new BlockPos(1, 1, 4), Blocks.OAK_SIGN.defaultBlockState());
        helper.setBlock(new BlockPos(3, 2, 5), Blocks.STONE);
        sign(helper, new BlockPos(3, 2, 4), Blocks.OAK_WALL_SIGN.defaultBlockState()
                .setValue(WallSignBlock.FACING, Direction.NORTH));
        helper.setBlock(new BlockPos(4, 3, 4), Blocks.STONE);
        sign(helper, new BlockPos(4, 2, 4), Blocks.OAK_HANGING_SIGN.defaultBlockState());

        EnvironmentScanResult result = ShallowEnvironmentScanner.scan(maid, BLOCKS);
        helper.assertTrue(result.signTexts().size() == 3, "All three sign variants: " + result.toJson());
        for (ScannedSign text : result.signTexts()) {
            helper.assertTrue(text.frontLines().equals(List.of("正面", "", "第三行", "")), "Four front lines");
            helper.assertTrue(text.backLines().equals(List.of("背面", "", "", "第四行")), "Four back lines");
        }
        var wall=result.signTexts().stream().filter(s->s.registryId().equals("minecraft:oak_wall_sign")).findFirst().orElseThrow();
        var support=helper.absolutePos(new BlockPos(3,2,5));
        helper.assertTrue(wall.attachedBlock()!=null && wall.attachedBlock().registryId().equals("minecraft:stone")
                && wall.attachedBlock().x()==support.getX() && wall.attachedBlock().y()==support.getY() && wall.attachedBlock().z()==support.getZ(),
                "Wall sign reports its physical support position separately from the label");
        helper.assertTrue(result.signTexts().stream().filter(s->!s.registryId().equals("minecraft:oak_wall_sign")).allMatch(s->s.attachedBlock()==null),
                "Standing and ceiling signs do not invent a labelled destination");
        helper.assertTrue(!result.toJson().contains("run_command"), "No click actions in the tool result");
        helper.assertTrue(ShallowEnvironmentScanner.scan(maid,
                new EnvironmentScanRequest(ScanMode.ENTITIES, ScanDirection.ALL, 5, "")).signTexts().isEmpty(),
                "Entity-only scan does not read signs");
        helper.succeed();
    }

    @GameTest(template = "empty", templateNamespace = "aifun_gui_test")
    public static void respectsOcclusionRangeAndScanDirection(GameTestHelper helper) {
        EntityMaid maid = maid(helper);
        sign(helper, new BlockPos(2, 1, 4), Blocks.OAK_SIGN.defaultBlockState());
        helper.assertTrue(ShallowEnvironmentScanner.scan(maid, BLOCKS).signTexts().size() == 1,
                "Visible sign is read before adding a wall");
        EnvironmentScanRequest shortRange = new EnvironmentScanRequest(ScanMode.BLOCKS, ScanDirection.ALL, 1, "");
        helper.assertTrue(ShallowEnvironmentScanner.scan(maid, shortRange).signTexts().isEmpty(), "Outside scan radius");
        maid.setYRot(0); // Minecraft yaw 0 faces south, toward the sign.
        helper.assertTrue(ShallowEnvironmentScanner.scan(maid,
                new EnvironmentScanRequest(ScanMode.BLOCKS, ScanDirection.BACK, 5, "")).signTexts().isEmpty(),
                "Requested opposite face excludes the sign");
        for (BlockPos wall : BlockPos.betweenClosed(new BlockPos(0, 1, 3), new BlockPos(5, 4, 3))) {
            helper.setBlock(wall, Blocks.STONE);
        }
        helper.assertTrue(ShallowEnvironmentScanner.scan(maid, BLOCKS).signTexts().isEmpty(), "Opaque wall blocks sign text");
        helper.succeed();
    }

    @GameTest(template = "empty", templateNamespace = "aifun_gui_test")
    public static void readsFilteredTextWithoutOwnerAndNeverExportsClickEvents(GameTestHelper helper)
            throws ReflectiveOperationException {
        EntityMaid maid = maid(helper);
        SignBlockEntity sign = sign(helper, new BlockPos(2, 1, 4), Blocks.OAK_SIGN.defaultBlockState());
        Component raw = Component.literal("原文").withStyle(style -> style.withClickEvent(
                new ClickEvent(ClickEvent.Action.RUN_COMMAND, "/say hidden-command")));
        sign.setText(new SignText().setMessage(0, raw, Component.literal("过滤文字")), true);
        sign.setText(new SignText().setMessage(0, Component.literal("背面原文"), Component.literal("背面过滤")), false);
        ScannedSign filtered = ShallowEnvironmentScanner.scan(maid, BLOCKS).signTexts().get(0);
        helper.assertTrue(filtered.frontLines().get(0).equals("过滤文字"), "Missing owner uses filtered front text");
        helper.assertTrue(filtered.backLines().get(0).equals("背面过滤"), "Missing owner uses filtered back text");
        // Invoke the package-private reader without exporting a test-only API from the main mod.
        var read = Class.forName("com.wjx.touhou_aifun.vision.scan.SignTextReader").getDeclaredMethod("read",
                SignBlockEntity.class, String.class, int.class, int.class, int.class,
                double.class, String.class, boolean.class);
        read.setAccessible(true);
        ScannedSign unfiltered = (ScannedSign) read.invoke(null, sign, "minecraft:oak_sign", 0, 0, 0, 0, "front", false);
        helper.assertTrue(unfiltered.frontLines().get(0).equals("原文"), "Unfiltered mode reads plain raw text");
        helper.assertTrue(unfiltered.backLines().get(0).equals("背面原文"), "Unfiltered back text");
        helper.assertTrue(!new com.google.gson.Gson().toJson(unfiltered).contains("hidden-command"), "Component click event is never exported");
        helper.succeed();
    }

    @GameTest(template = "empty", templateNamespace = "aifun_gui_test", timeoutTicks = 100)
    public static void asyncScanCachesThenRefreshesEditedSign(GameTestHelper helper) {
        EntityMaid maid = maid(helper);
        SignBlockEntity sign = sign(helper, new BlockPos(2, 1, 4), Blocks.OAK_SIGN.defaultBlockState());
        VisionScanCache.invalidate(maid);
        var future = VisionScanCache.scanAsync(maid, BLOCKS);
        boolean[] edited = {false};
        var refresh = new java.util.concurrent.atomic.AtomicReference<
                java.util.concurrent.CompletableFuture<EnvironmentScanResult>>();
        helper.succeedWhen(() -> {
            helper.assertTrue(future.isDone(), "Scheduled scan completed");
            EnvironmentScanResult initial = future.join();
            helper.assertTrue(initial.signTexts().size() == 1, "Scheduled scan includes sign text");
            if (!edited[0]) {
                sign.setText(new SignText().setMessage(0, Component.literal("修改后")), true);
                helper.assertTrue(VisionScanCache.scan(maid, BLOCKS).signTexts().get(0).frontLines().get(0).equals("修改后"), "Sign update invalidates cache immediately");
                edited[0] = true;
            }
            helper.assertTrue(helper.getLevel().getGameTime() - initial.gameTick() > 10, "Cache expired");
            if (refresh.get() == null) refresh.set(VisionScanCache.scanAsync(maid, BLOCKS));
            helper.assertTrue(refresh.get().isDone(), "Scheduled refresh completed");
            EnvironmentScanResult current = refresh.get().join();
            helper.assertTrue(current.signTexts().get(0).frontLines().get(0).equals("修改后"), "Edited text is refreshed");
            helper.assertTrue(JsonParser.parseString(current.toCompactJson()).getAsJsonObject()
                    .getAsJsonArray("sign_texts").size() == 1, "Compact result keeps refreshed text");
        });
    }

    @GameTest(template = "empty", templateNamespace = "aifun_gui_test")
    public static void scanDoesNotLoadAnUnloadedChunk(GameTestHelper helper) {
        EntityMaid maid = maid(helper);
        maid.setPos(1_000_000.5, maid.getY(), 1_000_000.5);
        helper.assertTrue(!helper.getLevel().hasChunkAt(maid.blockPosition()), "Target chunk starts unloaded");
        int before = helper.getLevel().getChunkSource().getLoadedChunksCount();
        EnvironmentScanResult result = ShallowEnvironmentScanner.scan(maid, BLOCKS);
        helper.assertTrue(result.signTexts().isEmpty(), "No sign text from unloaded chunks");
        helper.assertTrue(helper.getLevel().getChunkSource().getLoadedChunksCount() == before, "Scan does not load chunks");
        helper.succeed();
    }

    @GameTest(template = "empty", templateNamespace = "aifun_gui_test")
    public static void readSignsSkipsPanoramaAndReadsFreshText(GameTestHelper helper) {
        EntityMaid maid = maid(helper);
        SignBlockEntity sign = sign(helper,new BlockPos(2,1,4),Blocks.OAK_SIGN.defaultBlockState());
        EnvironmentScanRequest request = new EnvironmentScanRequest(BLOCKS.mode(),BLOCKS.direction(),BLOCKS.maxDistance(),"","read_signs","full");
        var first = VisionScanCache.scan(maid,request);
        helper.assertTrue(!first.signTexts().isEmpty(),"Fast path finds sign");
        var json = JsonParser.parseString(first.toJson()).getAsJsonObject();
        helper.assertTrue(json.get("primary_rays").getAsInt()==0,"No full panorama rays");
        sign.setText(new SignText().setMessage(0,Component.literal("fresh")),true);
        var second = VisionScanCache.scan(maid,request);
        helper.assertTrue(second.signTexts().get(0).frontLines().get(0).equals("fresh"),"Fresh sign text, no stale cache");
        helper.succeed();
    }
    @GameTest(template="empty",templateNamespace="aifun_gui_test")
    public static void focusProjectionReusesGeometryButBlockUpdatesInvalidate(GameTestHelper helper) {
        EntityMaid maid=maid(helper);
        sign(helper,new BlockPos(2,1,4),Blocks.OAK_SIGN.defaultBlockState());
        var first=VisionScanCache.scan(maid,new EnvironmentScanRequest(ScanMode.BLOCKS,ScanDirection.ALL,5,"","overview","full"));
        var projected=VisionScanCache.scan(maid,new EnvironmentScanRequest(ScanMode.BLOCKS,ScanDirection.ALL,5,"sign","overview","summary"));
        helper.assertTrue(first.gameTick()==projected.gameTick() && first.completedTick()==projected.completedTick(),"Original observation age retained across focus/detail projection");
        helper.assertTrue(projected.signTexts().size()==1,"Projection retains authoritative signs");
        for(BlockPos wall:BlockPos.betweenClosed(new BlockPos(0,1,3),new BlockPos(5,4,3))) helper.setBlock(wall,Blocks.STONE);
        var changed=VisionScanCache.scan(maid,BLOCKS);
        helper.assertTrue(changed.signTexts().isEmpty(),"New occlusion invalidates cached geometry");
        helper.succeed();
    }
    private static EntityMaid maid(GameTestHelper helper) {
        for (BlockPos floor : BlockPos.betweenClosed(new BlockPos(0, 0, 0), new BlockPos(5, 0, 5))) {
            helper.setBlock(floor, Blocks.STONE);
        }
        EntityMaid maid = helper.spawn(InitEntities.MAID.get(), new BlockPos(2, 1, 1));
        maid.setNoAi(true);
        maid.setInvulnerable(true);
        return maid;
    }

    private static SignBlockEntity sign(GameTestHelper helper, BlockPos pos, BlockState state) {
        helper.setBlock(pos, state);
        SignBlockEntity sign = (SignBlockEntity) helper.getBlockEntity(pos);
        sign.setText(new SignText().setMessage(0, Component.literal("正面"))
                .setMessage(2, Component.literal("第三行")), true);
        sign.setText(new SignText().setMessage(0, Component.literal("背面"))
                .setMessage(3, Component.literal("第四行")), false);
        return sign;
    }
}
