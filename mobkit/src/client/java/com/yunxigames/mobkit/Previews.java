package com.yunxigames.mobkit;

import com.yunxigames.mobs.client.PhantomCreeperModel;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.client.model.geom.builders.CubeDeformation;
import net.minecraft.client.model.monster.creeper.CreeperModel;
import net.minecraft.client.model.monster.phantom.PhantomModel;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * 各生物的外观预览定义。
 *
 * <p>加一个新生物预览只需要在这里写一个方法：烘焙它的模型 → 组一个
 * {@link ModelShot.Preview} → 交给 {@link ModelShot#render}。
 */
public final class Previews {

	/** 预览输出用的固定参数。 */
	private static final int WIDTH = 900;
	private static final int HEIGHT = 700;

	/** 正交取景范围：苦力怕幻翼双翼展开约 32 单位，留点边距。 */
	private static final float ORTHO_SPAN = 34.0F;

	private Previews() {
	}

	/**
	 * 入口：渲染默认预览。用法 {@code gradlew :mobkit:shot}。
	 *
	 * <p>命令行可覆盖输出目录：{@code args[0]}。
	 */
	public static void main(String[] args) throws Exception {
		Path outDir = Path.of(args.length > 0 ? args[0] : "mobkit-out");

		List<Path> files = phantomCreeper(outDir);
		for (Path file : files) {
			System.out.println("[mobkit] 写出 " + file.toAbsolutePath());
		}

		System.out.println("[mobkit] 完成，共 " + files.size() + " 张。");
	}

	/**
	 * 「苦力怕幻翼」三视图：幻翼翅膀/尾巴（原生贴图）+ 苦力怕头身（苦力怕贴图）。
	 *
	 * <p>模型完全走模组自己的 {@link PhantomCreeperModel}（也就是游戏内真正用的那个），
	 * 所以这里出的图能代表游戏里的外观。
	 */
	public static List<Path> phantomCreeper(Path outDir) throws Exception {
		ModelPart phantomRoot = PhantomModel.createBodyLayer().bakeRoot();
		ModelPart creeperRoot = CreeperModel.createBodyLayer(CubeDeformation.NONE).bakeRoot();

		// 构造一次就走完模组真实的组装逻辑：幻翼躯干 skipDraw、幻翼头隐藏、苦力怕四腿隐藏
		new PhantomCreeperModel(phantomRoot, creeperRoot);

		// 头单独再翻半圈：躯干绕 X 放平时会把头一起带倒，这一步把它扶正
		creeperRoot.getChild("head").xRot += (float) Math.toRadians(CREEPER_HEAD_FIX_X_DEG);

		// 缩放到与翅膀相称的尺寸（1:1 时躯干会把翅膀整片挡住）
		creeperRoot.xScale = CREEPER_SCALE;
		creeperRoot.yScale = CREEPER_SCALE;
		creeperRoot.zScale = CREEPER_SCALE;

		ModelPart phantomBody = phantomRoot.getChild(PhantomCreeperModel.PHANTOM_KEY);
		applyPhantomWingPose(phantomBody);

		Texture phantomTex = Texture.load("/assets/minecraft/textures/entity/phantom/phantom.png");
		Texture creeperTex = Texture.load("/assets/yg_mobs/textures/entity/creeper.png");

		ModelShot.Preview preview = new ModelShot.Preview("yg-mobs", WIDTH, HEIGHT,
				List.of(
						ModelShot.Part.of(phantomBody, phantomTex),
						new ModelShot.Part(creeperRoot, creeperTex,
								CREEPER_ROT_X_DEG, CREEPER_ROT_Y_DEG, 0.0F,
								0.0F, CREEPER_OFFSET_Y, CREEPER_OFFSET_Z)),
				ORTHO_SPAN,
				// 以「3/4 俯视斜角」为主视角（与成品参考图一致），再附三视图便于核对结构
				List.of(ModelShot.View.ISO_RIGHT_FRONT, ModelShot.View.ISO_LEFT_FRONT,
						ModelShot.View.FRONT, ModelShot.View.SIDE, ModelShot.View.TOP));

		return ModelShot.render(preview, outDir);
	}

	// ---------------------------------------------------------------- 苦力怕幻翼的姿态常量

	/**
	 * 苦力怕这块相对幻翼躯干的<b>姿态修正</b>：躯干绕 X −90°、头再单独绕 X −90°。
	 *
	 * <p>目标是参考成品图的形态：<b>躯干横躺</b>（长轴顺着机头方向）、
	 * <b>头是方块立在躯干上方的前端</b>、<b>脸朝前正立</b>。
	 *
	 * <p>这组数值不是推出来的，是 {@link PoseProbe} 用几何数字筛出来的。它报告：
	 * <pre>
	 *   bodyX-90 headX-90 → body size 8/10.2/16（长轴 = Z，BODY-FLAT）
	 *                       head center (0, +0.4, +4.0)（在躯干前端）
	 *                       face-normal (0, 0, +4) → +Z FORWARD OK
	 * </pre>
	 * 其它候选都被探针否掉了：{@code bodyX+90} 脸朝上、{@code bodyX-90} 脸朝地、
	 * 不带头部修正时头会掉到躯干<b>下面</b>（head-above-body=false）。
	 *
	 * <p>为什么必须"头单独再转"：躯干绕 X 放平的同时会把头一起带倒，
	 * 而"躯干长轴躺到 Z"与"头的上方仍朝 Y"在同一个旋转下互相矛盾（同一根局部 +Y
	 * 不可能既映射到 Z 又映射到 Y）—— 所以只能拆成两块各转各的，
	 * 这也正对应需求里的「头 + 躯干换」。
	 */
	public static final float CREEPER_ROT_X_DEG = -90.0F;
	public static final float CREEPER_ROT_Y_DEG = 0.0F;

	/** 头相对躯干的额外修正：把被躯干旋转带倒的脸扶正、并让头回到躯干上方。 */
	public static final float CREEPER_HEAD_FIX_X_DEG = -90.0F;

	/**
	 * 苦力怕那块相对幻翼的<b>缩放</b>。
	 *
	 * <p>苦力怕躯干 8×12×4（躺平后沿机头方向长 20），而幻翼双翼展开约 32 ——
	 * 1:1 时在 3/4 俯视角下躯干会横在镜头与翅膀之间、把翅膀整片挡住，
	 * 与参考图"头身在中间、双翼完整可见"完全不符。缩到 0.6 后比例才对上。
	 */
	public static final float CREEPER_SCALE = 0.6F;

	/**
	 * 苦力怕这块相对幻翼躯干原点的位移（模型单位，16 = 1 格）。
	 *
	 * <p>按 {@link PoseProbe} 实测的包围盒 + 0.6 缩放推得：
	 * 躯干中心落在 {@code y = +2.9×0.6} 附近、头在前端 {@code z = +4×0.6}，
	 * 下移让躯干贴在幻翼躯干上、前移让头探出机头。
	 */
	public static final float CREEPER_OFFSET_Y = -3.0F;
	public static final float CREEPER_OFFSET_Z = -6.0F;

	/**
	 * 复刻 {@code PhantomModel.setupAnim} 的翅膀摆动，取相位 0.375（cos≈0，双翼接近水平）。
	 *
	 * <p>注意层级：翅膀尖挂在翅膀根下面（不是挂在 body 上），与原版取件路径一致。
	 */
	private static void applyPhantomWingPose(ModelPart body) {
		float angle = (float) (Math.cos(0.375D * 7.448451D) * 16.0D) * 0.017453292F;

		ModelPart leftBase = body.getChild("left_wing_base");
		ModelPart rightBase = body.getChild("right_wing_base");

		leftBase.zRot = angle;
		leftBase.getChild("left_wing_tip").zRot = angle;
		rightBase.zRot = -angle;
		rightBase.getChild("right_wing_tip").zRot = -angle;
	}

	/** 便捷方法：一组候选姿态，用于"哪组旋转才对"的对比排查。 */
	public static List<ModelShot.View> allViews() {
		List<ModelShot.View> views = new ArrayList<>();
		views.add(ModelShot.View.FRONT);
		views.add(ModelShot.View.SIDE);
		views.add(ModelShot.View.TOP);
		views.add(ModelShot.View.BACK);
		return views;
	}
}
