package com.wjx.aifun_gui_test;

import com.github.tartaricacid.touhoulittlemaid.ai.manager.entity.LLMCallback;
import com.github.tartaricacid.touhoulittlemaid.ai.service.llm.Role;
import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import com.github.tartaricacid.touhoulittlemaid.init.InitEntities;
import com.google.gson.*;
import com.mojang.authlib.GameProfile;
import com.wjx.touhou_aifun.compat.ai.action.GuiTool;
import com.wjx.touhou_aifun.maid.gui.*;
import moze_intel.projecte.api.capabilities.*;
import moze_intel.projecte.api.event.PlayerAttemptLearnEvent;
import moze_intel.projecte.utils.EMCHelper;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.*;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.*;
import net.minecraft.world.item.enchantment.*;
import net.minecraft.world.level.block.Blocks;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.common.util.FakePlayer;
import net.minecraftforge.gametest.*;
import net.minecraftforge.registries.ForgeRegistries;

import java.math.BigInteger;
import java.util.*;
import java.util.function.Consumer;

/** Opt-in -PguiQa -PprojecteQa: actual ProjectE jar, real owner capabilities, public GUI tools. */
@GameTestHolder("aifun_projecte_test")
@PrefixGameTestTemplate(false)
public final class ProjectEGameTests {
    static final class Owner extends FakePlayer {
        boolean offline;
        Owner(GameTestHelper helper) { super(helper.getLevel(), new GameProfile(UUID.randomUUID(), "ProjectEQaOwner")); }
        @Override public boolean hasDisconnected() { return offline; }
    }
    static final class Fixture implements AutoCloseable {
        final GameTestHelper helper; final Owner owner; final EntityMaid maid; final LLMCallback callback; final BlockPos target;
        int callId;
        boolean faultOnSecondInsert;
        int insertions;
        Fixture(GameTestHelper helper) {
            this.helper = helper; owner = new Owner(helper);
            for (var air : BlockPos.betweenClosed(new BlockPos(0,1,0),new BlockPos(5,3,5))) helper.setBlock(air,Blocks.AIR);
            for (var floor : BlockPos.betweenClosed(new BlockPos(0,0,0), new BlockPos(5,0,5))) helper.setBlock(floor, Blocks.STONE);
            helper.setBlock(new BlockPos(1,1,1), ForgeRegistries.BLOCKS.getValue(new ResourceLocation("projecte:transmutation_table")));
            target = helper.absolutePos(new BlockPos(1,1,1));
            maid = new EntityMaid(InitEntities.MAID.get(), helper.getLevel()) {
                @Override public net.minecraft.world.entity.LivingEntity getOwner() { return owner; }
                @Override public net.minecraftforge.items.wrapper.CombinedInvWrapper getAvailableBackpackInv() {
                    var actual=super.getAvailableBackpackInv();
                    if(!faultOnSecondInsert)return actual;
                    return new net.minecraftforge.items.wrapper.CombinedInvWrapper(actual) {
                        @Override public ItemStack insertItem(int slot,ItemStack stack,boolean simulate) {
                            if(!simulate && !stack.isEmpty() && ++insertions==2)throw new IllegalStateException("QA mod insertion failure");
                            return super.insertItem(slot,stack,simulate);
                        }
                    };
                }
            };
            var pos = helper.absolutePos(new BlockPos(2,1,2)); maid.setPos(pos.getX()+.5,pos.getY(),pos.getZ()+.5);
            maid.setOwnerUUID(owner.getUUID()); maid.setNoAi(true); maid.setInvulnerable(true); helper.getLevel().addFreshEntity(maid);
            callback = new LLMCallback(maid.getAiChatManager(), new ArrayList<>(), true);
            knowledge().clearKnowledge(); knowledge().setEmc(BigInteger.ZERO);
        }
        IKnowledgeProvider knowledge() { return owner.getCapability(PECapabilities.KNOWLEDGE_CAPABILITY).orElseThrow(IllegalStateException::new); }
        void put(int slot, ItemStack item) { maid.getMaidInv().setStackInSlot(slot, item); }
        void observe() { MaidGuiSessionManager.session(maid.getUUID()).snapshot("ok"); }
        int count(Item item) { int count=0; var bag=maid.getAvailableBackpackInv(); for(int i=0;i<bag.getSlots();i++) if(bag.getStackInSlot(i).is(item)) count+=bag.getStackInSlot(i).getCount(); return count; }
        JsonObject call(String tool, JsonObject args) {
            String id="pe_"+(++callId);
            var result = new GuiTool(tool).onCallAsync(id,args,callback,null);
            helper.assertTrue(result.isDone(), "Semantic ProjectE tool needs no client or tick wait"); result.join();
            return JsonParser.parseString(callback.getMessages().stream().filter(message -> message.role()==Role.TOOL && id.equals(message.toolCallId()))
                    .reduce((a,b)->b).orElseThrow().message()).getAsJsonObject();
        }
        JsonObject open() {
            var args=new JsonObject(); args.addProperty("target","projecte:transmutation_table");
            args.addProperty("x",target.getX()); args.addProperty("y",target.getY()); args.addProperty("z",target.getZ()); args.addProperty("wait_policy","AUTO");
            var result=call("open_gui",args); helper.assertTrue(!result.has("error"),result.toString()); return result;
        }
        JsonObject args(String action, String item, int count) {
            var args=new JsonObject(); args.addProperty("session_id",MaidGuiSessionManager.session(maid.getUUID()).id.toString());
            args.addProperty("action",action); if(item!=null) args.addProperty("item",item); args.addProperty("count",count); return args;
        }
        JsonObject search(String item) { return call("gui_action",args("projecte_search",item,1)); }
        JsonObject deposit(int slot,int count) { var args=args("projecte_deposit",null,count); args.addProperty("backpack_slot",slot); return call("gui_action",args); }
        JsonObject exchange(String item,int count) { return call("gui_action",args("projecte_exchange",item,count)); }
        void error(JsonObject result,String expected) {helper.assertTrue(result.has("error") && result.get("error").getAsString().equals(expected),result.toString());}
        @Override public void close() { MaidGuiSessionManager.finish(callback); maid.discard(); }
    }

