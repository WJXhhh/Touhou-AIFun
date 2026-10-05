package com.wjx.aifun_gui_test;

import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import com.github.tartaricacid.touhoulittlemaid.init.InitEntities;
import com.google.gson.JsonParser;
import com.wjx.touhou_aifun.vision.scan.EnvironmentScanRequest;
import com.wjx.touhou_aifun.vision.scan.EnvironmentScanResult;
import com.wjx.touhou_aifun.vision.scan.ScanDirection;
import com.wjx.touhou_aifun.vision.scan.ScanMode;
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
