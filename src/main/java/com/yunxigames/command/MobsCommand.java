package com.yunxigames.command;

import com.mojang.brigadier.CommandDispatcher;
import com.yunxigames.MobsConfig;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.permissions.PermissionCheck;
import net.minecraft.server.permissions.Permissions;

/**
 * {@code /yg mobs ...}：生物玩法（幻翼 × 苦力怕混合）的游戏内启停与状态。
 *
 * <p>各玩法包统一往 {@code /yg} 根下挂以玩法名命名的子树（Brigadier 会把各包注册的
 * 同名根节点合并成一棵命令树），与 drops 包的 {@code /yg drops} 同一布局。
 *
 * <p>off 同时关闭俯冲爆炸、俯冲音效与外观换皮三个开关 —— 幻翼完全恢复原版行为。
 * 注意外观是<b>客户端渲染</b>判定：专用服务器上这里改的是服务端配置，已连接的客户端
 * 要各自改自己的 {@code config/yg-mobs.json}（或重启客户端同步默认值）才会卸下换皮。
 */
public final class MobsCommand {
	/** 与 drops 包同一权限档（等价旧「权限等级 2」，OP 可用）。 */
	private static final PermissionCheck PERMISSION = new PermissionCheck.Require(Permissions.COMMANDS_GAMEMASTER);

	private MobsCommand() {
	}

	public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
		dispatcher.register(Commands.literal("yg")
				.requires(Commands.hasPermission(PERMISSION))
				.then(Commands.literal("mobs")
						.executes(context -> status(context.getSource()))
						.then(Commands.literal("on")
								.executes(context -> toggle(context.getSource(), true)))
						.then(Commands.literal("off")
								.executes(context -> toggle(context.getSource(), false)))));
	}

	private static int toggle(CommandSourceStack source, boolean enabled) {
		MobsConfig config = MobsConfig.get();
		config.phantomCreeperEnabled = enabled;
		config.phantomSoundBz = enabled;
		config.phantomCreeperVisual = enabled;
		config.save();
		source.sendSuccess(() -> Component.literal("[yg] 生物玩法整体：" + (enabled ? "开启" : "关闭")
				+ "（俯冲爆炸 + 俯冲音效 + 外观换皮；服务端部分立即生效）"), false);
		return status(source);
	}

	private static int status(CommandSourceStack source) {
		MobsConfig config = MobsConfig.get();
		source.sendSuccess(() -> Component.literal(String.format(
				"[yg] 生物玩法=%s | 俯冲爆炸=%s(威力%.1f) 俯冲音效=%s 外观换皮=%s(垂直偏移%.1f)",
				config.phantomCreeperEnabled || config.phantomCreeperVisual ? "开" : "关",
				config.phantomCreeperEnabled ? "开" : "关",
				(double) config.phantomCreeperExplosionPower,
				config.phantomSoundBz ? "开" : "关",
				config.phantomCreeperVisual ? "开" : "关",
				(double) config.phantomCreeperBodyYOffset)), false);
		return 1;
	}
}
