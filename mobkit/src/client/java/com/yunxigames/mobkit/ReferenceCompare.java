package com.yunxigames.mobkit;

import com.yunxigames.mobs.client.PhantomCreeperModel;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.client.model.geom.builders.CubeDeformation;
import net.minecraft.client.model.monster.creeper.CreeperModel;
import net.minecraft.client.model.monster.phantom.PhantomModel;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * 与参考图的<b>量化对比</b>：算出当前配置的相似度，并输出一张叠加图。
 *
 * <p>叠加图用红/蓝双色：<b>红 = 本工具渲染的</b>、<b>蓝 = 参考图的</b>、<b>紫 = 重合</b>。
 * 一眼就能看出"哪块多了、哪块少了、哪块位置偏了"，比盯着两张图反复猜高效得多。
 *
 * <p>相似度用的是<b>轮廓 IoU</b>（交并比）：把两张图各自抠出实体掩膜、按包围盒等比归一化后求交集/并集。
 * 这是像素级的客观指标，不依赖主观判断。
 */
public final class ReferenceCompare {

	/** 归一化后用于比对的画布尺寸。 */
	private static final int CW = 320;
	private static final int CH = 222;

	/**
	 * 渲染时的正交取景范围（模型单位）。
	 *
	 * <p>必须比模型实际尺寸大不少：用 34 时模型会溢出画布、掩膜被裁成一整块矩形，
	 * 于是"两个矩形求 IoU"永远得到同一个数（实测 6 个机位分数完全相同 = 0.426，
	 * 这就是取景过窄的特征）。取 70 留足余量。
	 */
	private static final float ORTHO = 70.0F;

	/**
	 * 判"离背景足够远"的阈值。
	 *
	 * <p>参考图的背景是<b>渐变</b>（中心 RGB≈136,129,170，角落≈58,69,98），
	 * 用固定基准色 + 小阈值（34）会把渐变部分误判成实体，包围盒直接撑满整幅图。
	 * 提到 70 后只有真正的模型像素能通过。
	 */
	private static final double BACKGROUND_DISTANCE = 70.0;

	public static void main(String[] args) throws Exception {
		Path reference = Path.of(args.length > 0 ? args[0]
				: "mobkit/reference/phantom-creeper-target.png");
		Path outDir = Path.of(args.length > 1 ? args[1] : "mobkit-out/compare");
		Files.createDirectories(outDir);

		// 参考图掩膜
		int[][] refRaw = loadMask(reference);
		boolean[][] ref = maskOf(refRaw);

		Texture phantomTex = Texture.load("/assets/minecraft/textures/entity/phantom/phantom.png");
		Texture creeperTex = Texture.load("/assets/yg_mobs/textures/entity/creeper.png");

		// 当前配置（与 Previews 保持一致）：3/4 俯视斜角 + 苦力怕几何 0.6 缩放
		int[][] mineRaw = renderCurrent(ModelShot.View.ISO_RIGHT_FRONT, phantomTex, creeperTex);
		boolean[][] mine = maskOf(mineRaw);

		// 诊断：两个掩膜在归一化前后的规模，定位"谁被撑满了"
		System.out.println("[compare] 参考 原始掩膜：" + describe(refRaw) + " → 归一化后 " + describe(ref));
		System.out.println("[compare] 本工具 原始掩膜：" + describe(mineRaw) + " → 归一化后 " + describe(mine));

		double iou = iou(ref, mine);
		System.out.println(String.format("[compare] 轮廓 IoU = %.3f  （交集 %d / 并集 %d）",
				iou, intersect(ref, mine), union(ref, mine)));

		writeOverlay(ref, mine, outDir.resolve("overlay.png"));
		System.out.println("[compare] 叠加图（红=本工具 / 蓝=参考 / 紫=重合）已写出到 "
				+ outDir.resolve("overlay.png").toAbsolutePath());

		// 顺带扫几个机位，看看换个角度能不能更高
		List<ModelShot.View> views = List.of(
				ModelShot.View.ISO_RIGHT_FRONT, ModelShot.View.ISO_LEFT_FRONT,
				ModelShot.View.ISO_RIGHT_BACK, ModelShot.View.ISO_LEFT_BACK,
				ModelShot.View.FRONT, ModelShot.View.SIDE);

		System.out.println("[compare] 各机位下的 IoU：");
		for (ModelShot.View v : views) {
			double s = iou(ref, maskOf(renderCurrent(v, phantomTex, creeperTex)));
			System.out.println(String.format("[compare]   %-8s %.3f", v.name(), s));
		}
	}

