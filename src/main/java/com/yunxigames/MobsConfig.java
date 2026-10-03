package com.yunxigames;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonParseException;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.resources.Identifier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * 更多生物（幻翼 × 苦力怕混合）（yunxigames mobs 包）的独立配置。
 *
 * <p>文件位置：{@code <游戏目录>/config/yg-mobs.json}。字段全部是 public，Gson 直接读写；
 * 缺少的字段会保留默认值，所以升级后旧配置文件依然可用。每包配置相互独立。
 */
public final class MobsConfig extends YgConfig {
	public static final String FILE_NAME = "yg-mobs.json";

	// ---------- 生物改动（mobs 模块）：幻翼 × 苦力怕混合 ----------
	/**
	 * <b>幻翼 × 苦力怕混合生物</b>（v1.15，由 yg-mobs 模块实现）。
	 *
	 * <p>true：幻翼在保留原版飞行 / 俯冲能力的同时，俯冲命中目标时会引发一次爆炸
	 * （苦力怕的爆炸能力）。false：幻翼完全保持原版行为，模块只负责替换音效。
	 */
	public boolean phantomCreeperEnabled = true;

	/**
	 * 混合生物俯冲命中目标时的爆炸威力（≈ TNT 当量）。默认 3.0，与苦力怕持平；
	 * 调高会更炸、调 0 则只剩音效替换（等效于关闭爆炸）。
	 */
	public float phantomCreeperExplosionPower = 3.0F;

	/** 爆炸是否引燃火焰（默认 false，避免天上掉火球烧山）。 */
	public boolean phantomCreeperExplosionFire = false;

	/**
	 * 幻翼<b>俯冲开始</b>时是否播放自定义音频
	 * （{@code assets/yg_mobs/sounds/phantom_creeper.ogg}，即 res/bz.mp3 转码）。
	 * false 则俯冲静默，不影响其它音效开关（爆炸是独立配置）。
	 */
	public boolean phantomSoundBz = true;

	// ---------- 外观改造（mobs 模块）：头 + 躯干换苦力怕，翅膀 / 尾巴 / 眼睛维持原生幻翼 ----------
	/**
	 * 是否把幻翼的<b>头与躯干</b>换成苦力怕（苦力怕几何 + 苦力怕贴图），
	 * 翅膀 / 尾巴 / 眼睛层仍用原生幻翼那套（见 {@code PhantomCreeperRenderer}）。
	 *
	 * <p>与 {@link #phantomCreeperEnabled} 的区别：那个管<b>行为</b>（俯冲爆炸），这个只管<b>外观</b>。
	 * 两个开关独立 —— 可以只要爆炸不要换皮，也可以只要换皮不要爆炸。
	 * 判定在渲染时读取，改完存盘即时生效，不必重启客户端。
	 */
	public boolean phantomCreeperVisual = true;

	/**
	 * 苦力怕头身的<b>垂直</b>偏移（模型单位，16 = 1 格；正数往上）。
	 *
	 * <p><b>坐标系实测结论</b>（gametest 截图迭代，v1.1.0）：第二趟提交发生在
	 * {@code LivingEntityRenderer} 翻转链<b>之外</b>，该坐标系里 <b>+Y = 世界上方</b>、
	 * 原点 ≈ 幻翼躯干中心（两翼根部之间）。渲染器已复刻原版模型翻转（见
	 * {@code PhantomCreeperRenderer}），苦力怕正立、脚底站在原点上 ——
	 * 默认 <b>0</b> 就是 v1.0.0 时代「长在树上」的拼接位：两翼根长在苦力怕身上，
	 * 头顶从翅膀上缘探出。想整体抬高 / 压低再调它（正上负下）。
	 *
	 * <p><b>历史教训</b>：初版默认 -7.5/-7.0 是「两套躯干中心之差」的纸面推导，
	 * 既推错了坐标系、又没发现第二趟缺模型翻转（苦力怕头朝下倒挂），实际效果是
	 * 彻底不可见。v1.1.0 用 gametest 截图逐帧迭代才定位。
	 */
	public float phantomCreeperBodyYOffset = 0.0F;

	/**
	 * 苦力怕头身的<b>前后</b>偏移（模型单位）。
	 *
	 * <p>默认 <b>0</b>：苦力怕模型自身前后对称（深度 4 居中），而幻翼翅膀根就长在
	 * z≈0 平面上，0 即对齐。想让它前探 / 后缩再微调。
	 */
	public float phantomCreeperBodyZOffset = 0.0F;

