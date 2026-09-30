package com.yunxigames;

import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.monster.Phantom;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.Level;

import com.yunxigames.mobs.PhantomSound;

/**
 * 生物改变模块的开服自检：把这一批功能跑一遍，结论写进日志。
 *
 * <p>为什么在<b>真服务器</b>上跑而不是写单元测试：要摸到 {@code ServerLevel} / 实体生成 / 声音注册表，
 * 纯 mock 测不出「真的能用」。自检挂在 {@code SERVER_STARTED} 上，拿真实的 {@code overworld} 当实验场。
 *
 * <p>触发方式：配置里把 {@code selfTestRolls} 设成大于 0（比如 200），开服时即跑；跑完改回 0 关闭。
 */
public final class MobSelfTest {

	private MobSelfTest() {
	}

	/**
	 * ㊲ 幻翼 × 苦力怕混合：幻翼类型存在 + 能生成且存活。
	 *
	 * <p><b>为什么不检查开关的值</b>：开关本来就是给人关的。以前这里断言「必须启用」，
	 * 结果谁把 {@code phantomCreeperEnabled} 改成 false 就会看到一个假的 ❌ ——
	 * 自检该查的是「链路能不能用」，不是「用户有没有按默认值配」。所以只回显开关状态。
	 */
	static void checkPhantomCreeper(MinecraftServer server, ServerLevel level, MobsConfig config) {
		boolean typeOk = false;
		boolean spawnOk = false;
		boolean aliveOk = false;

		EntityType<?> type = BuiltInRegistries.ENTITY_TYPE.getValue(Identifier.parse("minecraft:phantom"));
		if (type != null) {
			typeOk = true;
			Entity e = type.create(level, EntitySpawnReason.EVENT);
			if (e instanceof Phantom phantom) {
				try {
					BlockPos at = level.getHeightmapPos(Heightmap.Types.MOTION_BLOCKING, new BlockPos(0, 64, 0));
					phantom.setPos(at.getX() + 0.5, at.getY() + 3.0, at.getZ() + 0.5);
					spawnOk = level.addFreshEntity(phantom);
					aliveOk = phantom.isAlive();
				} finally {
					phantom.discard();
				}
			}
		}

		check("㊲ 生物改动·幻翼×苦力怕混合",
				typeOk && spawnOk && aliveOk,
				"幻翼类型存在=" + typeOk + " 生成=" + spawnOk + " 存活=" + aliveOk
						+ "｜开关：爆炸=" + config.phantomCreeperEnabled
						+ " 威力=" + config.phantomCreeperExplosionPower
						+ " 外观=" + config.phantomCreeperVisual
						+ " 俯冲音效=" + config.phantomSoundBz);
	}

	/** ㊳ 自定义音效：yg_mobs:phantom_creeper 必须已在声音注册表里（由 YunxiGamesMobs 注册）。 */
	static void checkSoundRegistered(MinecraftServer server, ServerLevel level, MobsConfig config) {
		boolean registered = BuiltInRegistries.SOUND_EVENT.get(YunxiGamesMobs.PHANTOM_CREEPER_SOUND).isPresent();
		check("㊳ 生物改动·自定义音效注册",
				registered,
				"yg_mobs:phantom_creeper 已注册=" + registered);
	}

	/**
	 * ㊴ 俯冲音效注入：确认 mixin 真的织进了原版「俯冲执行者」内部类，且音效可解析。
	 *
	 * <p>为什么用反射查方法名：{@code Phantom$PhantomSweepAttackGoal} 是包级私有的内部类，
	 * 外部源码引用不到；而 Mixin 织入的处理方法会以 {@code handler$...$yg$playSoundOnDiveStart}
	 * 的形式出现在目标类的<b>声明方法</b>里 —— 只要它在那儿，就证明「俯冲开始播音效」这条链路
	 * 在运行时确实挂上了（而不是编译通过、运行静默失效）。
	 */
	static void checkSwoopSoundMixin(MinecraftServer server, ServerLevel level, MobsConfig config) {
		boolean injected = false;
		try {
			Class<?> goal = Class.forName("net.minecraft.world.entity.monster.Phantom$PhantomSweepAttackGoal");
			for (java.lang.reflect.Method method : goal.getDeclaredMethods()) {
				if (method.getName().contains("yg$playSoundOnDiveStart")) {
					injected = true;
					break;
				}
			}
		} catch (Throwable ignored) {
			// 类没加载 / 名字对不上都算没织上
		}

		boolean soundOk = PhantomSound.resolvable();
		check("㊴ 俯冲音效·注入生效 + 音效可解析",
				injected && soundOk,
				"mixin 已织入俯冲目标类=" + injected + " 音效可解析=" + soundOk);
	}

