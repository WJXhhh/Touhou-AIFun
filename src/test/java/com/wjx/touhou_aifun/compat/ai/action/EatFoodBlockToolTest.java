package com.wjx.touhou_aifun.compat.ai.action;

import com.github.tartaricacid.touhoulittlemaid.ai.service.function.schema.parameter.ObjectParameter;
import com.google.gson.Gson;
import com.google.gson.JsonObject;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EatFoodBlockToolTest {
    @Test
    void requestDistinguishesOneServingFromFinishingTheTarget() {
        EatFoodBlockTool.Request oneServing = new EatFoodBlockTool.Request("cake", 12, false);
        EatFoodBlockTool.Request finishTarget = new EatFoodBlockTool.Request("cake", 12, true);

        assertFalse(oneServing.untilFinished());
        assertTrue(finishTarget.untilFinished());
    }

    @Test
    void schemaRequiresTheModelToChooseAnActionScope() {
        ObjectParameter root = ObjectParameter.create();
        new EatFoodBlockTool().parameters(root, null);
        JsonObject schema = new Gson().toJsonTree(root).getAsJsonObject();

        assertTrue(schema.getAsJsonArray("required").asList().stream()
                .anyMatch(value -> "until_finished".equals(value.getAsString())));
    }
}
