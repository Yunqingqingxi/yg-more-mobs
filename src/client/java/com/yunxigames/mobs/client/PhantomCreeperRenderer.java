package com.yunxigames.mobs.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.yunxigames.MobsConfig;
import net.minecraft.client.model.geom.ModelLayers;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.client.model.monster.phantom.PhantomModel;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.client.renderer.entity.PhantomRenderer;
import net.minecraft.client.renderer.entity.state.PhantomRenderState;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.resources.Identifier;

/**
 * 「苦力怕幻翼」的渲染器（yg-mobs 客户端）：<b>翅膀 / 尾巴 / 眼睛走原生幻翼贴图，
 * 头 + 躯干走苦力怕贴图</b>。
 *
 * <p><b>一次渲染、两趟提交、两张贴图</b>：26.2 的实体渲染已经是「延迟提交」模型
 * （{@code submit(...)} 只往 {@link SubmitNodeCollector} 里塞节点，真正绘制在之后统一进行），
 * 所以这里在父类那种「幻翼贴图 + 原生通道」的基础上，<b>再补一趟</b>只提交苦力怕的头与躯干、
 * 用苦力怕自己的贴图。两趟的节点先后入队，绘制顺序自然是「先幻翼、后苦力怕」，头身盖在翅膀根上。
 *
 * <p><b>幻翼眼睛发光层的处理</b>：原版 {@code PhantomRenderer} 会挂 {@code PhantomEyesLayer}
 * （整棵模型树再用 {@code phantom_eyes.png} 采一遍、发光叠加）。混合模式下它提交的是
 * {@link PhantomCreeperModel} —— 树里<b>没有苦力怕块</b>（见模型类「材质分离」注释），
 * 而幻翼的躯干与头都被隐藏，所以它什么都不会画，无害；关掉换皮时提交的是干净的
 * 原版树，眼睛层自动恢复原版效果，无需额外处理。
 *
 * <p><b>为什么不用 Mixin 去改渲染器</b>：{@code LivingEntityRenderer.model} 是父类字段，
 * 而 Mixin 的 {@code @Shadow} 只在目标类自身查字段、不沿继承链找，挂 {@code PhantomRenderer}
 * 会报 {@code @Shadow field model was not located in the target class}，直接把渲染线程打死
 * （表现为游戏能启动但全程黑屏）。子类继承原版渲染器则可以直接用普通 Java 访问 protected 字段。
 *
 * <p><b>贴图约定</b>：本包<b>不再覆盖</b> {@code assets/minecraft/textures/entity/phantom/*}
 * （老版本那样做会把原版幻翼的眼睛层一起抹掉变成透明），幻翼那半直接用原版贴图，
 * 苦力怕那半用 {@link #CREEPER_TEXTURE}（本模组自带副本，装本包即自包含）。
 *
 * <p>头身的对位（垂直 / 前后偏移、缩放）全部走 {@code MobsConfig} 的字段，游戏里看着不对
 * 直接改 json 即可，不需要重新编译。
 */
public class PhantomCreeperRenderer extends PhantomRenderer {

	/** 原版幻翼贴图：翅膀 / 尾巴，以及眼睛层（不再覆盖，回归原生）。 */
	private static final Identifier PHANTOM_TEXTURE =
			Identifier.fromNamespaceAndPath("minecraft", "textures/entity/phantom/phantom.png");

	/** 苦力怕贴图：本模组自带副本，供头 + 躯干使用。 */
	private static final Identifier CREEPER_TEXTURE =
			Identifier.fromNamespaceAndPath("yg_mobs", "textures/entity/creeper.png");

	private static final RenderType CREEPER_RENDER_TYPE = RenderTypes.entityCutout(CREEPER_TEXTURE);

	/**
	 * 混合模型：翅膀 / 尾巴（原生幻翼）+ 头 / 躯干（苦力怕）。
	 *
	 * <p>必须由本类持有 —— 父类只有 {@code EntityModel<? super S>} 字段，用它回调
	 * {@link PhantomCreeperModel#creeperPart()} 还得强转，不如自己存一份干净。
	 */
	private final PhantomCreeperModel mixedModel;

	/** 纯原版幻翼模型：{@code phantomCreeperVisual} 关掉时用它，外观与未装本模组时完全一致。 */
	private final PhantomModel vanillaModel;

	/** 苦力怕「头 + 躯干」那一块，单独当模型提交用（只需一份，不必每帧新建）。 */
	private final PhantomCreeperModel.CreeperPartModel creeperPart;

	public PhantomCreeperRenderer(EntityRendererProvider.Context context) {
		super(context);

		// 混合树与原版树必须是两份独立的幻翼骨架：context.bakeLayer 对同一层有缓存语义，
		// 共用一份实例的话，混合侧对 body/head 的隐藏改动会污染原版模型 ——
		// 关掉 phantomCreeperVisual 后幻翼会只剩翅膀、没有躯干和头（v1.1.0 修复）。
		// 原版树用 createBodyLayer 现烤一份干净的，绝不共享。
		ModelPart phantomRoot = context.bakeLayer(ModelLayers.PHANTOM);
		ModelPart vanillaRoot = PhantomModel.createBodyLayer().bakeRoot();
		ModelPart creeperRoot = context.bakeLayer(ModelLayers.CREEPER);

		this.mixedModel = new PhantomCreeperModel(phantomRoot, creeperRoot);
		this.vanillaModel = new PhantomModel(vanillaRoot);
		this.creeperPart = new PhantomCreeperModel.CreeperPartModel(mixedModel.creeperPart());

		// 父类构造里已经建好一份原版幻翼模型，这里换成混合模型（默认开启外观改造）。
		this.model = this.mixedModel;
	}