	/** 描述一个掩膜：实体像素数与包围盒尺寸。 */
	private static String describe(int[][] mask) {
		int h = mask.length;
		int w = mask[0].length;
		int minX = w, maxX = -1, minY = h, maxY = -1;
		int n = 0;

		for (int y = 0; y < h; y++) {
			for (int x = 0; x < w; x++) {
				if (mask[y][x] != 0) {
					n++;
					if (x < minX) minX = x;
					if (x > maxX) maxX = x;
					if (y < minY) minY = y;
					if (y > maxY) maxY = y;
				}
			}
		}

		return String.format("%dx%d 画布, 实体 %d px, 包围盒 %dx%d",
				w, h, n, Math.max(0, maxX - minX + 1), Math.max(0, maxY - minY + 1));
	}

	private static String describe(boolean[][] mask) {
		int h = mask.length;
		int w = mask[0].length;
		int minX = w, maxX = -1, minY = h, maxY = -1;
		int n = 0;

		for (int y = 0; y < h; y++) {
			for (int x = 0; x < w; x++) {
				if (mask[y][x]) {
					n++;
					if (x < minX) minX = x;
					if (x > maxX) maxX = x;
					if (y < minY) minY = y;
					if (y > maxY) maxY = y;
				}
			}
		}

		return String.format("%dx%d 画布, 实体 %d px, 包围盒 %dx%d",
				w, h, n, Math.max(0, maxX - minX + 1), Math.max(0, maxY - minY + 1));
	}

	/** 用当前配置渲染一个视图（与 Previews 的参数完全一致）。 */
	private static int[][] renderCurrent(ModelShot.View view, Texture phantomTex, Texture creeperTex) {
		ModelPart phantomRoot = PhantomModel.createBodyLayer().bakeRoot();
		ModelPart creeperRoot = CreeperModel.createBodyLayer(CubeDeformation.NONE).bakeRoot();
		new PhantomCreeperModel(phantomRoot, creeperRoot);

		ModelPart phantomBody = phantomRoot.getChild(PhantomCreeperModel.PHANTOM_KEY);
		applyWingPose(phantomBody);

		creeperRoot.xRot += (float) Math.toRadians(Previews.CREEPER_ROT_X_DEG);
		creeperRoot.yRot += (float) Math.toRadians(Previews.CREEPER_ROT_Y_DEG);
		creeperRoot.getChild("head").xRot += (float) Math.toRadians(Previews.CREEPER_HEAD_FIX_X_DEG);
		creeperRoot.xScale = Previews.CREEPER_SCALE;
		creeperRoot.yScale = Previews.CREEPER_SCALE;
		creeperRoot.zScale = Previews.CREEPER_SCALE;

		ModelShot.Preview preview = new ModelShot.Preview("cmp", CW, CH,
				List.of(
						ModelShot.Part.of(phantomBody, phantomTex),
						new ModelShot.Part(creeperRoot, creeperTex, 0, 0, 0,
								0.0F, Previews.CREEPER_OFFSET_Y, Previews.CREEPER_OFFSET_Z)),
				// 取景范围要留足余量：用 34 时模型会溢出画布，
				// 掩膜被裁成一整块，IoU 就没意义了（实测占满 71040/71040 像素）
				ORTHO,
				List.of(view));

		Rasterizer canvas = Rasterizer.transparent(CW, CH);
		ModelWalker.Projection projection = ModelShot.projectionFor(view, CW, CH, ORTHO);

		for (ModelShot.Part part : preview.parts()) {
			List<ModelWalker.Face> faces = new ArrayList<>();
			ModelWalker.collect(part.node(), part.texture(), projection, faces);
			for (ModelWalker.Face face : faces) {
				canvas.draw(face);
			}
		}

		int[][] out = new int[CH][CW];
		for (int y = 0; y < CH; y++) {
			for (int x = 0; x < CW; x++) {
				out[y][x] = ((canvas.pixelAt(x, y) >>> 24) & 0xFF) >= 8 ? 1 : 0;
			}
		}

		return out;
	}

