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
 * <p>① Gson 缺项数值读成 0 —— 对位偏移默认值必须精确补回（旧默认 -7.5 时代缺项变 0 曾
 * 掩盖错位；v1.1.0 实测恒等位姿才是正确对位，默认改 0/0，缩放 1.0 仍是非零锚点）；
 * ② 旧布尔盲补写法把玩家明确写的 false 偷改回 true。
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
		// v1.1.0 起默认 0/0（恒等位姿）。0 与「Gson 缺项读成的 0」数值上不可分辨，
		// 但断言仍然钉死「缺项 = 代码默认」这条链路；缩放默认 1.0 才是非零锚点
		//（旧默认 -7.5/-7.0 的教训：纸面推导的对位偏移把苦力怕整个埋进了地面）。
		assertEquals(0.0F, cfg.phantomCreeperBodyYOffset, "缺项 Y 偏移补回代码默认 0");
		assertEquals(0.0F, cfg.phantomCreeperBodyZOffset, "缺项 Z 偏移补回代码默认 0");
		assertEquals(1.0F, cfg.phantomCreeperBodyScale,
				"缺项缩放必须补回 1.0 而不是 Gson 的 0.0（scale=0 模型整体消失）");
	}

	@Test
	void missingBooleanFieldsFallBackToCodeDefaultTrue() throws Exception {
		Files.writeString(configDir.resolve(MobsConfig.FILE_NAME),
				"{\"phantomCreeperEnabled\": true}");
		MobsConfig cfg = MobsConfig.load();
		assertTrue(cfg.phantomCreeperVisual, "缺项外观开关必须补回 true");
	}

	@Test
	void legacyBuriedOffsetPairMigratesToZero() throws Exception {
		Files.writeString(configDir.resolve(MobsConfig.FILE_NAME),
				"{\"phantomCreeperBodyYOffset\": -7.5, \"phantomCreeperBodyZOffset\": -7.0}");
		MobsConfig cfg = MobsConfig.load();
		// -7.5/-7.0 是 v1.0.x 的「纸面推导」默认值，实测把苦力怕送到不可见的位置。
		// 老配置里成对出现的这组值几乎必然是当年存下来的默认值而非刻意调的，
		// validate() 自动归零即修复 —— 本测试钉死迁移行为，防止将来被当回归误删。
		assertEquals(0.0F, cfg.phantomCreeperBodyYOffset, "旧默认对 (-7.5,-7.0) 自动迁移归零");
		assertEquals(0.0F, cfg.phantomCreeperBodyZOffset, "旧默认对 (-7.5,-7.0) 自动迁移归零");
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
