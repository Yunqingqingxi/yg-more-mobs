package com.yunxigames;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * yg-mobs 单元测试：validate() 钳制的纯逻辑验证。
 * 对位偏移 / 缩放直接决定模型渲染效果，浮点钳制必须精确。
 */
class MobsUnitTest {

	@Test
	void nanExplosionPowerFallsBackToDefault() {
		MobsConfig cfg = new MobsConfig();
		cfg.phantomCreeperExplosionPower = Float.NaN;
		cfg.validate();
		assertEquals(3.0F, cfg.phantomCreeperExplosionPower, "NaN 威力必须回落默认 3.0");
	}

	@Test
	void explosionPowerZeroIsLegalButOversizeIsClamped() {
		MobsConfig cfg = new MobsConfig();
		cfg.phantomCreeperExplosionPower = 0.0F;
		cfg.validate();
		assertEquals(0.0F, cfg.phantomCreeperExplosionPower, "0 等效关闭爆炸，是合法值");
		cfg.phantomCreeperExplosionPower = 99.0F;
		cfg.validate();
		assertEquals(16.0F, cfg.phantomCreeperExplosionPower, "上限 16");
	}

	@Test
	void nanOffsetsFallBackToExactDefaults() {
		MobsConfig cfg = new MobsConfig();
		cfg.phantomCreeperBodyYOffset = Float.NaN;
		cfg.phantomCreeperBodyZOffset = Float.NaN;
		cfg.phantomCreeperBodyScale = Float.NaN;
		cfg.validate();
		assertEquals(-7.5F, cfg.phantomCreeperBodyYOffset, "Y 偏移默认 -7.5，必须精确（对位用）");
		assertEquals(-7.0F, cfg.phantomCreeperBodyZOffset, "Z 偏移默认 -7.0，必须精确（对位用）");
		assertEquals(1.0F, cfg.phantomCreeperBodyScale);
	}

	@Test
	void scaleBounds() {
		MobsConfig cfg = new MobsConfig();
		cfg.phantomCreeperBodyScale = 0.01F;
		cfg.validate();
		assertEquals(1.0F, cfg.phantomCreeperBodyScale, "低于 0.1 回落默认 1.0（缩成 0 模型会消失）");
		cfg.phantomCreeperBodyScale = 10.0F;
		cfg.validate();
		assertEquals(1.0F, cfg.phantomCreeperBodyScale, "越界不钳边而是整体回落默认 1.0（对位参数联动，钳边会造成错位）");
	}
}