    @GameTest(batch="projecte",template="empty",templateNamespace="aifun_projecte_test")
    public static void depositsLearnsSearchesExchangesExactQuantityAndPersistsOwnersBalance(GameTestHelper helper) {
        try(var f=new Fixture(helper)) {
            f.put(0,new ItemStack(Items.DIAMOND,2)); f.knowledge().addKnowledge(new ItemStack(Items.IRON_INGOT));
            f.owner.getInventory().setItem(0,new ItemStack(Items.GOLD_INGOT,7));
            var locks=(net.minecraftforge.items.IItemHandlerModifiable)f.knowledge().getInputAndLocks(); locks.setStackInSlot(0,new ItemStack(Items.EMERALD,3));
            var opened=f.open(); helper.assertTrue(opened.getAsJsonObject("adapter_state").get("account").getAsString().equals("owner"),"Owner account is explicit");
            helper.assertTrue(f.search("minecraft:iron_ingot").getAsJsonArray("projecte_results").size()==1,"Learned but unaffordable iron remains searchable");
            var deposit=f.deposit(0,2); helper.assertTrue(!deposit.has("error") && deposit.get("deposited_count").getAsInt()==2,deposit.toString());
            BigInteger gain=BigInteger.valueOf(EMCHelper.getEmcSellValue(new ItemStack(Items.DIAMOND))).multiply(BigInteger.TWO);
            helper.assertTrue(f.count(Items.DIAMOND)==0 && f.knowledge().hasKnowledge(new ItemStack(Items.DIAMOND)) && f.knowledge().getEmc().equals(gain),"Actual bag diamonds become owner EMC and learned knowledge");
            var search=f.search("minecraft:iron_ingot"); String selection=search.getAsJsonArray("projecte_results").get(0).getAsJsonObject().get("selection").getAsString();
            var args=f.args("projecte_exchange","minecraft:iron_ingot",5); args.addProperty("selection",selection); args.addProperty("close_after",true);
            var exchange=f.call("gui_action",args); helper.assertTrue(!exchange.has("error") && exchange.get("exchanged_count").getAsInt()==5,exchange.toString());
            BigInteger balance=gain.subtract(BigInteger.valueOf(EMCHelper.getEmcValue(Items.IRON_INGOT)).multiply(BigInteger.valueOf(5)));
            helper.assertTrue(f.count(Items.IRON_INGOT)==5 && f.knowledge().getEmc().equals(balance),"Exact output and exact native EMC cost");
            helper.assertTrue(f.owner.getInventory().getItem(0).getCount()==7 && locks.getStackInSlot(0).getCount()==3,"Owner physical inventory and persistent table input slots untouched");
            helper.assertTrue(MaidGuiSessionManager.session(f.maid.getUUID())==null && exchange.getAsJsonObject("cleanup").get("dropped_count").getAsInt()==0,"Menu closes with no drops");
            f.open(); helper.assertTrue(f.knowledge().getEmc().equals(balance),"Reopening uses persisted owner account");
            var actor=MaidGuiSessionManager.session(f.maid.getUUID()).actor;
            helper.assertTrue(actor.getCapability(PECapabilities.KNOWLEDGE_CAPABILITY).orElseThrow(IllegalStateException::new).getEmc().equals(BigInteger.ZERO),"No duplicate EMC account on fake actor");
            helper.succeed();
        }
    }

