package com.wjx.touhou_aifun.vision;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import static org.junit.jupiter.api.Assertions.*;

class ModelMetadataStoreTest {
    @TempDir Path directory;
    @Test void roundTripsReferencesWithoutDuplicatingConnectionParameters() throws Exception {
        ModelRef first = new ModelRef("custom/site", "vendor/model:voice");
        ModelRef second = new ModelRef("custom/site", "different");
        assertEquals(first, ModelRef.decode(first.encode()));
        assertNull(ModelRef.decode("old-site-id"));
        var store = new ModelMetadataStore();
        store.put(first, ModelMetadataStore.Metadata.DEFAULT.withCapability(VisionCapabilityMode.SUPPORTED));
        store.put(second, ModelMetadataStore.Metadata.DEFAULT.withCapability(VisionCapabilityMode.UNSUPPORTED));
        store.markMigrated(first.encode());
        Path path = directory.resolve("metadata.json");
        store.save(path);
        var read = ModelMetadataStore.read(path);
        assertEquals(VisionCapabilityMode.SUPPORTED, read.get(first).capability());
        assertEquals(VisionCapabilityMode.UNSUPPORTED, read.get(second).capability());
        assertTrue(read.migrated());
        assertEquals(first.encode(), read.migratedSelection());
        String text = Files.readString(path);
        assertFalse(text.contains("api_key"));
        assertFalse(text.contains("endpoint"));
        assertFalse(Files.exists(directory.resolve("metadata.json.tmp")));
    }
}
