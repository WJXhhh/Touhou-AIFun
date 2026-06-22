package com.wjx.touhou_aifun;

import com.mojang.logging.LogUtils;
import net.minecraftforge.fml.ModLoadingContext;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.config.ModConfig;
import org.slf4j.Logger;
import com.wjx.touhou_aifun.config.TouhouAIFunConfig;
import com.wjx.touhou_aifun.network.AIFunNetwork;

@Mod(TouhouAIFun.MOD_ID)
public final class TouhouAIFun {
    public static final String MOD_ID = "touhou_aifun";
    public static final Logger LOGGER = LogUtils.getLogger();

    public TouhouAIFun() {
        ModLoadingContext.get().registerConfig(ModConfig.Type.COMMON, TouhouAIFunConfig.SPEC,
                "touhou-stepfun.toml");
        AIFunNetwork.init();
        LOGGER.info("Touhou AIFun addon initialized.");
    }
}