	/**
	 * ㊵ 苦力怕贴图契约：本模组自带副本必须真在 jar 里，且是原尺寸 64×32。
	 *
	 * <p><b>为什么在服务端自检里查客户端贴图</b>：这张贴图是「头 + 躯干换苦力怕」的唯一资源依赖，
	 * 一旦被 build 配置漏打包（历史坑：客户端资源不会自动进 jar，要显式
	 * {@code jar { from sourceSets.client.output }}，而 main 资源靠默认流程），外观会静默变成
	 * 缺贴图的黑紫格，且服务端日志毫无反应。资源在 classpath 上，服务端也能读，所以顺手钉死。
	 *
	 * <p>为什么直接解析 PNG 头而不是用 ImageIO：只需要宽高两个数，读 IHDR 前 24 字节即可，
	 * 免得为一行检查把 java.desktop 依赖引进纯服务端自检路径。
	 *
	 * <p><b>为什么还要查父类部件</b>：{@code PhantomCreeperModel} 的新树必须留着幻翼的
	 * {@code body}（翅膀 / 尾巴挂在它下面），否则父类 {@code PhantomModel} 构造取件时直接抛异常
	 * → 渲染器构造失败 → 黑屏。这里反射父类声明的部件字段，确认取件路径没被改坏。
	 *
	 * <p><b>三态而不是两态</b>：{@code PhantomModel} 是<b>客户端类</b>（在
	 * {@code minecraft-client-only} 里，纯服务端 classpath 上根本没有），所以专门服务器上这次反射
	 * 必然取不到。这种情况必须报「本环境不可测」并且<b>算通过</b> —— 第一版写成「查不到就算失败」，
	 * 结果每次 runServer 自检都稳定报一个假 ❌，反而掩盖真问题。只有「类在、但部件字段缺了」
	 * 才是真的契约坏了。
	 *
	 * <p>注意：本项只能证明<b>资源与部件契约完好</b>，证明不了画面好看 —— 头身对位、
	 * 缩放必须在游戏里目视确认。
	 */
	static void checkCreeperVisualContract(MinecraftServer server, ServerLevel level, MobsConfig config) {
		int[] size = pngSize("assets/yg_mobs/textures/entity/creeper.png");
		boolean textureOk = size != null && size[0] == 64 && size[1] == 32;

		// 幻翼贴图必须保持「不覆盖」：本包只在 yg_mobs 命名空间下自带苦力怕贴图，
		// 绝不能再去覆盖 minecraft:phantom（老版本那样做会把原版眼睛层抹成全透明）。
		boolean noOverride = MobSelfTest.class.getResource(
				"/assets/minecraft/textures/entity/phantom/phantom.png") == null;

		Boolean partsOk = null;
		try {
			Class<?> model = Class.forName("net.minecraft.client.model.monster.phantom.PhantomModel");
			boolean wings = false;
			boolean tail = false;

			for (java.lang.reflect.Field field : model.getDeclaredFields()) {
				String name = field.getName();
				if (name.equals("leftWingBase") || name.equals("rightWingBase")) {
					wings = true;
				} else if (name.equals("tailBase")) {
					tail = true;
				}
			}

			partsOk = wings && tail;
		} catch (ClassNotFoundException | LinkageError ignored) {
			// 纯服务端环境：客户端类不在 classpath 上，这一项在本环境无法验证 → 记 null（不可测）
		} catch (Throwable t) {
			partsOk = false;
		}

		String partsText = partsOk == null ? "本环境不可测（客户端类未加载，需在客户端侧验证）" : String.valueOf(partsOk);

		check("㊵ 生物改动·苦力怕外观契约",
				textureOk && noOverride && !Boolean.FALSE.equals(partsOk),
				"贴图 64x32=" + textureOk + "（实测 " + (size == null ? "缺失" : size[0] + "x" + size[1])
						+ "） 未覆盖原版 phantom 贴图=" + noOverride
						+ " 幻翼翅膀/尾巴部件在=" + partsText);
	}

	/**
	 * 读 PNG 的 IHDR 拿宽高（PNG 签名 8 字节 + 长度 4 + "IHDR" 4 + 宽 4 + 高 4，大端）。
	 * 资源不存在或格式不对返回 null，调用方按「缺失」处理。
	 */
	private static int[] pngSize(String resourcePath) {
		try (java.io.InputStream in = MobSelfTest.class.getResourceAsStream("/" + resourcePath)) {
			if (in == null) {
				return null;
			}

			byte[] header = in.readNBytes(24);
			if (header.length < 24) {
				return null;
			}

			int width = ((header[16] & 0xFF) << 24) | ((header[17] & 0xFF) << 16)
					| ((header[18] & 0xFF) << 8) | (header[19] & 0xFF);
			int height = ((header[20] & 0xFF) << 24) | ((header[21] & 0xFF) << 16)
					| ((header[22] & 0xFF) << 8) | (header[23] & 0xFF);

			return new int[] { width, height };
		} catch (Throwable ignored) {
			return null;
		}
	}

