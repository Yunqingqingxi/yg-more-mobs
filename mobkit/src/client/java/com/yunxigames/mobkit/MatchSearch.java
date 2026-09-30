package com.yunxigames.mobkit;

import com.yunxigames.mobs.client.PhantomCreeperModel;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.client.model.geom.builders.CubeDeformation;
import net.minecraft.client.model.monster.creeper.CreeperModel;
import net.minecraft.client.model.monster.phantom.PhantomModel;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * 与参考图做数值比对，自动搜索最接近的姿态。
 *
 * <p><b>为什么不再靠肉眼看</b>：这个项目的姿态调了多轮都对不上，原因是"像不像"没有量化标准。
 * 这里把参考图和渲染结果都归一化成三张<b>分类掩膜</b>，用 IoU 量化相似度，然后直接搜索参数空间。
 *
 * <p>三类的判据：
 * <ul>
 *   <li><b>绿</b>——苦力怕（头 + 躯干）；</li>
 *   <li><b>奶白/米色</b>——幻翼翅膀的外缘带；</li>
 *   <li><b>蓝</b>——幻翼翅膀的内侧。</li>
 * </ul>
 * 只比"颜色分类后的形状分布"，不比逐像素颜色 —— 于是对光照、背景色、渲染方式差异
 * （我们是软件光栅化、参考图是游戏渲染）都免疫，但仍能强约束「哪块是绿的、在哪、多大」。
 *
 * <p>归一化：先抠出实体包围盒，再等比缩放进同一画布并居中，消除缩放与位移的影响，
 * 使比对专注于<b>形状与朝向</b>。
 */
public final class MatchSearch {

	/** 归一化画布尺寸：渲染与参考图都降到这个尺寸再做像素级比对，保证可搜索。 */
	private static final int NORM_W = 220;
	private static final int NORM_H = 152;

	/** 参考图降采样后的尺寸（与渲染画布同数量级，比对时再按质心对齐）。 */
	private static final int TARGET_W = 300;
	private static final int TARGET_H = 208;

	private static final int CLASS_GREEN = 0;
	private static final int CLASS_CREAM = 1;
	private static final int CLASS_BLUE = 2;

	public static void main(String[] args) throws Exception {
		Path reference = Path.of(args.length > 0 ? args[0]
				: "mobkit/reference/phantom-creeper-target.png");
		Path outDir = Path.of(args.length > 1 ? args[1] : "mobkit-out/match");
		Files.createDirectories(outDir);

		int[][] target = downsample(loadTarget(reference), TARGET_W, TARGET_H);
		System.out.println("[match] 参考图已载入并按 " + TARGET_W + "x" + TARGET_H + " 降采样");

		Texture phantomTex = Texture.load("/assets/minecraft/textures/entity/phantom/phantom.png");
		Texture creeperTex = Texture.load("/assets/yg_mobs/textures/entity/creeper.png");

		// 搜索空间：机位（方位/俯仰都搜，因为参考图的机位是未知的）
		//           × 躯干绕 X（唯一能把躯干放平的轴：±90）
		//           × 头 X 修正（0/±90/180）
		List<ModelShot.View> views = new ArrayList<>();
		for (float az = 0.0F; az < 360.0F; az += 15.0F) {
			for (float el = 12.0F; el <= 48.0F; el += 12.0F) {
				views.add(new ModelShot.View(String.format("az%03.0f-el%02.0f", az, el), az, el));
			}
		}

		float[] bodyXs = { 90.0F, -90.0F };
		float[] headFixes = { 0.0F, 90.0F, 180.0F, -90.0F };

		// 翅膀后掠角：原版翅膀是向两侧平展的；参考图里翅膀看着更收拢、往后掠，
		// 于是躯干显得比翅膀长。把后掠角也纳入搜索，验证这个猜测。
		float[] wingSweeps = { 0.0F, 15.0F, 30.0F, 45.0F };

		List<Result> results = new ArrayList<>();
		int tested = 0;

		for (ModelShot.View view : views) {
			for (float bx : bodyXs) {
				for (float headX : headFixes) {
					for (float sweep : wingSweeps) {
						Result r = evaluate(view, bx, 0.0F, 0.0F, headX, sweep,
								phantomTex, creeperTex, target);
						tested++;
						results.add(r);
					}
				}
			}
		}

		results.sort(Comparator.comparingDouble(Result::score).reversed());

		System.out.println("[match] 共评估 " + tested + " 组参数。前 10：");
		for (int i = 0; i < Math.min(10, results.size()); i++) {
			Result r = results.get(i);
			System.out.println(String.format(
					"[match] #%d  %s  躯干X%+.0f Y%+.0f Z%+.0f  头X%+.0f  Z偏移%+.0f  | 相似度=%.3f (绿%.3f 奶白%.3f 蓝%.3f)",
					i + 1, r.view().name(), r.bodyX(), r.bodyY(), r.bodyZ(), r.headX(), r.wingSweep(),
					r.score(), r.iouGreen(), r.iouCream(), r.iouBlue()));
		}

		// 把最优结果按 1:1 渲染出来，方便直接看
		Result best = results.get(0);
		Path bestFile = outDir.resolve("best.png");
		renderTo(best, phantomTex, creeperTex, bestFile);
		System.out.println("[match] 最优结果已渲染到 " + bestFile.toAbsolutePath());
	}

