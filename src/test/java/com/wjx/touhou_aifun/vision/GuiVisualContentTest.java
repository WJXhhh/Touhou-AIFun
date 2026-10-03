package com.wjx.touhou_aifun.vision;

import com.google.gson.JsonObject;
import org.junit.jupiter.api.Test;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;

class GuiVisualContentTest {
    @Test void guiImageIsPresentInEveryProtocolAndEnvironmentFacesRemainSix() {
        assertEquals(6, MultimodalContent.FACES.length);
        for (var protocol : UnifiedModelCatalog.VisualProtocol.values()) {
            var content = MultimodalContent.content(protocol, "GUI", Map.of("gui", "data:image/jpeg;base64,AAAA"));
            assertEquals(3, content.size()); assertTrue(content.toString().contains("AAAA"));
        }
    }
    @Test void guiPromptUsesLogicalCoordinatesWithoutWorldDirections() {
        JsonObject metadata = new JsonObject(); metadata.addProperty("gui_width", 640); metadata.addProperty("gui_height", 480);
        VisionRequest request = new VisionRequest(null, null, "按钮", "", Map.of("gui", "data:image/jpeg;base64,AAAA"), -1, 10, 10, Float.NaN, "GUI", metadata);
        String prompt = OpenAICompatibleVisionClient.prompt(request);
        assertTrue(prompt.contains("640")); assertTrue(prompt.contains("logical GUI")); assertFalse(prompt.contains("cubemap"));
    }
}
