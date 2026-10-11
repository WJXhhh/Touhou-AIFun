package com.wjx.touhou_aifun.client.gui;

import com.wjx.touhou_aifun.chat.agent.decision.DecisionServiceSettings;
import com.wjx.touhou_aifun.network.AIFunNetwork;
import com.wjx.touhou_aifun.network.message.*;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.*;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.*;

/** Draft credentials travel only client -> server. Server -> client returns a presence flag. */
public final class DecisionServiceSettingsScreen extends Screen {
    private final Screen parent;
    private String provider="jev",status="",draftKey="";
    private boolean enabled,hasKey,clearKey,readOnly=true,pending=true,requested;
    private EditBox endpoint,model,key,timeout,confidence;
    private DecisionServiceSettings.View view=new DecisionServiceSettings.View("jev",false,"https://api.typesafe.ai/v1/systemone","jev-1.13.0",false,3000,0.9);
    public DecisionServiceSettingsScreen(Screen parent) {super(Component.translatable("gui.touhou_aifun.native_decision"));this.parent=parent;}
    private int x(){return width/2-215;} private int y(){return height/2-115;}
    private Component text(String k){return Component.translatable("gui.touhou_aifun.native_decision."+k);}
    @Override protected void init() {
        capture();
        rebuild();if(!requested && minecraft!=null && minecraft.getConnection()!=null){requested=true;AIFunNetwork.requestNativeDecisionSettings("");}
    }
    private Button button(int x,int y,int w,Component label,boolean active,Runnable click) {
        var b=Button.builder(label,ignored->click.run()).bounds(x,y,w,20).build();b.active=active;return addRenderableWidget(b);
    }
    private EditBox field(int y,int width,int max,String value) {
        var box=new EditBox(font,x()+105,y,width,20,Component.empty());box.setMaxLength(max);box.setValue(value);box.setEditable(!readOnly && !pending);addRenderableWidget(box);return box;
    }
    private void rebuild() {
        clearWidgets();int left=x()+105;
        button(left,y()+25,150,Component.literal(provider.equals("jev")?"JEV / TypeSafe":"Perplexity Decider"),!pending,()-> {
            provider=provider.equals("jev")?"perplexity":"jev";pending=true;AIFunNetwork.requestNativeDecisionSettings(provider);rebuild();
        });
        button(left+160,y()+25,150,text(enabled?"enabled":"disabled"),!readOnly && !pending,()->{capture();enabled=!enabled;rebuild();});
        endpoint=field(y()+50,310,1024,view.endpoint());model=field(y()+80,310,256,view.model());
        key=field(y()+110,225,512,draftKey);
        key.setHint(text(hasKey?"key_keep":"key_empty"));
        key.setFormatter((value,cursor)->net.minecraft.util.FormattedCharSequence.forward("•".repeat(value.length()),Style.EMPTY));
        button(left+235,y()+110,75,text(clearKey?"clearing":"clear_key"),!readOnly && !pending,()->{capture();clearKey=!clearKey;rebuild();});
        timeout=field(y()+140,90,5,Integer.toString(view.timeoutMs()));
        confidence=new EditBox(font,left+220,y()+140,90,20,Component.empty());confidence.setMaxLength(6);confidence.setValue(Double.toString(view.minConfidence()));confidence.setEditable(!readOnly && !pending);addRenderableWidget(confidence);
        button(left+145,y()+205,80,CommonComponents.GUI_DONE,!readOnly && !pending,this::save);
        button(left+230,y()+205,80,CommonComponents.GUI_BACK,true,this::onClose);
    }
    private void capture() {
        if(endpoint==null) return;
        draftKey=key.getValue();
        try {view=new DecisionServiceSettings.View(provider,enabled,endpoint.getValue(),model.getValue(),hasKey,Integer.parseInt(timeout.getValue()),Double.parseDouble(confidence.getValue()));}
        catch(NumberFormatException ignored) { }
    }
    private void save() {
        try {
            var draft=new DecisionServiceSettings.View(provider,enabled,endpoint.getValue(),model.getValue(),hasKey,Integer.parseInt(timeout.getValue()),Double.parseDouble(confidence.getValue()));
            DecisionServiceSettings.validateEndpoint(draft.endpoint());
            pending=true;AIFunNetwork.saveNativeDecisionSettings(new AIFunNativeDecisionSettingsMessage(true,provider,enabled,draft.endpoint(),draft.model(),key.getValue(),clearKey,draft.timeoutMs(),draft.minConfidence()));
            view=draft;draftKey="";rebuild();
        } catch(RuntimeException invalid) {status="invalid_endpoint".equals(invalid.getMessage())?"invalid_endpoint":"invalid_threshold";}
    }
    public void refresh(AIFunNativeDecisionSettingsSyncMessage reply) {
        view=reply.view();provider=view.provider();enabled=view.enabled();hasKey=view.hasKey();clearKey=false;draftKey="";
        readOnly=reply.readOnly();status=reply.status();pending=false;rebuild();
    }
    @Override public void render(GuiGraphics g,int mx,int my,float partial) {
        renderBackground(g);g.drawCenteredString(font,title,width/2,y()+5,0xFFFFFF);
        g.drawWordWrap(font,text("help"),x()+8,y()+170,410,0xAAAAAA);
        for(int i=0;i<4;i++) g.drawString(font,text(new String[]{"url","model","key","timeout"}[i]),x()+8,y()+56+i*30,0xFFFFFF,false);
        g.drawString(font,text("confidence"),x()+210,y()+146,0xFFFFFF,false);
        if(!status.isBlank()) g.drawString(font,text(status),x()+8,y()+208,0xAAAAAA,false);
        super.render(g,mx,my,partial);
    }
    @Override public void onClose(){if(minecraft!=null) minecraft.setScreen(parent);}
    @Override public boolean isPauseScreen(){return false;}
}
