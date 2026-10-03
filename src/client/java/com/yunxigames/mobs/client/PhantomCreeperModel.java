package com.yunxigames.mobs.client;

import net.minecraft.client.model.EntityModel;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.client.model.monster.phantom.PhantomModel;
import net.minecraft.client.renderer.entity.state.PhantomRenderState;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 「苦力怕幻翼」的模型（yg-mobs 客户端）：<b>幻翼的翅膀 / 尾巴 / 飞行姿态全套保留，
 * 头与躯干换成苦力怕</b>（云兮 2026-09-24 拍板：头+躯干换，其余维持原生幻翼样式）。
 *
 * <p><b>为什么之前那版会花屏</b>：老实现把模型换成了苦力怕方块，但渲染器仍继承
 * {@code PhantomRenderer}（贴图硬编码返回 {@code phantom.png}），于是「苦力怕 UV + 幻翼贴图」
 * 错配采样 —— 采样区一半是绿皮碎片、一半是透明，渲染出来就是错位花屏。本类只负责<b>几何</b>，
 * 贴图由 {@link PhantomCreeperRenderer} 分两趟提交解决，两者职责不再混在一起。
 *
 * <p><b>模型树怎么拼</b>（新树只装幻翼的骨架，苦力怕块独立持有）：
 * <ul>
 *   <li>根下放幻翼的原 {@code body} —— 翅膀 / 尾巴本来就是它的子部件，跟着一起进来，
 *       拍打与摆动动画操作的是同一批 {@link ModelPart} 实例，因此动画原封不动；</li>
 *   <li>幻翼自己的躯干方块用 {@code skipDraw = true} 藏掉（<b>只跳过自身方块，子部件照常渲染</b>，
 *       所以翅膀不会被误伤；用 {@code visible=false} 会把翅膀一起藏掉）；</li>
 *   <li>幻翼的 {@code head} 是 {@code body} 的子部件，单独 {@code visible = false}；</li>
 *   <li>苦力怕的 {@code root}（root 本身没有 cube，只是挂载点 —— 躯干是它名为
 *       {@code body} 的子部件、头是 {@code head}，「头 + 躯干」随子树一起提交；
 *       四条腿与腿上的脚全部藏掉）<b>不挂进树</b>，由渲染器独立持有、单独以苦力怕贴图提交
 *       —— 原因见下方「材质分离」。</li>
 * </ul>
 *
 * <p><b>为什么根必须留着幻翼的 {@code body}</b>：父类 {@link PhantomModel} 的构造会依次取
 * {@code root.getChild("body")} → {@code body.getChild("tail_base" / "left_wing_base" / "right_wing_base")}，
 * 自己另建一棵不含 {@code body} 的树会直接抛「找不到部件」→ 渲染器构造失败 → 黑屏。
 *
	 * <p><b>位置对齐</b>：苦力怕头 / 躯干在 {@link CreeperPartModel#setupAnim} 里直接摆进
	 * <b>幻翼的 raw 坐标系</b>（幻翼 body pivot = 原点），与翅膀 / 尾巴共用同一个基变换，
	 * 拼接由坐标系本身保证；{@code yg-mobs.json} 的偏移 / 缩放只剩观感微调职责（见
	 * {@link PhantomCreeperRenderer}）。
 *
 * <p><b>苦力怕块为什么不挂进混合树</b>（v1.1.0 修复「材质分离」）：26.2 的每趟模型提交
 * 都会<b>遍历整棵树</b> —— 把苦力怕块挂进树里，它就会被幻翼贴图趟与幻翼眼睛发光层
 * （{@code PhantomEyesLayer}，整棵树再用 {@code phantom_eyes.png} 采一遍）先后采样，
 * 在苦力怕头身表面叠出两套错位材质，与第二趟正确的苦力怕贴图互相竞争。
 * 所以树里只放幻翼的 {@code body}，苦力怕块由渲染器<b>独立持有</b>、单独以苦力怕贴图提交。
 */
public class PhantomCreeperModel extends PhantomModel {

	/** 幻翼翅膀 / 尾巴的那一半（躯干隐藏，只用来挂着翅膀与尾巴）。 */
	public static final String PHANTOM_KEY = "body";

	/** 苦力怕的四条腿：只要头和躯干，腿不要（幻翼本来就没有腿）。 */
	private static final String[] CREEPER_LEGS = {
			"right_hind_leg", "left_hind_leg", "right_front_leg", "left_front_leg",
	};

	/** 苦力怕那一块（root 本身无 cube，只是挂载点：躯干是 {@code body} 子部件、头是 {@code head}）。 */
	private final ModelPart creeperPart;

	public PhantomCreeperModel(ModelPart phantomRoot, ModelPart creeperRoot) {
		super(buildRoot(phantomRoot.getChild(PHANTOM_KEY)));

		this.creeperPart = creeperRoot;

		ModelPart phantomBody = phantomRoot.getChild(PHANTOM_KEY);

		// 藏躯干方块但保住翅膀 / 尾巴：skipDraw 只跳自身方块，visible=false 会连子部件一起藏。
		phantomBody.skipDraw = true;

		// 幻翼的头是 body 的子部件（见 PhantomModel#createBodyLayer），单独藏掉。
		if (phantomBody.hasChild("head")) {
			phantomBody.getChild("head").visible = false;
		}

		// 苦力怕只要头 + 躯干：四条腿藏掉（连腿上的脚一起，因为 foot 挂在腿下面）。
		for (String leg : CREEPER_LEGS) {
			if (creeperRoot.hasChild(leg)) {
				creeperRoot.getChild(leg).visible = false;
			}
		}
	}

	/**
	 * 拼出新模型树：只放幻翼的 {@code body}（翅膀 / 尾巴都在它下面）。
	 *
	 * <p>幻翼的 {@code body} 必须原样放进来，否则父类构造取部件时找不到 → 抛异常。
	 * 苦力怕块<b>刻意不挂</b> —— 见类注释「材质分离」：挂进树里会被幻翼贴图趟与
	 * 眼睛发光层先后采样，叠出错位材质；它由渲染器独立持有、单独提交。
	 */
	private static ModelPart buildRoot(ModelPart phantomBody) {
		Map<String, ModelPart> children = new LinkedHashMap<>();
		children.put(PHANTOM_KEY, phantomBody);
		return new ModelPart(List.of(), children);
	}

	/** 取「苦力怕头 + 躯干」那一块，供渲染器第二趟单独用苦力怕贴图提交。 */
	public ModelPart creeperPart() {
		return this.creeperPart;
	}

	/**
	 * 苦力怕那一块的最小包装模型：只为让渲染器能把它当作一个 {@code Model} 用苦力怕贴图提交。
	 *
	 * <p>{@link #setupAnim} 里做<b>静态拼装</b>：把直立的苦力怕改拼成参考图的
	 * 「绿机身」造型（2026-09-22 参考图，云兮拍板）—— 躯干放倒沿飞行方向平铺，
	 * 头保持正立接在躯干前端。渲染器每帧绘制前都会调用本方法，所以位姿每帧覆写即可，
	 * 也不需要父类的 {@code resetPose}（那反而会把位姿打回烘焙原状）。
	 */
	public static final class CreeperPartModel extends EntityModel<PhantomRenderState> {

		private final ModelPart head;
		private final ModelPart body;

		public CreeperPartModel(ModelPart creeperRoot) {
			super(creeperRoot);
			this.head = creeperRoot.getChild("head");
			this.body = creeperRoot.getChild("body");
		}

		/**
		 * 拼装「绿机身」。坐标是 <b>幻翼的 raw 模型系</b>（+Y 向下，16 单位 = 1 格），
		 * 经渲染器复刻的原版翻转（scale(-1,-1,1) + translate(0,-1.501,0)）后成为世界系。
		 *
		 * <p><b>为什么必须用幻翼的坐标系</b>（2026-10-03 修复「身体和翅膀分离」）：
		 * 反编译 26.2 PhantomModel 实锤 —— 幻翼 body 的 pivot 是 <b>(0,0,0)</b>，本体 cube
		 * 只占 raw x -3..2 / y -2..1 / z -8..1，翅膀平面在 raw y -2..0，尾巴 z 0..12；
		 * 整只幻翼贴着模型原点长。早先把苦力怕摆在 raw y≈24（照搬 32 高人形的脚底翻转公式），
		 * 同一个翻转下机身落在世界 y≈0、翅膀却渲染在世界 y≈1.53 —— 中间空出 1.5 格，
		 * 两趟提交各画各的，肉眼看就是「翅膀和身子分离」。把苦力怕搬进幻翼的 raw 系，
		 * 两趟共用同一个基变换，<b>对齐由坐标系本身保证</b>，不再依赖任何手调偏移。
		 *
		 * <p>幻翼原始几何（反编译值，拼装全部以此为锚）：
		 * <ul>
		 *   <li>body cube x -3..2（<b>偏心 0.5</b>，中心 x=-0.5）、y -2..1、z -8..1；</li>
		 *   <li>翅膀 left_wing_base x 2..8 / right_wing_base x -9..-3，y -2..0，z -8..1
		 *       —— 两翼关于 x=-0.5 对称；</li>
		 *   <li>tail_base pivot(0,-2,0) cube z 0..6、tail_tip 接 z 6..12。</li>
		 * </ul>
		 *
		 * <p>目标布局（同一 raw 系）：<b>躯干</b>绕 X +90° 平铺 z -8..+4、横截面
		 * x ±4、厚 4 居中于翼根平面（y -3..+1，翼根 y -2..0 整段嵌进躯干里）；
		 * tail_base 前段 z 0..4 被躯干吞住、从 z +4 接出 —— 尾巴与机身无缝相连。
		 * <b>头</b>不旋转（脸朝 -Z = 前进方向），z -16..-8 接住躯干前端，
		 * y -5..+3 与躯干同中心（raw y -1）；x 与翅膀同取 -0.5 偏心。
		 */
		@Override
		public void setupAnim(PhantomRenderState state) {
			// 躯干：pivot raw(-0.5, -1, -8)，xRot=+90° 把「向下 12 单位」翻成「向后 +Z 12 单位」。
			this.body.setPos(-0.5F, -1.0F, -8.0F);
			this.body.xRot = (float) (Math.PI / 2.0);
			this.body.yRot = 0.0F;
			this.body.zRot = 0.0F;

			// 头：pivot raw(-0.5, 3, -12)，cube 在 pivot 上方 8 单位 → y -5..+3，
			// z -16..-8 与躯干前端相接；不旋转 = 脸保持朝前。
			this.head.setPos(-0.5F, 3.0F, -12.0F);
			this.head.xRot = 0.0F;
			this.head.yRot = 0.0F;
			this.head.zRot = 0.0F;
		}
	}
}