	/** 躯干是否躺平（长轴从 Y 转到 Z）：绕 X 转 ±90 才能做到，绕 Y/Z 转都不行。 */
	private static boolean laysBodyFlat(float bx, float by, float bz) {
		return Math.abs(bx) == 90.0F && by == 0.0F && bz == 0.0F;
	}

	private record Result(ModelShot.View view, float bodyX, float bodyY, float bodyZ,
			float headX, float wingSweep, double score,
			double iouGreen, double iouCream, double iouBlue) {
	}

	/** 评估一组参数：渲染 → 与参考图对齐（质心 + 缩放）→ 三类 IoU。 */
	private static Result evaluate(ModelShot.View view, float bx, float by, float bz,
			float headX, float wingSweepDeg, Texture phantomTex, Texture creeperTex, int[][] target) {

		int[][] rendered = renderSilhouette(view, bx, by, bz, headX, wingSweepDeg, phantomTex, creeperTex);

		// 自己抠包围盒、按质心对齐、只搜缩放 —— 不再用自动拉伸归一化，
		// 否则位移信息会被完全抹掉（偏移参数怎么变分数都一样，等于没搜）。
		Best best = alignAndScore(rendered, target);

		return new Result(view, bx, by, bz, headX, wingSweepDeg,
				best.score(), best.green(), best.cream(), best.blue());
	}

	private record Best(double score, double green, double cream, double blue) {
	}

	/**
	 * 把渲染结果抠出包围盒、按质心对齐到参考图，并搜索最佳缩放（等比，保形状比例），
	 * 返回三类 IoU 的平均分。
	 *
	 * <p>搜索缩放是必要的：参考图里模型占画面的比例未知，而我们渲染用的正交取景范围是估的。
	 * 但<b>只搜缩放、不搜拉伸</b>——等比缩放不会掩盖"躯干太长/太短"这类比例问题。
	 */
	private static Best alignAndScore(int[][] render, int[][] target) {
		Box rb = boxOf(render);
		Box tb = boxOf(target);

		if (rb == null || tb == null) {
			return new Best(0, 0, 0, 0);
		}

		double[] cx = centroid(render, rb);
		double[] tx = centroid(target, tb);

		double bestScore = -1;
		double bestG = 0;
		double bestC = 0;
		double bestB = 0;

		for (double scale = 0.35; scale <= 1.65; scale += 0.05) {
			double g = 0;
			double c = 0;
			double b = 0;

			for (int cls = 0; cls < 3; cls++) {
				double v = iouClass(render, cx, target, tx, scale, cls);
				if (cls == CLASS_GREEN) g = v;
				else if (cls == CLASS_CREAM) c = v;
				else b = v;
			}

			double score = (g + c + b) / 3.0;
			if (score > bestScore) {
				bestScore = score;
				bestG = g;
				bestC = c;
				bestB = b;
			}
		}

		return new Best(bestScore, bestG, bestC, bestB);
	}

	private record Box(int minX, int minY, int maxX, int maxY) {
	}

	private static Box boxOf(int[][] mask) {
		int h = mask.length;
		int w = mask[0].length;
		int minX = w, maxX = -1, minY = h, maxY = -1;

		for (int y = 0; y < h; y++) {
			for (int x = 0; x < w; x++) {
				if (mask[y][x] >= 0) {
					if (x < minX) minX = x;
					if (x > maxX) maxX = x;
					if (y < minY) minY = y;
					if (y > maxY) maxY = y;
				}
			}
		}

		return maxX < 0 ? null : new Box(minX, minY, maxX, maxY);
	}

	/** 某类别像素的质心（用于对齐）。 */
	private static double[] centroid(int[][] mask, Box box) {
		double sx = 0;
		double sy = 0;
		int n = 0;

		for (int y = box.minY(); y <= box.maxY(); y++) {
			for (int x = box.minX(); x <= box.maxX(); x++) {
				if (mask[y][x] >= 0) {
					sx += x;
					sy += y;
					n++;
				}
			}
		}

		return n == 0 ? new double[] { 0, 0 } : new double[] { sx / n, sy / n };
	}