	/**
	 * ㊶ 旧配置升级：把一份「缺少新布尔开关」的老 json 放到磁盘上真读一遍，
	 * 确认 {@code phantomCreeperVisual} 被补成默认 {@code true}，而不是静默变 false。
	 *
	 * <p>为什么值得单独一项：Gson 反序列化<b>不跑字段初始化器</b>，json 里没写的 boolean 会留在
	 * {@code false}。这个坑一旦漏了，表现是「升级了但外观没变」，而且<b>日志里一个字都没有</b>，
	 * 排查起来极其费时。这里用真实文件走一遍完整读写链路（临时覆写 → load → 校验 → 还原），
	 * 比只测一个工具方法可靠。
	 *
	 * <p>还原放在 finally 里：自检就算抛异常，也不能把用户原来的配置留在原地被写坏。
	 * 另外 {@link #hookConfigBackup()} 挂了 AFTER_RUN 兜底（进程在自检中途被杀也能还原）。
	 */
	static void checkConfigUpgrade(MinecraftServer server, ServerLevel level, MobsConfig config) {
		// 老版本 yg-mobs.json 的全部字段：注意没有 phantomCreeperVisual / 三个对位字段
		String legacy = """
				{
				  "phantomCreeperEnabled": true,
				  "phantomCreeperExplosionPower": 3.0,
				  "phantomCreeperExplosionFire": false,
				  "phantomSoundBz": true,
				  "debugLog": false,
				  "selfTestRolls": 0
				}
				""";

		java.nio.file.Path path = net.fabricmc.loader.api.FabricLoader.getInstance()
				.getConfigDir().resolve(MobsConfig.FILE_NAME);

		boolean wrote = false;
		boolean visualOk = false;
		boolean offsetOk = false;
		String detail;

		try {
			java.nio.file.Files.createDirectories(path.getParent());
			java.nio.file.Files.writeString(path, legacy, java.nio.charset.StandardCharsets.UTF_8);
			wrote = true;

			MobsConfig upgraded = MobsConfig.load();
			visualOk = upgraded.phantomCreeperVisual;

			// 对位字段是 float：默认值不为 0，所以 validate() 的钳制**救不了**「json 里没写」——
			// 老配置缺这两项时读进来就是 0.0（头身沉在中心、不探出）。
			// 这里按「当前代码里的默认值」精确断言，而不是只看非零。
			offsetOk = upgraded.phantomCreeperBodyYOffset == -7.5F
					&& upgraded.phantomCreeperBodyZOffset == -7.0F
					&& upgraded.phantomCreeperBodyScale == 1.0F;

			detail = "老 json 读入后：外观开关=" + visualOk + "（应 true）、对位=当前默认值=" + offsetOk
					+ "、Y=" + upgraded.phantomCreeperBodyYOffset
					+ " Z=" + upgraded.phantomCreeperBodyZOffset
					+ " 缩放=" + upgraded.phantomCreeperBodyScale;
		} catch (Throwable t) {
			detail = "写/读测试配置失败：" + t;
		} finally {
			if (wrote) {
				restoreConfigBackup(path);
			}

			// 不管成功失败，都把内存里的配置恢复成「磁盘上用户那份」
			MobsConfig.load();
		}

		check("㊶ 旧配置升级·缺失的默认 true 开关被补回", visualOk && offsetOk, detail);
	}

	/** 自检前备份用户配置的磁盘路径（AFTER_RUN 用它兜底还原）。 */
	private static java.nio.file.Path configBackupPath;

	/** 自检开始前调用：记下配置路径并备份成 .selftest-bak。 */
	static void hookConfigBackup() {
		try {
			java.nio.file.Path path = net.fabricmc.loader.api.FabricLoader.getInstance()
					.getConfigDir().resolve(MobsConfig.FILE_NAME);

			if (java.nio.file.Files.isRegularFile(path)) {
				configBackupPath = path;
				java.nio.file.Files.copy(path, path.resolveSibling(MobsConfig.FILE_NAME + ".selftest-bak"),
						java.nio.file.StandardCopyOption.REPLACE_EXISTING);
			}
		} catch (Throwable ignored) {
			configBackupPath = null;
		}
	}

	/** 自检结束后调用：还原被覆盖的配置并删掉备份。 */
	static void hookConfigRestore() {
		if (configBackupPath != null) {
			restoreConfigBackup(configBackupPath);
			configBackupPath = null;
		}
	}

	/** 用 .selftest-bak 覆盖回配置，并清掉备份文件。 */
	private static void restoreConfigBackup(java.nio.file.Path path) {
		try {
			java.nio.file.Path backup = path.resolveSibling(MobsConfig.FILE_NAME + ".selftest-bak");

			if (java.nio.file.Files.isRegularFile(backup)) {
				java.nio.file.Files.move(backup, path, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
			}
		} catch (Throwable ignored) {
			// 还原失败只能吞掉：这是收尾路径，再抛异常只会掩盖真正的自检结论
		}
	}

	// 复用 core 的统一自检记录器（通过静态导入）
	static void check(String name, boolean ok, String detail) {
		SelfTest.check(name, ok, detail);
	}
}
