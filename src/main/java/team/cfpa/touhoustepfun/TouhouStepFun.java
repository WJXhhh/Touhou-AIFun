package team.cfpa.touhoustepfun;

import com.mojang.logging.LogUtils;
import net.minecraftforge.fml.ModLoadingContext;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.config.ModConfig;
import org.slf4j.Logger;
import team.cfpa.touhoustepfun.config.TouhouStepFunConfig;
import team.cfpa.touhoustepfun.network.StepFunNetwork;

@Mod(TouhouStepFun.MOD_ID)
public final class TouhouStepFun {
    public static final String MOD_ID = "touhou_aifun";
    public static final Logger LOGGER = LogUtils.getLogger();

    public TouhouStepFun() {
        ModLoadingContext.get().registerConfig(ModConfig.Type.COMMON, TouhouStepFunConfig.SPEC,
                "touhou-stepfun.toml");
        StepFunNetwork.init();
        LOGGER.info("Touhou StepFun addon initialized.");
    }
}
