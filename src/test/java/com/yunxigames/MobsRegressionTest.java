package com.yunxigames;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * yg-mobs 回归测试：钉死本包真踩过的两个坑。
 *
 * <p>① Gson 缺项数值读成 0 —— 对位偏移默认 -7.5，缺项曾静默变成 0 导致头身错位
 * （自检断言精确默认值才抓到）；② 旧布尔盲补写法把玩家明确写的 false 偷改回 true。
 */
class MobsRegressionTest {

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
	void missingOffsetFieldFallsBackToExactDefaultNotZero() throws Exception {
		Files.writeString(configDir.resolve(MobsConfig.FILE_NAME),
				"{\"phantomCreeperEnabled\": true}");
		MobsConfig cfg = MobsConfig.load();
		assertEquals(-7.5F, cfg.phantomCreeperBodyYOffset,
				"缺项 Y 偏移必须补回 -7.5（Gson 缺项是 0.0，而 0 在 [-32,32] 内不会被钳制——只能靠缺项补回）");
		assertEquals(-7.0F, cfg.phantomCreeperBodyZOffset);
	}

	@Test
	void missingBooleanFieldsFallBackToCodeDefaultTrue() throws Exception {
		Files.writeString(configDir.resolve(MobsConfig.FILE_NAME),
				"{\"phantomCreeperEnabled\": true}");
		MobsConfig cfg = MobsConfig.load();
		assertTrue(cfg.phantomCreeperVisual, "缺项外观开关必须补回 true");
	}

	@Test
	void explicitFalseInJsonMustNotBeOverwritten() throws Exception {
		Files.writeString(configDir.resolve(MobsConfig.FILE_NAME),
				"{\"phantomCreeperEnabled\": true, \"phantomCreeperVisual\": false}");
		MobsConfig cfg = MobsConfig.load();
		assertFalse(cfg.phantomCreeperVisual,
				"玩家明确写 false 必须保持 false —— 旧「默认 true 读到 false 就补回」写法会偷改，本测试钉死正确行为");
	}
}