	/**
	 * 苦力怕头身的缩放（1.0 = 原尺寸）。
	 *
	 * <p>苦力怕躯干 8×12×4、幻翼躯干 5×3×9：宽度接近，所以默认 1.0 不缩；
	 * 若觉得头身比翅膀显得太壮 / 太小，改这个值微调。
	 */
	public float phantomCreeperBodyScale = 1.0F;


	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();
	private static final Logger LOGGER = LoggerFactory.getLogger("yg-mobs.json");
	private static volatile MobsConfig instance;

	MobsConfig() {  // 包内可见：单元测试与 YgConfig 缺项补回需要 new 默认实例
	}

	/** 取当前配置；首次调用会从磁盘载入。 */
	public static MobsConfig get() {
		MobsConfig local = instance;
		if (local == null) {
			synchronized (MobsConfig.class) {
				local = instance;
				if (local == null) {
					local = load();
				}
			}
		}
		return local;
	}

	/** 从磁盘读取配置（文件缺失或损坏时回退到默认值），并把规范化后的结果写回。 */
	public static synchronized MobsConfig load() {
		Path path = configPath(FILE_NAME);
		MobsConfig loaded = null;
		com.google.gson.JsonObject raw = null;

		if (Files.isRegularFile(path)) {
			try (Reader reader = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
				// 先解析成 JsonObject 留底：merge 用它区分「json 里没写这一项」和「明确写了值」
				raw = GSON.fromJson(reader, com.google.gson.JsonObject.class);
				loaded = GSON.fromJson(raw, MobsConfig.class);
			} catch (IOException | JsonParseException e) {
				LOGGER.warn("[yg-mobs.json] 读取 {} 失败，改用默认配置：{}", path, e.toString());
			}
		}

		if (loaded == null) {
			loaded = new MobsConfig();
		} else {
			mergeMissingFields(loaded, raw, new MobsConfig());
		}

		loaded.validate();
		instance = loaded;
		loaded.save();
		return loaded;
	}

	/** 把当前配置写回磁盘。 */
	public synchronized void save() {
		Path path = configPath(FILE_NAME);
		try {
			Files.createDirectories(path.getParent());
			try (Writer writer = Files.newBufferedWriter(path, StandardCharsets.UTF_8)) {
				GSON.toJson(this, writer);
			}
		} catch (IOException e) {
			LOGGER.error("[yg-mobs.json] 写入 {} 失败：{}", path, e.toString());
		}
	}

	/** 修正越界 / 缺失的值，并解析各个 id 列表。 */
	void validate() {
		// 爆炸威力用 !(x >= 0) 顺带挡掉 NaN；允许设为 0（等效关闭爆炸，只剩音效替换）。
		if (!(phantomCreeperExplosionPower >= 0.0F)) phantomCreeperExplosionPower = 3.0F;
		phantomCreeperExplosionPower = Math.min(16.0F, phantomCreeperExplosionPower);

		// 外观对位：全部用 !(x >= lo && x <= hi) 的写法，NaN 一并落到默认值。
		// 默认值与字段声明处保持一致（0 / 0），改一处记得改两处。
		// 迁移：-7.5 / -7.0 是 v1.0.x 的「纸面推导」默认值，实测会把苦力怕送到
		// 不可见的位置（还叠加第二趟缺模型翻转的问题）—— 老配置里成对出现的
		// 这组值几乎必然是当年存下来的默认值而非刻意调的，自动归零即修复。
		if (phantomCreeperBodyYOffset == -7.5F && phantomCreeperBodyZOffset == -7.0F) {
			phantomCreeperBodyYOffset = 0.0F;
			phantomCreeperBodyZOffset = 0.0F;
		}

		if (!(phantomCreeperBodyYOffset >= -32.0F && phantomCreeperBodyYOffset <= 32.0F)) {
			phantomCreeperBodyYOffset = 0.0F;
		}

		if (!(phantomCreeperBodyZOffset >= -32.0F && phantomCreeperBodyZOffset <= 32.0F)) {
			phantomCreeperBodyZOffset = 0.0F;
		}

		// 缩放下限 0.1 防止缩成 0 后模型消失；上限 4 防止糊满屏幕。
		if (!(phantomCreeperBodyScale >= 0.1F && phantomCreeperBodyScale <= 4.0F)) {
			phantomCreeperBodyScale = 1.0F;
		}
	}
}
