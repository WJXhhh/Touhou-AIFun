package com.wjx.touhou_aifun.compat.ai.stepfun.layout;

import com.github.tartaricacid.touhoulittlemaid.ai.service.stt.STTSite;
import com.github.tartaricacid.touhoulittlemaid.client.gui.entity.maid.ai.layout.FieldDescriptor;
import com.github.tartaricacid.touhoulittlemaid.client.gui.entity.maid.ai.layout.STTSiteFormLayout;
import net.minecraft.network.chat.Component;
import org.apache.commons.lang3.StringUtils;
import org.jetbrains.annotations.Nullable;
import com.wjx.touhou_aifun.compat.ai.stepfun.stt.StepFunSTTSite;

import java.util.List;
import java.util.function.Consumer;
import java.util.function.Function;

import static com.github.tartaricacid.touhoulittlemaid.client.gui.entity.maid.ai.FormField.MODELS;
import static com.github.tartaricacid.touhoulittlemaid.client.gui.entity.maid.ai.FormField.SECRET_KEY;
import static com.github.tartaricacid.touhoulittlemaid.client.gui.entity.maid.ai.FormField.URL;
import static com.github.tartaricacid.touhoulittlemaid.client.gui.entity.maid.ai.Translations.SECRET_KEY_IS_EMPTY;
import static com.github.tartaricacid.touhoulittlemaid.client.gui.entity.maid.ai.Translations.URL_IS_EMPTY;

public class StepFunSTTFormLayout extends STTSiteFormLayout {
    public StepFunSTTFormLayout(STTSite sourceSite) {
        super(sourceSite);
    }

    @Override
    public List<FieldDescriptor> getFieldDescriptors() {
        StepFunSTTSite site = (StepFunSTTSite) this.sourceSite;
        return List.of(
                new FieldDescriptor(URL, site.url(), true, false),
                new FieldDescriptor(SECRET_KEY, site.getSecretKey(), true, true),
                new FieldDescriptor(MODELS, site.getModel(), true, false)
        );
    }

    @Override
    public @Nullable STTSite buildSite(Function<String, String> fieldValues, Consumer<Component> showStatus) {
        StepFunSTTSite site = (StepFunSTTSite) this.sourceSite;
        String url = StringUtils.trimToEmpty(fieldValues.apply(URL));
        if (StringUtils.isBlank(url)) {
            showStatus.accept(URL_IS_EMPTY);
            return null;
        }
        String secretKey = StringUtils.trimToEmpty(fieldValues.apply(SECRET_KEY));
        if (StringUtils.isBlank(secretKey)) {
            showStatus.accept(SECRET_KEY_IS_EMPTY);
            return null;
        }
        return new StepFunSTTSite(
                site.id(),
                site.icon(),
                site.enabled(),
                url,
                secretKey,
                StringUtils.trimToEmpty(fieldValues.apply(MODELS))
        );
    }
}
