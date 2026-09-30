package com.yunxigames.mobs;

import com.yunxigames.MobsConfig;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.level.Level;

/**
 * 「苦力怕幻翼」的自定义音频（yg-mobs 包）。
 *
 * <p>音频文件是 {@code res/bz.mp3} 转码出的 ogg，注册为 {@code yg_mobs:phantom_creeper}。
 * 播放时机是<b>俯冲开始</b>（幻翼锁定目标、刚开始往下冲的那一刻），不是命中瞬间 ——
 * 这样玩家听到声音就知道「它来了」，有反应时间躲开或举盾。
 *
 * <p>为什么音效要单独抽一个类：播放点有两处潜力（俯冲目标类、以及将来可能加的其他触发点），
 * 而「音效注册表查询 + 懒加载缓存 + 配置开关」的逻辑只该有一份，避免各写一遍走样。
 */
public final class PhantomSound {
	/** 自定义音效 id（{@code assets/yg_mobs/sounds/phantom_creeper.ogg}）。 */
	public static final Identifier ID = Identifier.parse("yg_mobs:phantom_creeper");

	private static SoundEvent cached;
	private static boolean resolved;

	/**
	 * 在指定位置播放俯冲音效。
	 *
	 * <p>用 {@code level.playSound(null, ...)}（player 传 null）：服务端会广播给附近所有玩家，
	 * 客户端单机则直接本地播放 —— 两种环境都覆盖，不需要分端代码。
	 */
	public static void playDive(Level level, double x, double y, double z) {
		SoundEvent sound = sound();
		if (sound == null) {
			return;
		}
		level.playSound(null, x, y, z, sound, SoundSource.HOSTILE, 1.5F, 1.0F);
	}

	/**
	 * 懒加载音效：等 {@code YunxiGamesMobs} 在 {@code onInitialize} 里注册完再查一次，之后缓存。
	 * 查不到（比如资源包/注册被禁用）返回 null，调用方静默跳过，绝不因音效缺失而中断逻辑。
	 */
	private static SoundEvent sound() {
		if (!resolved) {
			resolved = true;
			cached = BuiltInRegistries.SOUND_EVENT.get(ID)
					.map(holder -> holder.value())
					.orElse(null);
		}
		return cached;
	}

	/** 配置总开关（{@code yg-mobs.json} 的 {@code phantomSoundBz}）。 */
	public static boolean enabled() {
		return MobsConfig.get().phantomSoundBz;
	}

	/** 自检用：音效是否已在注册表里解析成功（查不到说明注册或资源有问题）。 */
	public static boolean resolvable() {
		return sound() != null;
	}

	private PhantomSound() {
	}
}