	/** 读参考图（紫背景）→ 布尔掩膜（按原尺寸）。 */
	private static int[][] loadMask(Path file) throws Exception {
		BufferedImage image = ImageIO.read(file.toFile());
		int w = image.getWidth();
		int h = image.getHeight();
		int[][] mask = new int[h][w];

		for (int y = 0; y < h; y++) {
			for (int x = 0; x < w; x++) {
				int argb = image.getRGB(x, y);
				int r = (argb >> 16) & 0xFF;
				int g = (argb >> 8) & 0xFF;
				int b = argb & 0xFF;

				double dist = Math.sqrt(sq(r - 136) + sq(g - 129) + sq(b - 170));
				mask[y][x] = dist < BACKGROUND_DISTANCE ? 0 : 1;
			}
		}

		return mask;
	}

	/**
	 * 抠包围盒 → 等比缩放居中到 CW×CH。
	 *
	 * <p>这一步是为了把"相机距离/取景范围"这个未知量消掉：参考图是游戏里截的，
	 * 它把模型拍多大我们不知道，而形状比例才是要比的东西。
	 */
	private static boolean[][] maskOf(int[][] src) {
		int h = src.length;
		int w = src[0].length;

		int minX = w, maxX = -1, minY = h, maxY = -1;
		for (int y = 0; y < h; y++) {
			for (int x = 0; x < w; x++) {
				if (src[y][x] != 0) {
					if (x < minX) minX = x;
					if (x > maxX) maxX = x;
					if (y < minY) minY = y;
					if (y > maxY) maxY = y;
				}
			}
		}

		boolean[][] out = new boolean[CH][CW];
		if (maxX < 0) {
			return out;
		}

		int boxW = maxX - minX + 1;
		int boxH = maxY - minY + 1;
		double scale = Math.min(CW * 0.94 / boxW, CH * 0.94 / boxH);

		int dw = Math.max(1, (int) Math.round(boxW * scale));
		int dh = Math.max(1, (int) Math.round(boxH * scale));
		int ox = (CW - dw) / 2;
		int oy = (CH - dh) / 2;

		for (int y = 0; y < dh; y++) {
			for (int x = 0; x < dw; x++) {
				int sx = minX + (int) Math.floor(x / scale);
				int sy = minY + (int) Math.floor(y / scale);
				if (sx > maxX || sy > maxY) {
					continue;
				}

				out[oy + y][ox + x] = src[sy][sx] != 0;
			}
		}

		return out;
	}

	private static double iou(boolean[][] a, boolean[][] b) {
		int u = union(a, b);
		return u == 0 ? 0.0 : (double) intersect(a, b) / u;
	}

	private static int intersect(boolean[][] a, boolean[][] b) {
		int n = 0;
		for (int y = 0; y < CH; y++) {
			for (int x = 0; x < CW; x++) {
				if (a[y][x] && b[y][x]) {
					n++;
				}
			}
		}
		return n;
	}

	private static int union(boolean[][] a, boolean[][] b) {
		int n = 0;
		for (int y = 0; y < CH; y++) {
			for (int x = 0; x < CW; x++) {
				if (a[y][x] || b[y][x]) {
					n++;
				}
			}
		}
		return n;
	}

	/** 红=本工具、蓝=参考、紫=重合。 */
	private static void writeOverlay(boolean[][] ref, boolean[][] mine, Path file) throws Exception {
		BufferedImage image = new BufferedImage(CW, CH, BufferedImage.TYPE_INT_ARGB);

		for (int y = 0; y < CH; y++) {
			for (int x = 0; x < CW; x++) {
				boolean r = ref[y][x];
				boolean m = mine[y][x];

				int argb;
				if (r && m) {
					argb = 0xFFB060E0;       // 紫：重合
				} else if (m) {
					argb = 0xFFE04040;       // 红：本工具多出来的
				} else if (r) {
					argb = 0xFF3A70E0;       // 蓝：参考图有、本工具缺的
				} else {
					argb = 0xFFF2F2F2;       // 白底
				}

				image.setRGB(x, y, argb);
			}
		}

		ImageIO.write(image, "PNG", file.toFile());
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

	private static double sq(double v) {
		return v * v;
	}

	private ReferenceCompare() {
	}
}
