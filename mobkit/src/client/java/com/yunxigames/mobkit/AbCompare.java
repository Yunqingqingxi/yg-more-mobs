package com.yunxigames.mobkit;

import com.yunxigames.mobs.client.PhantomCreeperModel;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.client.model.geom.builders.CubeDeformation;
import net.minecraft.client.model.monster.creeper.CreeperModel;
import net.minecraft.client.model.monster.phantom.PhantomModel;

import java.awt.image.BufferedImage;
import java.nio.file.Path;
import java.util.List;

/**
 * A/B 对比：两种实现路线渲成同机位的图，直接比。
 *
 * <p><b>方案 A（"改材质"）</b>：完全用原生幻翼模型，只把贴图换成
 * 「苦力怕的头 + 躯干贴到幻翼头/躯干对应的 UV 区、翅膀尾巴保留原版」的合成贴图。
 * 优点：轮廓与原生幻翼 100% 一致（因为模型没动），实现最简单、最稳。
 * 缺点：幻翼的头是 7×3×5 的<b>扁</b>盒子，苦力怕的脸贴上去会被压扁，不可能像方块头。
 *
 * <p><b>方案 B（"换几何"）</b>：保留幻翼的翅膀/尾巴，把头和躯干换成苦力怕的方块几何。
 * 优点：头是完整的方块，苦力怕脸不形变。缺点：姿态（旋转/对位）要调，容易对不上。
 *
 * <p>两条路线的取舍在这里一次看清，避免反复来回改。
 */
public final class AbCompare {

	private static final int W = 900;
	private static final int H = 700;

	public static void main(String[] args) throws Exception {
		Path outDir = Path.of(args.length > 0 ? args[0] : "mobkit-out/ab");

		Texture phantomTex = Texture.load("/assets/minecraft/textures/entity/phantom/phantom.png");
		Texture creeperTex = Texture.load("/assets/yg_mobs/textures/entity/creeper.png");
		Texture composite = compositeTexture(phantomTex, creeperTex);

		// 与参考图一致的 3/4 俯视斜角
		List<ModelShot.View> views = List.of(ModelShot.View.ISO_RIGHT_FRONT, ModelShot.View.ISO_LEFT_FRONT);

		// ---- 方案 A：原生幻翼模型 + 合成贴图 ----
		for (ModelShot.View view : views) {
			ModelPart root = PhantomModel.createBodyLayer().bakeRoot();
			applyWingPose(root.getChild("body"));

			ModelShot.Preview preview = new ModelShot.Preview("A-texture", W, H,
					List.of(ModelShot.Part.of(root, composite)), 34.0F, List.of(view));
			Path file = outDir.resolve("A-texture-" + view.name() + ".png");
			ModelShot.renderView(preview, view, file);
			System.out.println("[ab] A 方案写出 " + file.getFileName());
		}

		// ---- 方案 B：苦力怕几何（当前实现），并按不同缩放对比 ----
		// 苦力怕躯干 8×12×4（躺平后长 20）相对幻翼翅膀（展开约 32）偏大，
		// 1:1 时在俯视角下会横在镜头与翅膀之间、把翅膀挡掉。这里扫几档缩放。
		float[] scales = { 1.0F, 0.75F, 0.6F, 0.5F };

		for (float scale : scales) {
			for (ModelShot.View view : views) {
				ModelPart phantomRoot = PhantomModel.createBodyLayer().bakeRoot();
				ModelPart creeperRoot = CreeperModel.createBodyLayer(CubeDeformation.NONE).bakeRoot();
				new PhantomCreeperModel(phantomRoot, creeperRoot);

				ModelPart phantomBody = phantomRoot.getChild(PhantomCreeperModel.PHANTOM_KEY);
				applyWingPose(phantomBody);

				creeperRoot.xRot += (float) Math.toRadians(Previews.CREEPER_ROT_X_DEG);
				creeperRoot.yRot += (float) Math.toRadians(Previews.CREEPER_ROT_Y_DEG);
				creeperRoot.getChild("head").xRot += (float) Math.toRadians(Previews.CREEPER_HEAD_FIX_X_DEG);
				creeperRoot.xScale = scale;
				creeperRoot.yScale = scale;
				creeperRoot.zScale = scale;

				// 对位随缩放同比调整（偏移量是在缩放后的空间里生效的）
				float oy = Previews.CREEPER_OFFSET_Y;
				float oz = Previews.CREEPER_OFFSET_Z;

				ModelShot.Preview preview = new ModelShot.Preview("B", W, H,
						List.of(
								ModelShot.Part.of(phantomBody, phantomTex),
								new ModelShot.Part(creeperRoot, creeperTex, 0, 0, 0, 0.0F, oy, oz)),
						34.0F, List.of(view));

				String tag = "B-scale" + (int) (scale * 100);
				Path file = outDir.resolve(tag + "-" + view.name() + ".png");
				ModelShot.renderView(preview, view, file);
				System.out.println("[ab] " + tag + " 写出 " + file.getFileName());
			}
		}

		System.out.println("[ab] 完成：" + outDir.toAbsolutePath());
	}

