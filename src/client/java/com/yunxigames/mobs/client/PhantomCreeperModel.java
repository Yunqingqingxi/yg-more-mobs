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
 *   <li>苦力怕的 {@code root}（root 自身就是躯干、{@code head} 挂在它下面，「头 + 躯干」一次凑齐；
 *       四条腿与腿上的脚全部藏掉）<b>不挂进树</b>，由渲染器独立持有、单独以苦力怕贴图提交
 *       —— 原因见下方「材质分离」。</li>
 * </ul>
 *
 * <p><b>为什么根必须留着幻翼的 {@code body}</b>：父类 {@link PhantomModel} 的构造会依次取
 * {@code root.getChild("body")} → {@code body.getChild("tail_base" / "left_wing_base" / "right_wing_base")}，
 * 自己另建一棵不含 {@code body} 的树会直接抛「找不到部件」→ 渲染器构造失败 → 黑屏。
 *
 * <p><b>位置对齐</b>：苦力怕以脚底为原点、幻翼躯干原点在身体中心，两个坐标系不同，
 * 所以由渲染器每帧按 {@code yg-mobs.json} 的偏移/缩放把苦力怕那块平移到位（见
 * {@link PhantomCreeperRenderer}）。观感不合适只改配置里的数值，不用重新编译。
 *
 * <p><b>苦力怕块为什么不挂进混合树</b>（v1.3.0 修复「材质分离」）：26.2 的每趟模型提交
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

	/** 苦力怕那一块（root 自身就是躯干，head 挂在它下面）。 */
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
	 * <p>它不参与动画（{@code EntityModel#setupAnim} 是空实现）—— 头身跟着幻翼躯干走，
	 * 由渲染器按配置偏移平移整块，不需要逐帧摆姿势。
	 */
	public static final class CreeperPartModel extends EntityModel<PhantomRenderState> {
		public CreeperPartModel(ModelPart creeperRoot) {
			super(creeperRoot);
		}
	}
}
