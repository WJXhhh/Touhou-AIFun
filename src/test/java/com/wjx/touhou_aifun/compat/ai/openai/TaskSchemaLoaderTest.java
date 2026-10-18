package com.wjx.touhou_aifun.compat.ai.openai;

import com.google.gson.*;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class TaskSchemaLoaderTest {
    @Test void registeredContractOmitsOnlyDefinitionSyntaxAndRestoresItWhenNeeded() {
        var planner=new com.wjx.touhou_aifun.chat.agent.TaskPlanningTool();var gson=new Gson();
        var full=gson.toJsonTree(planner.parameters(com.github.tartaricacid.touhoulittlemaid.ai.service.function.schema.parameter.ObjectParameter.create(),null,false,false)).getAsJsonObject();
        var registered=gson.toJsonTree(planner.parameters(com.github.tartaricacid.touhoulittlemaid.ai.service.function.schema.parameter.ObjectParameter.create(),null,false,true)).getAsJsonObject();
        assertTrue(full.getAsJsonObject("properties").has("completion"));
        var properties=registered.getAsJsonObject("properties");assertFalse(properties.has("completion"));
        assertTrue(properties.has("steps") && properties.has("current_step") && properties.has("allowed_tools") && properties.has("complete"));
        assertTrue(registered.toString().length()<full.toString().length()/2);
        var decisions=gson.toJsonTree(planner.parameters(com.github.tartaricacid.touhoulittlemaid.ai.service.function.schema.parameter.ObjectParameter.create(),null,true,true)).getAsJsonObject();
        assertTrue(decisions.getAsJsonObject("properties").has("action_steps"));
        assertFalse(decisions.getAsJsonObject("properties").has("completion"));
    }
    @Test void frozenRegisteredNamesExcludeInventedActionsButAllowRealExtensions() {
        var schema=new Gson().toJsonTree(LoadToolSchemaTool.taskParameters(List.of("open_gui","gui_action","group:gui"))).getAsJsonObject();
        var values=schema.getAsJsonObject("properties").getAsJsonObject("tool_name").getAsJsonArray("enum");
        assertTrue(values.contains(new JsonPrimitive("gui_action")));assertFalse(values.contains(new JsonPrimitive("craft_recipe")));
        assertTrue(new Gson().toJson(LoadToolSchemaTool.taskParameters(List.of("craft_recipe"))).contains("\"craft_recipe\""));
    }
    @Test void largePluginRegistriesKeepExactFreeTextLoadingInsteadOfTruncatingCapabilities() {
        var names=java.util.stream.IntStream.range(0,97).mapToObj(i->"plugin_tool_"+i).toList();
        var schema=new Gson().toJsonTree(LoadToolSchemaTool.taskParameters(names)).getAsJsonObject();
        assertFalse(schema.getAsJsonObject("properties").getAsJsonObject("tool_name").has("enum"));
    }
}
