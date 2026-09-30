package com.yunxigames;

import com.google.gson.Gson;
import com.google.gson.JsonObject;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * yg-mobs 冒烟测试：mod 描述文件合法、配置能从零生成并写回、改动能落盘再读回。
 */
@Tag("smoke")
class MobsSmokeTest {

	private static final Gson GSON = new Gson();

	@TempDir
	Path configDir;

	@BeforeEach
	void injectConfigDir() {
		YgConfig.configDirOverride = configDir;
	}

	@AfterEach
	void resetConfigDir() {
		YgConfig.configDirOverride = null;
	}

	@Test
	void fabricModJsonIsValidWithCorrectModId() throws Exception {
		try (var in = getClass().getResourceAsStream("/fabric.mod.json")) {
			assertNotNull(in, "fabric.mod.json 必须在 jar 资源里");
			JsonObject json = GSON.fromJson(
					new InputStreamReader(in, StandardCharsets.UTF_8), JsonObject.class);
			assertEquals("yg_mobs", json.get("id").getAsString());
			assertEquals(1, json.get("schemaVersion").getAsInt());
			assertNotNull(json.get("entrypoints").getAsJsonObject().get("main"));
			assertNotNull(json.get("entrypoints").getAsJsonObject().get("client"),
					"mobs 有客户端源集（外观渲染），必须声明 client 入口");
		}
	}

	@Test
	void loadCreatesDefaultConfigFileOnDisk() {
		MobsConfig cfg = MobsConfig.load();
		assertTrue(Files.isRegularFile(configDir.resolve(MobsConfig.FILE_NAME)),
				"load() 后配置文件必须已写回磁盘");
		assertTrue(cfg.phantomCreeperEnabled, "苦力怕幻翼默认开");
		assertTrue(cfg.phantomCreeperVisual, "外观改造默认开");
		assertTrue(cfg.phantomSoundBz, "自定义俯冲音效默认开");
	}

	@Test
	void modifiedValuesSurviveSaveLoadRoundtrip() {
		MobsConfig cfg = MobsConfig.load();
		cfg.phantomCreeperExplosionPower = 5.5F;
		cfg.phantomCreeperExplosionFire = true;
		cfg.save();

		MobsConfig reloaded = MobsConfig.load();
		assertEquals(5.5F, reloaded.phantomCreeperExplosionPower);
		assertTrue(reloaded.phantomCreeperExplosionFire, "写盘的 true 必须原样读回");
	}
}
