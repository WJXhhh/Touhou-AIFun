package com.wjx.aifun_gui_test;

import com.github.tartaricacid.touhoulittlemaid.ai.manager.entity.LLMCallback;
import com.github.tartaricacid.touhoulittlemaid.init.InitEntities;
import com.google.gson.*;
import com.wjx.touhou_aifun.maid.gui.*;
import com.wjx.touhou_aifun.vision.scan.*;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.*;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.*;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.*;
import net.minecraftforge.gametest.*;
import java.nio.file.*;
import java.util.*;

/** Measures actual tool data in a fixed server fixture, excluding models, network and client rendering. */
@GameTestHolder("aifun_gui_test")
@PrefixGameTestTemplate(false)
public final class AgentPerformanceGameTests {
    @GameTest(template="empty",templateNamespace="aifun_gui_test")
    public static void fixedWorldGuiVolumeAndSignScanCosts(GameTestHelper helper) throws Exception {
        for(var floor:BlockPos.betweenClosed(new BlockPos(0,0,0),new BlockPos(5,0,5))) helper.setBlock(floor,Blocks.STONE);
        var maid=helper.spawn(InitEntities.MAID.get(),new BlockPos(2,1,2)); maid.setNoAi(true); maid.setInvulnerable(true);
        helper.setBlock(new BlockPos(1,1,1),Blocks.CHEST); var chest=(ChestBlockEntity)helper.getBlockEntity(new BlockPos(1,1,1)); chest.setItem(0,new ItemStack(Items.IRON_INGOT,64));
        var callback=new LLMCallback(maid.getAiChatManager(),new ArrayList<>(),true);
        var pos=helper.absolutePos(new BlockPos(1,1,1)); JsonObject open=new JsonObject(); open.addProperty("x",pos.getX());open.addProperty("y",pos.getY());open.addProperty("z",pos.getZ());open.addProperty("wait_policy","AUTO");
        var opened=MaidGuiSessionManager.call(callback,"open_gui","open_benchmark",open).join(); helper.assertTrue(!opened.has("error"),opened.toString());
        var session=MaidGuiSessionManager.session(maid.getUUID()); int backpack=-1;
        for(var slot:session.menu().slots) if(slot.container==session.actor.getInventory() && slot.mayPlace(new ItemStack(Items.IRON_INGOT))) { backpack=slot.index; break; }
        long baselineChars=0,deltaChars=0;
        for(int i=0;i<50;i++) {
            JsonObject action=new JsonObject();action.addProperty("session_id",session.id.toString());action.addProperty("action","transfer");action.addProperty("slot",0);action.addProperty("to_slot",backpack);action.addProperty("count",1);
            JsonObject delta=MaidGuiSessionManager.call(callback,"gui_action","benchmark_"+i,action).join();
            helper.assertTrue(delta.get("moved_count").getAsInt()==1,"Each measured transfer commits exactly once");
            deltaChars+=delta.toString().length(); baselineChars+=session.snapshot("ok",false).toString().length();
        }
        helper.assertTrue(chest.getItem(0).getCount()==14 && deltaChars*2<baselineChars,"Actual GUI delta results reduce redundant size by at least 50%");
        MaidGuiSessionManager.cancel(maid.getUUID(),"benchmark_finished");
        helper.setBlock(new BlockPos(2,1,4),Blocks.OAK_SIGN); var sign=(SignBlockEntity)helper.getBlockEntity(new BlockPos(2,1,4)); sign.setText(new SignText().setMessage(0,Component.literal("Fixture sign")),true);
        var overview=new EnvironmentScanRequest(ScanMode.BLOCKS,ScanDirection.ALL,5,"sign","overview","full");
        var fast=new EnvironmentScanRequest(ScanMode.BLOCKS,ScanDirection.ALL,5,"sign","read_signs","full");
        List<Double> oldTimes=new ArrayList<>(),newTimes=new ArrayList<>();
        for(int i=0;i<25;i++) {
            for(int pass=0;pass<2;pass++) {
                boolean optimized=(i+pass)%2==0; long started=System.nanoTime(); var result=ShallowEnvironmentScanner.scan(maid,optimized?fast:overview);
                double millis=(System.nanoTime()-started)/1_000_000d;
                helper.assertTrue(result.signTexts().size()==1 && result.signTexts().get(0).frontLines().get(0).equals("Fixture sign"),"Both paths read the same authoritative text");
                if(i>=5) (optimized?newTimes:oldTimes).add(millis);
            }
        }
        JsonObject report=new JsonObject(); report.addProperty("scope","Fixed-world tool fixture only: no live model, network, client capture or full task completion-rate measurement.");
        report.addProperty("gui_transfers",50);report.addProperty("gui_full_result_chars",baselineChars);report.addProperty("gui_delta_result_chars",deltaChars);
        report.addProperty("gui_result_reduction",1-deltaChars/(double)baselineChars);
        report.add("overview_scan",statistics(oldTimes)); report.add("read_signs_scan",statistics(newTimes));
        Files.writeString(Path.of("agent-tool-benchmark.json"),new GsonBuilder().setPrettyPrinting().create().toJson(report)); helper.succeed();
    }
    private static JsonObject statistics(List<Double> times) {
        var sorted=new ArrayList<>(times); Collections.sort(sorted); JsonObject out=new JsonObject();out.addProperty("samples",sorted.size());
        out.addProperty("p50_ms",(sorted.get(sorted.size()/2-1)+sorted.get(sorted.size()/2))/2);out.addProperty("p95_ms",sorted.get((int)Math.ceil(sorted.size()*.95)-1));return out;
    }
}
