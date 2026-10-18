package com.wjx.aifun_gui_test;

import com.electronwill.nightconfig.core.CommentedConfig;
import com.electronwill.nightconfig.core.UnmodifiableConfig;
import com.wjx.touhou_aifun.config.TouhouAIFunConfig;
import net.minecraftforge.common.ForgeConfigSpec;
import java.util.List;

/** Detach temporary test settings from Forge's autosaving, asynchronously reloaded file. */
final class QaConfigScope implements AutoCloseable {
    private final CommentedConfig original;
    private boolean closed;

    QaConfigScope() throws ReflectiveOperationException {
        var field=ForgeConfigSpec.class.getDeclaredField("childConfig");
        field.setAccessible(true);
        original=(CommentedConfig)field.get(TouhouAIFunConfig.SPEC);
        TouhouAIFunConfig.SPEC.setConfig(copy(original));
    }

    private static CommentedConfig copy(UnmodifiableConfig source) {
        var result=CommentedConfig.inMemory();
        source.valueMap().forEach((key,value)->result.set(List.of(key),copyValue(value)));
        return result;
    }

    private static Object copyValue(Object value) {
        if(value instanceof UnmodifiableConfig config)return copy(config);
        if(value instanceof List<?> list)return list.stream().map(QaConfigScope::copyValue).collect(java.util.stream.Collectors.toCollection(java.util.ArrayList::new));
        return value;
    }

    @Override public void close() {
        if(closed)return;
        closed=true;
        TouhouAIFunConfig.SPEC.setConfig(original);
    }
}
