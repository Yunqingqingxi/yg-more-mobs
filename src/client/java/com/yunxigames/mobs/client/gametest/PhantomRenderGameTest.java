package com.yunxigames.mobs.client.gametest;

import java.nio.file.Path;

import com.yunxigames.YunxiGamesMobs;

import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestServerContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.monster.Phantom;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;

/**
 * 幻翼渲染取证的临时 gametest（不进正式测试套件）：
 * 自动开单机世界 → 按参考图机位召唤一只冻结的幻翼 → 等渲染稳定 → 截屏存盘。
 *
 * <p>机位复刻 2026-09-22 参考图（云兮的「绿机身」设计渲染图）：前上方 3/4 俯视，
 * 俯角 35°，幻翼在面向相机的基础上再偏 35°（头指向画面左侧）——
 * 用于和参考图逐处比对拼装效果。
 *
 * <p>运行方式：{@code ./gradlew runGametestClient}（JDK 25 + --offline）。
 * 截图存到 {@code run/screenshots/}，路径打在日志里。
 *
 * <p>诊断完成后若不再需要，可整类删除并摘掉 fabric.mod.json 里的
 * {@code fabric-client-gametest} 入口点与 build.gradle 里的运行配置。
 */
public class PhantomRenderGameTest implements FabricClientGameTest {

	/** 相机俯角（度，正 = 向下看）。 */
	private static final float CAM_PITCH = 35.0F;

	/** 幻翼相对相机的水平距离（格）。 */
	private static final double DISTANCE = 2.6;

	/** 幻翼朝向 = 面向相机再偏转的角度（正 = 头指向画面左侧）。 */
	private static final float PHANTOM_YAW_OFFSET = 35.0F;

	@Override
	public void runTest(ClientGameTestContext context) {
		try (TestSingleplayerContext sp = context.worldBuilder().create()) {
			TestServerContext server = sp.getServer();

			// 黑夜防止幻翼日光燃烧（火焰糊住模型），夜视保证截屏亮度；
			// 俯视机位需要高处相机：先在头顶放一块玻璃平台再把玩家站上去
			//（直接 tp 到半空会摔回地面，相机跟着掉，幻翼就出画了 —— 踩过的坑）
			server.runCommand("time set midnight");
			server.runCommand("effect give @p minecraft:night_vision infinite 1 true");
			server.runCommand("setblock ~ ~6 ~ minecraft:glass");
			server.runCommand("tp @p ~ ~7 ~");

			// 保存幻翼引用，供后面日志用（两个 runOnServer 在服务端线程顺序执行）
			Phantom[] holder = new Phantom[1];

			server.runOnServer((MinecraftServer minecraftServer) -> {
				ServerLevel level = minecraftServer.getLevel(Level.OVERWORLD);
				ServerPlayer player = minecraftServer.getPlayerList().getPlayers().getFirst();

				// 沿「俯角 35° 的视线」放幻翼：它会正好落在画面中心
				float camYaw = player.getYRot();
				double yawRad = Math.toRadians(camYaw);
				double pitchRad = Math.toRadians(CAM_PITCH);
				Vec3 viewDir = new Vec3(
						-Math.sin(yawRad) * Math.cos(pitchRad),
						-Math.sin(pitchRad),
						Math.cos(yawRad) * Math.cos(pitchRad));
				Vec3 eye = player.getEyePosition();

				// 26.2 没有实体类型常量，走 BuiltInRegistries 查找（见项目记忆「26.2 API 速查」）
				EntityType<Phantom> phantomType =
						(EntityType<Phantom>) BuiltInRegistries.ENTITY_TYPE.getValue(Identifier.parse("minecraft:phantom"));
				Phantom phantom = phantomType.create(level, EntitySpawnReason.COMMAND);
				phantom.setPos(eye.x + viewDir.x * DISTANCE,
						eye.y + viewDir.y * DISTANCE,
						eye.z + viewDir.z * DISTANCE);
				phantom.setYRot(camYaw + 180.0F + PHANTOM_YAW_OFFSET);
				phantom.setNoAi(true); // 冻结：不让它飞走/俯冲，保证截屏时姿态稳定
				boolean added = level.addFreshEntity(phantom);
				holder[0] = phantom;

				YunxiGamesMobs.LOGGER.info("[yg-gametest] phantom summoned at {} (added={})",
						phantom.position(), added);
			});

			// 客户端相机同步俯角（yaw 保持与服务器一致，只压低视角）
			context.runOnClient(minecraft -> {
				LocalPlayer p = minecraft.player;
				p.setXRot(CAM_PITCH);
				p.xRotO = CAM_PITCH;
			});

			// 等实体同步到客户端 + 混合模型渲染若干帧
			context.waitTicks(40);

			// 取证：截图瞬间的客户端相机 vs 服务端幻翼实际位置
			context.computeOnClient(minecraft -> {
				LocalPlayer p = minecraft.player;
				YunxiGamesMobs.LOGGER.info("[yg-gametest] client cam pos={} yaw={} pitch={}",
						p.position(), p.getYRot(), p.getXRot());
				return null;
			});
			server.runOnServer((MinecraftServer minecraftServer) -> {
				ServerPlayer player = minecraftServer.getPlayerList().getPlayers().getFirst();
				YunxiGamesMobs.LOGGER.info("[yg-gametest] server player pos={} yaw={}",
						player.position(), player.getYRot());
				if (holder[0] != null) {
					YunxiGamesMobs.LOGGER.info("[yg-gametest] phantom now at {} noAi={} alive={}",
							holder[0].position(), holder[0].isNoAi(), holder[0].isAlive());
				}
			});

			Path shot = context.takeScreenshot("yg_phantom_debug");
			YunxiGamesMobs.LOGGER.info("[yg-gametest] screenshot saved: {}", shot.toAbsolutePath());
		}
	}
}