    @GameTest(batch="projecte",template="empty",templateNamespace="aifun_projecte_test")
    public static void taskReceiptPreventsNewIdExchangeAfterReloadWithoutSpendingAgain(GameTestHelper helper) {
        try(var f=new Fixture(helper)) {
            f.knowledge().addKnowledge(new ItemStack(Items.IRON_INGOT));f.knowledge().setEmc(BigInteger.valueOf(999999));
            var state=new com.wjx.touhou_aifun.chat.agent.AgentTaskState("兑换四个铁锭，不要重复扣 EMC");state.status=com.wjx.touhou_aifun.chat.agent.AgentTaskState.Status.running;
            var contract=new JsonObject();contract.addProperty("kind","transfer");contract.addProperty("item","minecraft:iron_ingot");contract.addProperty("count",4);contract.addProperty("to_maid",true);
            contract.addProperty("dimension",f.maid.level().dimension().location().toString());var position=new JsonArray();position.add(f.target.getX());position.add(f.target.getY());position.add(f.target.getZ());contract.add("position",position);state.defineCompletion(contract);
            var task=new com.wjx.touhou_aifun.chat.agent.TaskCallback(f.maid.getAiChatManager(),new ArrayList<>(),state);
            var open=new JsonObject();open.addProperty("target","projecte:transmutation_table");open.addProperty("x",f.target.getX());open.addProperty("y",f.target.getY());open.addProperty("z",f.target.getZ());
            var opening=MaidGuiSessionManager.call(task,"open_gui","task_open",open);
            helper.assertTrue(opening.isDone() && !opening.join().has("error"),"Real task opens ProjectE table without navigation from visible stand");
            var first=MaidGuiSessionManager.call(task,"gui_action","first_exchange",f.args("projecte_exchange","minecraft:iron_ingot",4)).join();
            helper.assertTrue(!first.has("error") && f.count(Items.IRON_INGOT)==4 && state.verifiedCount==4,first.toString());
            BigInteger balance=f.knowledge().getEmc();MaidGuiSessionManager.cancel(f.maid.getUUID(),"qa_reload");state.generation++;
            state=new Gson().fromJson(new Gson().toJson(state),com.wjx.touhou_aifun.chat.agent.AgentTaskState.class);
            var resumed=new com.wjx.touhou_aifun.chat.agent.TaskCallback(f.maid.getAiChatManager(),new ArrayList<>(),state);
            var reopened=MaidGuiSessionManager.call(resumed,"open_gui","restored_open",open);
            helper.assertTrue(reopened.isDone() && !reopened.join().has("error"),"Reloaded task opens table");
            var duplicate=MaidGuiSessionManager.call(resumed,"gui_action","different_call_id",f.args("projecte_exchange","minecraft:iron_ingot",4)).join();
            helper.assertTrue(duplicate.has("error") && duplicate.get("error").getAsString().startsWith("task_transfer_exceeds_remaining"),duplicate.toString());
            helper.assertTrue(f.count(Items.IRON_INGOT)==4 && f.knowledge().getEmc().equals(balance),"No extra item and no second EMC deduction after reload");
            MaidGuiSessionManager.cancel(f.maid.getUUID(),"qa_complete");com.wjx.touhou_aifun.chat.ChatFlowManager.forgetMaid(f.maid.getUUID());helper.succeed();
        }
    }
    @GameTest(batch="projecte",template="empty",templateNamespace="aifun_projecte_test")
    public static void exchangesModItemsUsingLiveProjectEPricesAndDeduplicatesCalls(GameTestHelper helper) {
        try(var f=new Fixture(helper)) {
            Item item=ForgeRegistries.ITEMS.getValue(new ResourceLocation("projecte:alchemical_coal"));
            long price=EMCHelper.getEmcValue(item); helper.assertTrue(price>0,"Real mod item has loaded EMC");
            f.knowledge().addKnowledge(new ItemStack(item)); f.knowledge().setEmc(BigInteger.valueOf(price).multiply(BigInteger.valueOf(200))); f.open();
            var args=f.args("projecte_exchange","projecte:alchemical_coal",70);
            var first=MaidGuiSessionManager.call(f.callback,"gui_action","dedup",args).join();
            var second=MaidGuiSessionManager.call(f.callback,"gui_action","dedup",args).join();
            helper.assertTrue(!first.has("error") && first.equals(second) && f.count(item)==70,"Repeated action id never spends or produces twice, including multi-stack quantity");
            helper.assertTrue(f.knowledge().getEmc().equals(BigInteger.valueOf(price).multiply(BigInteger.valueOf(130))),"Actual mod EMC charged exactly"); helper.succeed();
        }
    }
    @GameTest(batch="projecte",template="empty",templateNamespace="aifun_projecte_test")
    public static void settlesActualPartialDeliveryAndRefundsUnproducedItemsAfterModInsertionThrows(GameTestHelper helper) {
        try(var f=new Fixture(helper)) {
            Item item=ForgeRegistries.ITEMS.getValue(new ResourceLocation("projecte:alchemical_coal"));long price=EMCHelper.getEmcValue(item);
            f.knowledge().addKnowledge(new ItemStack(item));f.knowledge().setEmc(BigInteger.valueOf(price).multiply(BigInteger.valueOf(200)));f.open();
            f.faultOnSecondInsert=true;var args=f.args("projecte_exchange","projecte:alchemical_coal",70);
            var first=MaidGuiSessionManager.call(f.callback,"gui_action","partial_pe",args).join();
            f.error(first,"projecte_inventory_changed_partial_exchange");
            helper.assertTrue(first.get("exchanged_count").getAsInt()==64 && f.count(item)==64
                    && f.knowledge().getEmc().equals(BigInteger.valueOf(price).multiply(BigInteger.valueOf(136))),"Only actual first stack charged; failed second stack refunded");
            var duplicate=MaidGuiSessionManager.call(f.callback,"gui_action","partial_pe",args).join();
            helper.assertTrue(first.equals(duplicate) && f.count(item)==64,"Partial result idempotent");
            f.faultOnSecondInsert=false;var rest=f.exchange("projecte:alchemical_coal",6);
            helper.assertTrue(!rest.has("error") && f.count(item)==70
                    && f.knowledge().getEmc().equals(BigInteger.valueOf(price).multiply(BigInteger.valueOf(130))),"Remaining quantity retried without double charging prior output");helper.succeed();
        }
    }
    @GameTest(batch="projecte",template="empty",templateNamespace="aifun_projecte_test")
    public static void preflightsKnowledgeBalanceCapacityAndStaleBackpackWithoutLoss(GameTestHelper helper) {
        try(var f=new Fixture(helper)) {
            f.open(); f.error(f.exchange("minecraft:iron_ingot",3),"projecte_item_not_learned_or_no_emc_search_again");
            f.knowledge().addKnowledge(new ItemStack(Items.IRON_INGOT)); f.error(f.exchange("minecraft:iron_ingot",3),"projecte_insufficient_emc");
            f.knowledge().setEmc(BigInteger.valueOf(999999));
            for(int i=0;i<f.maid.getAvailableBackpackInv().getSlots();i++)f.put(i,new ItemStack(Items.COBBLESTONE,64));
            f.error(f.exchange("minecraft:iron_ingot",3),"stale_slots_reinspect"); f.observe();
            f.error(f.exchange("minecraft:iron_ingot",3),"projecte_backpack_full");
            helper.assertTrue(f.knowledge().getEmc().equals(BigInteger.valueOf(999999)) && f.count(Items.IRON_INGOT)==0,"No EMC consumed on full bag");
            f.put(0,new ItemStack(Items.BEDROCK)); f.observe(); f.error(f.deposit(0,1),"projecte_item_has_no_sell_emc");
            helper.assertTrue(f.count(Items.BEDROCK)==1,"Priceless item never burned"); helper.succeed();
        }
    }
    @GameTest(batch="projecte",template="empty",templateNamespace="aifun_projecte_test")
    public static void respectsOwnerLifecyclePhysicalTableAndRejectsNativeSlotBypasses(GameTestHelper helper) {
        try(var f=new Fixture(helper)) {
            f.knowledge().addKnowledge(new ItemStack(Items.IRON_INGOT)); f.knowledge().setEmc(BigInteger.valueOf(99999)); f.open();
            var nativeClick=f.args("quick_move",null,1);nativeClick.addProperty("slot",27); f.error(f.call("gui_action",nativeClick),"use_projecte_semantic_actions");
            f.owner.offline=true; f.error(f.exchange("minecraft:iron_ingot",1),"projecte_owner_unavailable"); f.owner.offline=false;
            helper.setBlock(new BlockPos(1,1,1),Blocks.AIR); f.error(f.exchange("minecraft:iron_ingot",1),"projecte_table_unavailable");
            helper.setBlock(new BlockPos(1,1,1),ForgeRegistries.BLOCKS.getValue(new ResourceLocation("projecte:transmutation_table")));
            var pending=f.args("projecte_exchange","minecraft:iron_ingot",1); f.maid.setOwnerUUID(UUID.randomUUID());
            helper.assertTrue(MaidGuiSessionManager.call(f.callback,"gui_action","old_owner",pending).join().has("error"),"Changing owner invalidates the previous GUI session");
            f.maid.setOwnerUUID(f.owner.getUUID());
            helper.assertTrue(f.count(Items.IRON_INGOT)==0 && f.knowledge().getEmc().equals(BigInteger.valueOf(99999)),"Invalid access cannot spend owner's EMC");helper.succeed();
        }
    }
    @GameTest(batch="projecte",template="empty",templateNamespace="aifun_projecte_test")
    public static void learnsThroughNativeOwnerEventAndHonorsCancellation(GameTestHelper helper) {
        try(var f=new Fixture(helper)) {
            f.put(0,new ItemStack(Items.DIAMOND)); f.open(); boolean[] ownerEvent={false};
            Consumer<PlayerAttemptLearnEvent> listener=event->{if(event.getPlayer()==f.owner){ownerEvent[0]=true;event.setCanceled(true);}};
            MinecraftForge.EVENT_BUS.addListener(listener);
            try {
                var result=f.deposit(0,1);helper.assertTrue(!result.has("error") && ownerEvent[0] && !f.knowledge().hasKnowledge(new ItemStack(Items.DIAMOND)),"Real owner learn event is cancellable");
                helper.assertTrue(f.count(Items.DIAMOND)==0 && f.knowledge().getEmc().equals(BigInteger.valueOf(EMCHelper.getEmcSellValue(new ItemStack(Items.DIAMOND)))),"Native cancellation prevents learning while sale still credits EMC");
            } finally {MinecraftForge.EVENT_BUS.unregister(listener);}
            helper.succeed();
        }
    }
    @GameTest(batch="projecte",template="empty",templateNamespace="aifun_projecte_test")
    public static void selectsLearnedNbtVariantsAndUsesRemainingSpaceExactly(GameTestHelper helper) {
        try(var f=new Fixture(helper)) {
            ItemStack first=new ItemStack(Items.DIAMOND_SWORD),second=new ItemStack(Items.DIAMOND_SWORD);
            EnchantmentHelper.setEnchantments(Map.of(Enchantments.UNBREAKING,1),first);EnchantmentHelper.setEnchantments(Map.of(Enchantments.UNBREAKING,2),second);
            f.knowledge().addKnowledge(first); f.knowledge().addKnowledge(second); f.knowledge().setEmc(BigInteger.valueOf(999999));f.open();
            var search=f.search("minecraft:diamond_sword");var rows=search.getAsJsonArray("projecte_results");helper.assertTrue(rows.size()==2,"Native persistent enchantment variants retained");
            f.error(f.exchange("minecraft:diamond_sword",1),"projecte_ambiguous_variant_use_search_selection");
            var args=f.args("projecte_exchange","minecraft:diamond_sword",2);String token=rows.get(0).getAsJsonObject().get("selection").getAsString();args.addProperty("selection",token);
            var result=f.call("gui_action",args);helper.assertTrue(!result.has("error") && f.count(Items.DIAMOND_SWORD)==2,result.toString());
            var bag=f.maid.getAvailableBackpackInv();helper.assertTrue(ItemStack.isSameItemSameTags(bag.getStackInSlot(0),bag.getStackInSlot(1)) && bag.getStackInSlot(0).isEnchanted(),"Selected NBT reproduced, unstackable outputs occupy separate slots");
            helper.succeed();
        }
    }
    @GameTest(batch="projecte",template="empty",templateNamespace="aifun_projecte_test")
    public static void supportsFullKnowledgeAndPagedSearchWithoutClient(GameTestHelper helper) {
        try(var f=new Fixture(helper)) {
            f.knowledge().setFullKnowledge(true);f.open();var args=f.args("projecte_search",null,1);args.addProperty("limit",3);
            var page=f.call("gui_action",args);helper.assertTrue(page.getAsJsonArray("projecte_results").size()==3 && page.get("has_more").getAsBoolean(),"Full knowledge enumerated from actual ProjectE mappings");
            args.add("offset",page.get("next_offset"));var next=f.call("gui_action",args);
            helper.assertTrue(!next.getAsJsonArray("projecte_results").get(0).equals(page.getAsJsonArray("projecte_results").get(0)),"Paging advances through learned items");
            var visual=new JsonObject();visual.addProperty("session_id",MaidGuiSessionManager.session(f.maid.getUUID()).id.toString());visual.addProperty("visual",true);
            var observed=f.call("inspect_gui",visual);helper.assertTrue(observed.has("adapter_state") && !observed.has("error"),"Inspection remains usable without client rendering");helper.succeed();
        }
    }
}