	/** 在给定缩放下，把渲染的某类掩膜按质心对齐到参考，算 IoU。 */
	private static double iouClass(int[][] render, double[] rCentroid,
			int[][] target, double[] tCentroid, double scale, int cls) {

		int rh = render.length;
		int rw = render[0].length;
		int th = target.length;
		int tw = target[0].length;

		int inter = 0;
		int union = 0;

		// 以参考图的像素为基准：对每个参考像素，反查它在渲染图里的对应位置
		for (int ty = 0; ty < th; ty++) {
			for (int tx = 0; tx < tw; tx++) {
				int rx = (int) Math.round((tx - tCentroid[0]) / scale + rCentroid[0]);
				int ry = (int) Math.round((ty - tCentroid[1]) / scale + rCentroid[1]);

				boolean inTarget = target[ty][tx] == cls;
				boolean inRender = rx >= 0 && ry >= 0 && rx < rw && ry < rh && render[ry][rx] == cls;

				if (inTarget && inRender) {
					inter++;
				}
				if (inTarget || inRender) {
					union++;
				}
			}
		}

		// 渲染图里落在参考画布之外的部分也要计入并集，否则小缩放会被高估
		for (int ry = 0; ry < rh; ry++) {
			for (int rx = 0; rx < rw; rx++) {
				if (render[ry][rx] != cls) {
					continue;
				}

				int tx = (int) Math.round((rx - rCentroid[0]) * scale + tCentroid[0]);
				int ty = (int) Math.round((ry - rCentroid[1]) * scale + tCentroid[1]);

				if (tx < 0 || ty < 0 || tx >= tw || ty >= th) {
					union++;
				}
			}
		}

		return union == 0 ? 0.0 : (double) inter / union;
	}

	/** 渲染一份预览并转成"分类掩膜"（每像素一个类别 id，未覆盖为 -1）。 */
	private static int[][] renderSilhouette(ModelShot.View view, float bx, float by, float bz,
			float headX, float wingSweepDeg, Texture phantomTex, Texture creeperTex) {

		// 每次都重新烘焙，避免姿态在 ModelPart 上累积
		ModelPart phantomRoot = PhantomModel.createBodyLayer().bakeRoot();
		ModelPart creeperRoot = CreeperModel.createBodyLayer(CubeDeformation.NONE).bakeRoot();
		new PhantomCreeperModel(phantomRoot, creeperRoot);

		ModelPart phantomBody = phantomRoot.getChild(PhantomCreeperModel.PHANTOM_KEY);
		applyPhantomWingPose(phantomBody, wingSweepDeg);

		creeperRoot.xRot += (float) Math.toRadians(bx);
		creeperRoot.yRot += (float) Math.toRadians(by);
		creeperRoot.zRot += (float) Math.toRadians(bz);
		creeperRoot.getChild("head").xRot += (float) Math.toRadians(headX);

		ModelShot.Preview preview = new ModelShot.Preview("m", NORM_W, NORM_H,
				List.of(
						ModelShot.Part.of(phantomBody, phantomTex),
						new ModelShot.Part(creeperRoot, creeperTex, 0, 0, 0, 0.0F, 0.0F, 0.0F)),
				34.0F, List.of(view));

		Rasterizer canvas = Rasterizer.transparent(NORM_W, NORM_H);
		ModelWalker.Projection projection = ModelShot.projectionFor(view, NORM_W, NORM_H, 34.0F);

		for (ModelShot.Part part : preview.parts()) {
			List<ModelWalker.Face> faces = new ArrayList<>();
			ModelWalker.collect(part.node(), part.texture(), projection, faces);
			for (ModelWalker.Face face : faces) {
				canvas.draw(face);
			}
		}

		int[][] classes = new int[NORM_H][NORM_W];
		for (int y = 0; y < NORM_H; y++) {
			for (int x = 0; x < NORM_W; x++) {
				classes[y][x] = classify(canvas.pixelAt(x, y));
			}
		}

		return classes;
	}

	/** 把最优参数按原尺寸渲染成 PNG（给人看）。 */
	private static void renderTo(Result r, Texture phantomTex, Texture creeperTex, Path file) throws IOException {
		ModelPart phantomRoot = PhantomModel.createBodyLayer().bakeRoot();
		ModelPart creeperRoot = CreeperModel.createBodyLayer(CubeDeformation.NONE).bakeRoot();
		new PhantomCreeperModel(phantomRoot, creeperRoot);

		ModelPart phantomBody = phantomRoot.getChild(PhantomCreeperModel.PHANTOM_KEY);
		applyPhantomWingPose(phantomBody, r.wingSweep());

		creeperRoot.xRot += (float) Math.toRadians(r.bodyX());
		creeperRoot.yRot += (float) Math.toRadians(r.bodyY());
		creeperRoot.zRot += (float) Math.toRadians(r.bodyZ());
		creeperRoot.getChild("head").xRot += (float) Math.toRadians(r.headX());

		ModelShot.Preview preview = new ModelShot.Preview("best", 900, 700,
				List.of(
						ModelShot.Part.of(phantomBody, phantomTex),
						new ModelShot.Part(creeperRoot, creeperTex, 0, 0, 0, 0.0F, 0.0F, 0.0F)),
				34.0F, List.of(r.view()));

		ModelShot.renderView(preview, r.view(), file);
	}

