package com.wjx.aifun_gui_test;

import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import com.github.tartaricacid.touhoulittlemaid.init.InitEntities;
import com.wjx.touhou_aifun.vision.scan.*;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.level.block.Blocks;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;

/** Same incremental state machine as production; no timers or network calls. */
@GameTestHolder("aifun_gui_test")
@PrefixGameTestTemplate(false)
public final class ScanBudgetGameTests {
    @GameTest(batch="scanBudget",template="empty",templateNamespace="aifun_gui_test")
    public static void insufficientCandidateBudgetYieldsAndResumesWithoutLosingEvidence(GameTestHelper helper) throws Exception {
        EntityMaid maid=helper.spawn(InitEntities.MAID.get(),new BlockPos(2,1,2));maid.setNoAi(true);
        var local=new BlockPos(2,1,4);helper.setBlock(local,Blocks.CRAFTING_TABLE);var target=helper.absolutePos(local);
        var begin=ShallowEnvironmentScanner.class.getDeclaredMethod("begin",EntityMaid.class,EnvironmentScanRequest.class);begin.setAccessible(true);
        var job=begin.invoke(null,maid,new EnvironmentScanRequest(ScanMode.BLOCKS,ScanDirection.ALL,8,""));
        var step=job.getClass().getDeclaredMethod("step",int.class,int.class,int.class);step.setAccessible(true);
        var visits=job.getClass().getDeclaredMethod("ddaVisits");visits.setAccessible(true);
        var result=job.getClass().getDeclaredMethod("result");result.setAccessible(true);
        // Start at section candidates; primary rays and block entities have separate budgets.
        for(String name:new String[]{"surfaceDone","blockEntitiesDone"}) {
            var field=job.getClass().getDeclaredField(name);field.setAccessible(true);field.setBoolean(job,true);
        }
        try {
            helper.assertTrue(!(boolean)step.invoke(job,0,1,4),"A visibility candidate must defer when its allowance is insufficient");
            helper.assertTrue(!(boolean)step.invoke(job,0,1,4) && (int)visits.invoke(job)==0,"Retry on an already started section yields again rather than spinning within the tick");
            boolean done=false;for(int i=0;i<80 && !done;i++)done=(boolean)step.invoke(job,0,40_000,4);
            helper.assertTrue(done,"Restoring the allowance completes the deferred scan");
            helper.assertTrue(((EnvironmentScanResult)result.invoke(job)).importantBlocks().stream().anyMatch(block->block.registryId().equals("minecraft:crafting_table")
                    && block.dx()+maid.blockPosition().getX()==target.getX() && block.dy()+maid.blockPosition().getY()==target.getY()
                    && block.dz()+maid.blockPosition().getZ()==target.getZ()),"The deferred candidate remains eligible and its actual evidence is retained");
            helper.succeed();
        } finally {maid.discard();}
    }
}
