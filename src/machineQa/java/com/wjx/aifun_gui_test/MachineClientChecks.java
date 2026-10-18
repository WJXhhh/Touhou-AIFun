package com.wjx.aifun_gui_test;

import com.github.tartaricacid.touhoulittlemaid.ai.manager.entity.LLMCallback;
import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import com.github.tartaricacid.touhoulittlemaid.init.InitEntities;
import com.google.gson.*;
import com.wjx.touhou_aifun.maid.gui.*;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.*;
import net.minecraft.world.level.block.Blocks;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.common.capabilities.ForgeCapabilities;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.registries.ForgeRegistries;
import net.minecraft.resources.ResourceLocation;

import java.nio.file.*;
import java.util.*;
import java.util.concurrent.CompletableFuture;

/** Actual optional mods, actual integrated client/screens, public GUI operations; no per-machine adapters. */
@Mod.EventBusSubscriber(modid="aifun_gui_test",value=Dist.CLIENT)
public final class MachineClientChecks {
    private static final String[] MACHINES={"ironfurnaces:iron_furnace","mekanism:energized_smelter","thermal:machine_furnace"};
    private static int ticks,index,calls,stage;
    private static boolean loaded,started,finished,busy;
    private static UUID owner;
    private static EntityMaid maid;
    private static LLMCallback callback;
    private static BlockPos target;
    private static long start;
    private static MachineLiveRun live;
    private static final JsonArray results=new JsonArray();
    @SubscribeEvent public static void clientTick(TickEvent.ClientTickEvent event) {
        if(!Boolean.getBoolean("aifun.machineQa.autorun") || event.phase!=TickEvent.Phase.END || finished)return;
        var mc=Minecraft.getInstance();ticks++;
        if(!loaded && ticks>100 && mc.screen!=null){loaded=true;mc.createWorldOpenFlows().loadLevel(mc.screen,"AIFun-Machine-QA");}
        if(!started && loaded && mc.player!=null && mc.getSingleplayerServer()!=null && ticks>180){
            started=true;owner=mc.player.getUUID();mc.options.pauseOnLostFocus=false;
            mc.getSingleplayerServer().execute(()->{try{setup();}catch(Throwable failure){finish(failure);}});
        }
        if(ticks>18000)finish(new AssertionError("Machine client timeout"));
    }
    private static void setup() {
        var player=Minecraft.getInstance().getSingleplayerServer().getPlayerList().getPlayer(owner);var level=player.serverLevel();
        player.setGameMode(net.minecraft.world.level.GameType.CREATIVE);player.setHealth(player.getMaxHealth());
        player.teleportTo(player.getX(),120,player.getZ());
        target=player.blockPosition().offset(2,0,0);
        for(var pos:BlockPos.betweenClosed(target.offset(-3,-1,-3),target.offset(3,-1,3)))level.setBlockAndUpdate(pos,Blocks.STONE.defaultBlockState());
        for(var pos:BlockPos.betweenClosed(target.offset(-3,0,-3),target.offset(3,4,3)))level.setBlockAndUpdate(pos,Blocks.AIR.defaultBlockState());
        var block=ForgeRegistries.BLOCKS.getValue(new ResourceLocation(MACHINES[index]));require(block!=null && block!=Blocks.AIR,"Actual optional machine is installed: "+MACHINES[index]);
        level.setBlockAndUpdate(target,block.defaultBlockState());
        maid=InitEntities.MAID.get().create(level);maid.moveTo(target.getX()+.5,target.getY(),target.getZ()+1.5,0,0);
        maid.setOwnerUUID(owner);maid.setTame(true);maid.setNoAi(true);maid.setInvulnerable(true);level.addFreshEntity(maid);
        maid.getMaidInv().setStackInSlot(0,new ItemStack(Items.RAW_IRON,3));if(index==0)maid.getMaidInv().setStackInSlot(1,new ItemStack(Items.COAL));
        callback=new LLMCallback(maid.getAiChatManager(),new ArrayList<>(),true);stage=0;start=level.getGameTime();busy=false;
    }
    @SubscribeEvent public static void serverTick(TickEvent.ServerTickEvent event) {
        if(!started || finished || maid==null || event.phase!=TickEvent.Phase.END)return;
        try {
            var level=maid.level();var tile=level.getBlockEntity(target);
            maid.setAirSupply(300);maid.setHealth(maid.getMaxHealth());
            if(tile!=null)for(var side:net.minecraft.core.Direction.values())
                tile.getCapability(ForgeCapabilities.ENERGY,side).ifPresent(energy->energy.receiveEnergy(1_000_000,false));
            if(tile!=null && level.getGameTime()%200==0){
                var diagnostic=new JsonObject();diagnostic.addProperty("machine",MACHINES[index]);diagnostic.addProperty("stage",stage);
                diagnostic.addProperty("tile",tile.saveWithFullMetadata().toString());
                var session=MaidGuiSessionManager.session(maid.getUUID());if(session!=null)diagnostic.add("menu",session.snapshot("qa_diagnostic",false));
                Files.writeString(Path.of("machine-progress.json"),diagnostic.toString());
            }
            if(level.getGameTime()-start>7200)throw new AssertionError("Machine stalled: "+MACHINES[index]+" stage="+stage);
            if(busy || level.getGameTime()-start<80)return;
            if(stage==0 && System.getProperty("aifun.machineQa.sites")!=null){live=new MachineLiveRun(maid,MACHINES[index],target,index==0);stage=3;live.start();return;}
            if(stage==3){
                if(!live.done())return;
                results.add(live.verify());live.close();live=null;maid.discard();index++;
                if(index==MACHINES.length)finish(null);else setup();return;
            }
            if(stage==0){
                busy=true;var args=new JsonObject();args.addProperty("target",MACHINES[index]);args.addProperty("x",target.getX());args.addProperty("y",target.getY());args.addProperty("z",target.getZ());args.addProperty("wait_policy","UNTIL_GOAL");
                call("open_gui",args).thenCompose(opened->{check(opened);require(opened.get("visual_inspection_required").getAsBoolean(),"Unknown machine requires actual GUI observation");return insert(0,Items.RAW_IRON,3);})
                        .thenCompose(blocked->{require(blocked.has("error") && blocked.get("error").getAsString().equals("unfamiliar_machine_inspect_gui_visual_first"),"Blind machine mutation is rejected");
                            require(maid.getAvailableBackpackInv().getStackInSlot(0).getCount()==3,"Rejected blind operation leaves materials intact");
                            var capture=args();capture.addProperty("visual",true);return call("inspect_gui",capture);})
                        .thenCompose(frame->{check(frame);require(frame.has("_gui_image"),"Actual native GUI captured");saveFrame(frame,"before");
                            try{Files.writeString(Path.of("machine-"+index+"-before.json"),frame.toString().replaceAll("\"_gui_image\":\"[^\"]*\",?",""));}catch(Exception error){throw new RuntimeException(error);}
                            return insert(0,Items.RAW_IRON,3);})
                        .thenCompose(result->{check(result);return index==0?insert(1,Items.COAL,1):CompletableFuture.completedFuture(result);})
                        .thenCompose(result->{check(result);var capture=args();capture.addProperty("visual",true);return call("inspect_gui",capture);})
                        .thenAccept(frame->{check(frame);saveFrame(frame,"running");stage=1;busy=false;}).exceptionally(failure->{finish(failure);return null;});
            } else if(stage==1){
                var session=MaidGuiSessionManager.session(maid.getUUID());
                for(int slot=0;slot<session.menu().slots.size();slot++)if(!MaidGuiSlots.belongsTo(session.menu().getSlot(slot), session.actor.getInventory()) && session.menu().getSlot(slot).getItem().is(Items.IRON_INGOT)){
                    busy=true;stage=2;var wait=args();wait.addProperty("item","minecraft:iron_ingot");wait.addProperty("count",3);wait.addProperty("output_slot",slot);wait.addProperty("max_wait_seconds",90);
                    call("wait_gui",wait).thenAccept(result->{
                        check(result);int iron=0,raw=0;var bag=maid.getAvailableBackpackInv();for(int n=0;n<bag.getSlots();n++){var item=bag.getStackInSlot(n);if(item.is(Items.IRON_INGOT))iron+=item.getCount();if(item.is(Items.RAW_IRON))raw+=item.getCount();}
                        require(iron==3 && raw==0 && result.get("delivered_count").getAsInt()==3,"Actual input consumed and exact output delivered: "+result);
                        require(maid.getMainHandItem().isEmpty() && maid.getOffhandItem().isEmpty(),"Hands unchanged");
                        var row=new JsonObject();row.addProperty("machine",MACHINES[index]);row.addProperty("outputs",iron);row.addProperty("inputs_remaining",raw);row.addProperty("ticks",level.getGameTime()-start);row.addProperty("completed",true);results.add(row);
                        try{Files.writeString(Path.of("machine-fixture-progress.json"),results.toString());}catch(Exception failure){throw new RuntimeException(failure);}
                        MaidGuiSessionManager.finish(callback);maid.discard();index++;if(index==MACHINES.length)finish(null);else setup();
                    }).exceptionally(failure->{finish(failure);return null;});break;
                }
            }
        } catch(Throwable failure){finish(failure);}
    }
    private static CompletableFuture<JsonObject> insert(int backpack,Item item,int count) {
        var session=MaidGuiSessionManager.session(maid.getUUID());int source=-1,destination=-1;
        for(int n=0;n<session.menu().slots.size();n++){
            var slot=session.menu().getSlot(n);
            if(MaidGuiSlots.inventorySlot(slot,session.actor.getInventory())==backpack+1)source=n;
            if(destination<0 && !MaidGuiSlots.belongsTo(slot,session.actor.getInventory()) && slot.mayPlace(new ItemStack(item)))destination=n;
        }
        require(source>=0 && destination>=0,"Native slots accept actual backpack material");
        var args=args();args.addProperty("action","transfer");args.addProperty("slot",source);args.addProperty("to_slot",destination);args.addProperty("count",count);
        return call("gui_action",args);
    }
    private static JsonObject args(){var args=new JsonObject();args.addProperty("session_id",MaidGuiSessionManager.session(maid.getUUID()).id.toString());return args;}
    private static CompletableFuture<JsonObject> call(String tool,JsonObject args){return MaidGuiSessionManager.call(callback,tool,"machine_"+(calls++),args);}
    private static void saveFrame(JsonObject frame,String name){try{String image=frame.get("_gui_image").getAsString();Files.write(Path.of("machine-"+index+"-"+name+".jpg"),Base64.getDecoder().decode(image.substring(image.indexOf(',')+1)));}catch(Exception failure){throw new RuntimeException(failure);}}
    private static void check(JsonObject result){require(!result.has("error"),result.toString());}
    private static void require(boolean value,String reason){if(!value)throw new AssertionError(reason);}
    private static void finish(Throwable failure){
        if(finished)return;finished=true;var report=new JsonObject();report.addProperty("completed",failure==null);report.add("machines",results);
        report.addProperty("mode",System.getProperty("aifun.machineQa.sites")==null?"public_tool_fixture":"real_model_native_gui");
        if(live!=null)try{live.close();}catch(Exception ignored){}
        if(failure!=null){report.addProperty("failure",failure.toString());failure.printStackTrace();}
        try{Files.writeString(Path.of("machine-client-result.json"),new GsonBuilder().setPrettyPrinting().create().toJson(report));}catch(Exception ignored){}
        System.out.println("MACHINE_QA "+report);Minecraft.getInstance().execute(()->Minecraft.getInstance().stop());
    }
}
