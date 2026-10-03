package com.yunxigames.mobs.client.gametest;

import java.nio.file.Path;

import com.yunxigames.YunxiGamesMobs;

import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestServerContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
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
 * 自动开单机世界 → 在玩家面前召唤一只冻结的幻翼 → 等渲染稳定 → 截屏存盘。
 *
 * <p>运行方式：{@code ./gradlew runGametestClient}（需要 build.gradle 里的
 * {@code fabric.client.gametest=true} 运行配置）。截图路径会打到日志，
 * 用它肉眼确认混合模型的两趟提交是否都画出来了。
 *
 * <p>诊断完成后若不再需要，可整类删除并摘掉 fabric.mod.json 里的
 * {@code fabric-client-gametest} 入口点与 build.gradle 里的运行配置。
 */
public class PhantomRenderGameTest implements FabricClientGameTest {

	@Override
	public void runTest(ClientGameTestContext context) {
		try (TestSingleplayerContext sp = context.worldBuilder().create()) {
			TestServerContext server = sp.getServer();

			// 黑夜防止幻翼日光燃烧（火焰会糊住模型），夜视保证截屏亮度
			server.runCommand("time set midnight");
			server.runCommand("effect give @p minecraft:night_vision infinite 1 true");

			server.runOnServer((MinecraftServer minecraftServer) -> {
				ServerLevel level = minecraftServer.getLevel(Level.OVERWORLD);
				ServerPlayer player = minecraftServer.getPlayerList().getPlayers().getFirst();

				// 放在玩家视线正前方 3 格、略高于视点：衬在天空背景上，绿色苦力怕一目了然；
				// 转身面向玩家（yaw+180），苦力怕头身正对镜头，避免被翅膀/尾巴遮挡
				Vec3 eye = player.getEyePosition();
				Vec3 view = player.getViewVector(1.0F);

				// 26.2 没有实体类型常量，走 BuiltInRegistries 查找（见项目记忆「26.2 API 速查」）
				EntityType<Phantom> phantomType =
						(EntityType<Phantom>) BuiltInRegistries.ENTITY_TYPE.getValue(Identifier.parse("minecraft:phantom"));
				Phantom phantom = phantomType.create(level, EntitySpawnReason.COMMAND);
				phantom.setPos(eye.x + view.x * 2.6D, eye.y + 0.2D, eye.z + view.z * 2.6D);
				phantom.setYRot(player.getYRot() + 180.0F);
				phantom.setNoAi(true); // 冻结：不让它飞走/俯冲，保证截屏时姿态稳定
				boolean added = level.addFreshEntity(phantom);

				YunxiGamesMobs.LOGGER.info("[yg-gametest] phantom summoned at {} (added={})",
						phantom.position(), added);
			});

			// 等实体同步到客户端 + 混合模型渲染若干帧
			context.waitTicks(40);

			Path shot = context.takeScreenshot("yg_phantom_debug");
			YunxiGamesMobs.LOGGER.info("[yg-gametest] screenshot saved: {}", shot.toAbsolutePath());
		}
	}
}