	/**
	 * 本体那趟用原版幻翼贴图（翅膀 / 尾巴 / 眼睛层全在它上面），
	 * 苦力怕的头与躯干在 {@link #submit} 里用苦力怕贴图补一趟。
	 */
	@Override
	public Identifier getTextureLocation(PhantomRenderState state) {
		return PHANTOM_TEXTURE;
	}

	/**
	 * 先按父类走完原生那一趟（幻翼贴图 + 眼睛层），再按开关补上苦力怕头身的第二趟。
	 *
	 * <p>第二趟完全复刻父类提交本体时的取值方式（同一个 {@code lightCoords}、同一套
	 * 受击 / 死亡白闪 overlay 与 tint），只是把模型换成苦力怕那块、贴图换成苦力怕贴图 ——
	 * 这样头身的光照与受击反馈和翅膀保持一致，不会出现「翅膀红了头还是绿的」。
	 *
	 * <p><b>只看 {@code phantomCreeperVisual}，不看 {@code phantomCreeperEnabled}</b>：
	 * 后者是「俯冲命中会不会爆炸」的行为开关，两者相互独立 ——
	 * 用户可以只要苦力怕的外观不要爆炸，也可以只要爆炸保持原版外观。
	 *
	 * <p>开关关掉时把模型换回纯原版幻翼：因为模型是每帧现摆的，同一帧内父类提交完之后立刻
	 * 换回来不会影响那一趟已入队的节点（节点里存的是已经算好的顶点），所以不必重启客户端。
	 */
	@Override
	public void submit(PhantomRenderState state, PoseStack poseStack, SubmitNodeCollector collector,
			CameraRenderState cameraState) {
		MobsConfig config = MobsConfig.get();
		boolean mixed = config.phantomCreeperVisual;

		this.model = mixed ? this.mixedModel : this.vanillaModel;

		super.submit(state, poseStack, collector, cameraState);

		if (!mixed) {
			return;
		}

		// 与父类同一套取值：白闪进度 → overlay 坐标，以及受击 / 状态色的 tint
		float overlayProgress = this.getWhiteOverlayProgress(state);
		int overlay = getOverlayCoords(state, overlayProgress);

		// 隐身药水 / 旁观者看隐身实体时，父类那趟本体根本不画（getRenderType 返回 null）——
		// 那第二趟也必须跟着不画，否则会出现「翅膀没了、苦力怕头身还飘着」的经典 bug。
		if (!this.isBodyVisible(state)) {
			return;
		}

		int tint = this.getModelTint(state);

		poseStack.pushPose();
		try {
			// 1) 配置偏移最先做：此刻轴还是纯世界轴（+Y = 世界上方、+Z = 朝尾巴方向），
			//    改 json 直观可调。对齐本身不靠它：苦力怕头 / 躯干已摆进幻翼的 raw 坐标系
			//    （见模型类），这里只剩观感微调职责。
			poseStack.translate(0.0F, config.phantomCreeperBodyYOffset, config.phantomCreeperBodyZOffset);
			poseStack.scale(config.phantomCreeperBodyScale,
					config.phantomCreeperBodyScale,
					config.phantomCreeperBodyScale);

			// 2) 逐帧复刻第一趟（super.submit → LivingEntityRenderer）pushPose 内部的
			//    <b>完整</b>基变换链。只复刻 scale(-1,-1,1)+translate(0,-1.501,0) 是不够的
			//    （gametest 两次实锤）：
			//    a) 漏了 setupRotations —— 里面有 180-bodyRot 的偏航 + PhantomRenderer 覆写的
			//       xRot 俯仰，第二趟机身会指向另一个水平方向；
			//    b) 漏了 PhantomRenderer#scale —— 它在翻转系里还有 scale(1+0.15*size) 和
			//       <b>translate(0, 1.3125, 0.1875)</b>，等于把幻翼整副骨架又下移 1.3125 格，
			//       不复刻它机身就悬在翅膀上方约 1.3 格（2026-10-03 截图实测）。
			//    这里直接调用本类继承到的虚方法（setupRotations / scale），虚分派自动带上
			//    PhantomRenderer 的覆写，与第一趟的链路逐字节一致，原版改了这里也跟着对。
			float entityScale = state.scale;
			poseStack.scale(entityScale, entityScale, entityScale);
			this.setupRotations(state, poseStack, state.bodyRot, entityScale);
			poseStack.scale(-1.0F, -1.0F, 1.0F);
			this.scale(state, poseStack);
			poseStack.translate(0.0F, -1.501F, 0.0F);

			//    拼装（头 / 躯干的位姿）见 PhantomCreeperModel.CreeperPartModel#setupAnim。

			collector.submitModel(this.creeperPart, state, poseStack, CREEPER_RENDER_TYPE,
					state.lightCoords, overlay, tint, null, state.outlineColor, null);
		} finally {
			poseStack.popPose();
		}
	}
}
