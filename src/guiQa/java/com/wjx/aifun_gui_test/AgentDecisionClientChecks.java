package com.wjx.aifun_gui_test;

import com.wjx.touhou_aifun.chat.agent.AgentDecisionModels;
import com.wjx.touhou_aifun.client.gui.AgentDecisionSettingsScreen;
import com.wjx.touhou_aifun.network.message.AIFunDecisionSettingsSyncMessage;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import java.nio.file.Path;
import java.util.List;

/** Standalone client layout check: no world, credentials, provider config writes or model requests. */
@Mod.EventBusSubscriber(modid="aifun_gui_test", value=Dist.CLIENT)
public final class AgentDecisionClientChecks {
    private static int ticks;
    private static int shownAt;
    private static java.util.concurrent.CompletableFuture<Void> reload;
    private static AgentDecisionSettingsScreen screen;
    private static com.wjx.touhou_aifun.client.gui.DecisionServiceSettingsScreen nativeScreen;
    private static List<AgentDecisionModels.Option> models;
    @SubscribeEvent public static void tick(TickEvent.ClientTickEvent event) {
        if (!Boolean.getBoolean("aifun.decisionUiQa.autorun") || event.phase!=TickEvent.Phase.END) return;
        var mc=Minecraft.getInstance();
        if (mc.screen==null || ++ticks<100) return;
        try {
            if (ticks==100) {
                mc.options.languageCode="zh_cn";
                mc.getLanguageManager().setSelected("zh_cn");
                reload=mc.reloadResourcePacks();
            }
            if (ticks>=160 && screen==null && reload!=null && reload.isDone() && mc.getOverlay()==null) {
                shownAt=ticks;
                screen=new AgentDecisionSettingsScreen(new TitleScreen());mc.setScreen(screen);
                models=java.util.stream.IntStream.range(0,8).mapToObj(i -> new AgentDecisionModels.Option(
                        "[\"qa\",\"fast-"+i+"\"]", "测试站点 / 快速决策模型 "+i, i!=3)).toList();
                screen.refresh(new AIFunDecisionSettingsSyncMessage(models.get(2).id(),models,false,""));
                var rows=screen.children().stream().filter(child -> child instanceof net.minecraft.client.gui.components.AbstractWidget)
                        .map(child -> (net.minecraft.client.gui.components.AbstractWidget)child).toList();
                if(rows.size()!=8 || rows.get(4).active || !rows.get(3).active)
                    throw new AssertionError("Unavailable model must be disabled while usable models can be selected");
                screen.refresh(new AIFunDecisionSettingsSyncMessage(models.get(2).id(),models,true,""));
                if(screen.children().stream().filter(child -> child instanceof net.minecraft.client.gui.components.AbstractWidget)
                        .map(child -> (net.minecraft.client.gui.components.AbstractWidget)child).limit(6).anyMatch(row -> row.active))
                    throw new AssertionError("Readonly users must not be able to change model selection");
            }
            if (screen!=null && ticks==shownAt+20) {
                if(mc.screen!=screen) throw new AssertionError("Resource reload replaced the decision settings screen");
                try(var image=net.minecraft.client.Screenshot.takeScreenshot(mc.getMainRenderTarget())) {
                    image.writeToFile(Path.of("agent-decision-settings-qa.png"));
                }
                if(!screen.mouseScrolled(screen.width/2.0+50,screen.height/2.0, -1))
                    throw new AssertionError("Model list must handle scrolling");
                screen.refresh(new AIFunDecisionSettingsSyncMessage("[\"removed\",\"fast\"]",models,true,"model_unavailable"));
            }
            if (screen!=null && ticks==shownAt+40) {
                if(mc.screen!=screen) throw new AssertionError("Decision settings screen changed before capture");
                try(var image=net.minecraft.client.Screenshot.takeScreenshot(mc.getMainRenderTarget())) {
                    image.writeToFile(Path.of("agent-decision-invalid-qa.png"));
                }
                screen.onClose();
                if (!(mc.screen instanceof TitleScreen)) throw new AssertionError("Decision settings back did not restore its parent");
                nativeScreen=new com.wjx.touhou_aifun.client.gui.DecisionServiceSettingsScreen(mc.screen);mc.setScreen(nativeScreen);
                nativeScreen.refresh(new com.wjx.touhou_aifun.network.message.AIFunNativeDecisionSettingsSyncMessage(
                        new com.wjx.touhou_aifun.chat.agent.decision.DecisionServiceSettings.View("perplexity",true,"https://api.perplexity.ai/v1/decisions","pplx-decider-v1.1-27b",true,3000,0.9),false,""));
                var edits=nativeScreen.children().stream().filter(child->child instanceof net.minecraft.client.gui.components.EditBox).map(child->(net.minecraft.client.gui.components.EditBox)child).toList();
                if(edits.size()!=5) throw new AssertionError("Native service must expose URL/model/Key/timeout/confidence");edits.get(2).setValue("QA-secret-must-be-masked");
            }
            if(nativeScreen!=null && ticks==shownAt+60) {
                try(var image=net.minecraft.client.Screenshot.takeScreenshot(mc.getMainRenderTarget())) {image.writeToFile(Path.of("native-decision-settings-qa.png"));}
                nativeScreen.refresh(new com.wjx.touhou_aifun.network.message.AIFunNativeDecisionSettingsSyncMessage(
                        new com.wjx.touhou_aifun.chat.agent.decision.DecisionServiceSettings.View("jev",false,"https://api.typesafe.ai/v1/systemone","jev-1.13.0",true,3000,0.9),true,"permission_denied"));
                for(var child:nativeScreen.children()) if(child instanceof net.minecraft.client.gui.components.EditBox edit) {
                    String before=edit.getValue();edit.setFocused(true);edit.charTyped('x',0);if(!before.equals(edit.getValue())) throw new AssertionError("Readonly native fields accepted keyboard input");
                }
            }
            if(nativeScreen!=null && ticks==shownAt+80) {
                try(var image=net.minecraft.client.Screenshot.takeScreenshot(mc.getMainRenderTarget())) {image.writeToFile(Path.of("native-decision-readonly-qa.png"));}
                nativeScreen.onClose();if(!(mc.screen instanceof TitleScreen)) throw new AssertionError("Native decision back did not restore parent");
                java.nio.file.Files.writeString(Path.of("agent-decision-ui-result.txt"),"PASS: execution model list, disabled model, readonly, scroll, invalid selection, native service fields, masked Key, readonly editing, back");
                mc.stop();
            }
        } catch(Throwable error) {
            error.printStackTrace();
            try {java.nio.file.Files.writeString(Path.of("agent-decision-ui-result.txt"),"FAIL: "+error);} catch(Exception ignored) { }
            mc.stop();
        }
    }
}