	// ---------------------------------------------------------------- 分类

	/** 渲染像素 → 类别（-1 表示透明/未覆盖）。 */
	private static int classify(int argb) {
		int a = (argb >>> 24) & 0xFF;
		if (a < 8) {
			return -1;
		}

		int r = (argb >> 16) & 0xFF;
		int g = (argb >> 8) & 0xFF;
		int b = argb & 0xFF;

		if (g > r + 10 && g > b + 10) {
			return CLASS_GREEN;
		}

		// 奶白/米色：三通道都高且彼此接近
		if (r > 150 && g > 140 && b > 110 && Math.abs(r - b) < 70) {
			return CLASS_CREAM;
		}

		return CLASS_BLUE;
	}

	/** 参考图（紫背景）→ 类别掩膜。 */
	private static int[][] loadTarget(Path file) throws IOException {
		BufferedImage image = ImageIO.read(file.toFile());
		int w = image.getWidth();
		int h = image.getHeight();

		int[][] classes = new int[h][w];
		for (int y = 0; y < h; y++) {
			for (int x = 0; x < w; x++) {
				int argb = image.getRGB(x, y);
				int r = (argb >> 16) & 0xFF;
				int g = (argb >> 8) & 0xFF;
				int b = argb & 0xFF;

				// 背景是均匀紫（约 136,129,170），按"离背景色足够远"判为实体
				double dist = Math.sqrt(sq(r - 136) + sq(g - 129) + sq(b - 170));
				classes[y][x] = dist < 34.0 ? -1 : classifyTarget(r, g, b);
			}
		}

		return classes;
	}

	private static int classifyTarget(int r, int g, int b) {
		if (g > r + 8 && g > b + 8) {
			return CLASS_GREEN;
		}

		if (r > 140 && g > 130 && b > 100 && Math.abs(r - b) < 75) {
			return CLASS_CREAM;
		}

		return CLASS_BLUE;
	}

	// ---------------------------------------------------------------- 归一化与 IoU

	/**
	 * 最近邻降采样：把掩膜缩到指定尺寸。
	 *
	 * <p>参考图是 1113×771，直接参与逐像素比对太慢（每轮几百万次），
	 * 而降采样到 300×208 对"形状与朝向"的判别力几乎没有损失。
	 */
	private static int[][] downsample(int[][] src, int dstW, int dstH) {
		int h = src.length;
		int w = src[0].length;
		int[][] out = new int[dstH][dstW];

		for (int y = 0; y < dstH; y++) {
			for (int x = 0; x < dstW; x++) {
				int sx = Math.min(w - 1, x * w / dstW);
				int sy = Math.min(h - 1, y * h / dstH);
				out[y][x] = src[sy][sx];
			}
		}

		return out;
	}

	private static double sq(double v) {
		return v * v;
	}

	/**
	 * 幻翼翅膀姿态：{@code sweepDeg} 为后掠角（绕 Y 轴把双翼往后收）。
	 *
	 * <p>原版 {@code PhantomModel.setupAnim} 只摆拍打角（zRot），翅膀是向两侧平展的。
	 * 参考图里翅膀看着更收拢，所以这里额外加一个后掠角做搜索 —— 用来验证
	 * "参考图里翅膀后掠、于是躯干相对更长"这个猜测。
	 */
	private static void applyPhantomWingPose(ModelPart body, float sweepDeg) {
		float angle = (float) (Math.cos(0.375D * 7.448451D) * 16.0D) * 0.017453292F;
		float sweep = (float) Math.toRadians(sweepDeg);

		ModelPart leftBase = body.getChild("left_wing_base");
		ModelPart rightBase = body.getChild("right_wing_base");

		leftBase.zRot = angle;
		leftBase.getChild("left_wing_tip").zRot = angle;
		rightBase.zRot = -angle;
		rightBase.getChild("right_wing_tip").zRot = -angle;

		// 左右对称后掠：左翅往 -X 后方收、右翅往 +X 后方收
		leftBase.yRot = sweep;
		leftBase.getChild("left_wing_tip").yRot = sweep;
		rightBase.yRot = -sweep;
		rightBase.getChild("right_wing_tip").yRot = -sweep;
	}

	private MatchSearch() {
	}
}
