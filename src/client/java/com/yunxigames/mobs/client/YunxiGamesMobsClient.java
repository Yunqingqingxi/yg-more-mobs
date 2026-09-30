package com.yunxigames.mobs.client;

import com.yunxigames.MobsConfig;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.rendering.v1.EntityRendererRegistry;
import net.minecraft.world.entity.EntityTypes;

/**
 * yg-mobs 的客户端入口：把原版幻翼的渲染器换成「苦力怕幻翼」渲染器。
 *
 * <p>外观属于客户端资源与渲染，服务端不参与 —— 所以这里只注册渲染器：
 * 俯冲爆炸（{@code PhantomCreeperMixin}）与俯冲音效（{@code PhantomSweepSoundMixin}）
 * 仍在服务端的 mixin 里，两边各管各的、互不影响。
 *
 * <p><b>为什么无条件注册自定义渲染器</b>：{@code phantomCreeperVisual} 是可以在游戏内随时改的
 * 开关，而 {@code EntityRendererRegistry} 只在客户端启动时注册一次。所以这里固定注册本模组渲染器，
 * 由它在<b>渲染时</b>读开关决定要不要第二趟苦力怕头身（关掉则完全走原生幻翼模型）——
 * 开关改了立刻生效，不需要重启客户端。
 *
 * <p>玩家没装本模组时，服务端行为照常（幻翼照样俯冲爆炸），只是看到的还是原版幻翼外观。
 */
public class YunxiGamesMobsClient implements ClientModInitializer {

	@Override
	public void onInitializeClient() {
		// 注册到 minecraft:phantom 这个原版实体类型上 —— 本模组不新增生物，是「改写原版幻翼」，
		// 所以自然生成 / 刷怪 / 已有存档里的幻翼统统变样。
		EntityRendererRegistry.register(EntityTypes.PHANTOM, PhantomCreeperRenderer::new);
	}
}