	/**
	 * 合成贴图：把苦力怕贴图贴到幻翼贴图的<b>头与躯干</b> UV 区域，翅膀 / 尾巴保留幻翼原样。
	 *
	 * <p>UV 矩形由 {@link UvProbe} 实测（贴图 64×64，单位是像素）：
	 * <pre>
	 *   幻翼头  正面 px(8,5)-(15,8)   侧面 px(0,5)-(5,8)   顶/底面 px(5,0)-(12,5)
	 *   幻翼躯干 正面 px(9,17)-(14,20) 侧面 px(0,17)-(9,20) 顶/底面 px(9,8)-(14,17)
	 *   苦力怕头  正面 px(8,8)-(16,16) （其余面同理，按 8×8 分块）
	 *   苦力怕躯干 正面 px(20,40)-(28,64) 等
	 * </pre>
	 *
	 * <p>做法是<b>按面逐个搬</b>：把苦力怕对应面的像素缩放到幻翼对应面的矩形里。
	 * 因为幻翼头只有 3 像素高、苦力怕脸有 8 像素高，纵向必然被压缩 —— 这是"只改材质"
	 * 这条路线的固有代价，方案 A 的对比图会如实体现出来。
	 */
	private static Texture compositeTexture(Texture phantomTex, Texture creeperTex) {
		BufferedImage out = new BufferedImage(64, 64, BufferedImage.TYPE_INT_ARGB);
		out.setRGB(0, 0, 64, 64, phantomTex.rawPixels(), 0, 64);

		// —— 幻翼头各面 → 贴苦力怕头对应面 ——
		blit(out, creeperTex, 8, 8, 8, 8, 8, 5, 8, 3);      // 正面
		blit(out, creeperTex, 0, 8, 8, 8, 0, 5, 5, 3);      // 侧面（左）
		blit(out, creeperTex, 16, 8, 8, 8, 5, 0, 7, 5);     // 顶面
		blit(out, creeperTex, 24, 8, 8, 8, 8, 0, 7, 5);     // 底面
		blit(out, creeperTex, 16, 8, 8, 8, 15, 5, 5, 3);    // 另一侧
		blit(out, creeperTex, 24, 8, 8, 8, 8, 5, 7, 3);     // 背面

		// —— 幻翼躯干各面 → 贴苦力怕躯干对应面 ——
		blit(out, creeperTex, 20, 40, 8, 24, 9, 17, 5, 3);  // 正面
		blit(out, creeperTex, 16, 40, 4, 24, 0, 17, 9, 3);  // 侧面
		blit(out, creeperTex, 20, 32, 8, 8, 9, 8, 5, 9);    // 顶面
		blit(out, creeperTex, 28, 32, 8, 8, 9, 8, 5, 9);    // 底面

		int[] pixels = new int[64 * 64];
		out.getRGB(0, 0, 64, 64, pixels, 0, 64);
		return new Texture(64, 64, pixels);
	}

	/** 把源图的一块矩形最近邻缩放后搬进目标图的一块矩形。 */
	private static void blit(BufferedImage dst, Texture src,
			int sx, int sy, int sw, int sh,
			int dx, int dy, int dw, int dh) {

		for (int y = 0; y < dh; y++) {
			for (int x = 0; x < dw; x++) {
				int srcX = sx + x * sw / Math.max(1, dw);
				int srcY = sy + y * sh / Math.max(1, dh);
				int texel = src.samplePx(srcX, srcY);

				if (((texel >>> 24) & 0xFF) == 0) {
					continue;   // 源透明则保留幻翼原像素
				}

				dst.setRGB(dx + x, dy + y, texel);
			}
		}
	}

	private static void applyWingPose(ModelPart body) {
		float angle = (float) (Math.cos(0.375D * 7.448451D) * 16.0D) * 0.017453292F;

		ModelPart leftBase = body.getChild("left_wing_base");
		ModelPart rightBase = body.getChild("right_wing_base");

		leftBase.zRot = angle;
		leftBase.getChild("left_wing_tip").zRot = angle;
		rightBase.zRot = -angle;
		rightBase.getChild("right_wing_tip").zRot = -angle;
	}

	private AbCompare() {
	}
}
