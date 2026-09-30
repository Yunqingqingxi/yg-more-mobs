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
import java.util.Comparator;
import java.util.List;

/**
 * 姿态搜索：自动找出「苦力怕头身」在幻翼身上的正确朝向。
 *
 * <p><b>为什么要搜索</b>：把"立着"的苦力怕变成成品图里"躯干横躺 + 头立在前端上方 + 脸朝前"
 * 需要绕 X 转 90°（躺平），但那一步会把脸一起转歪，于是还要再加一段绕别的轴的修正 ——
 * 旋转次序与符号稍有偏差就会出现"脸朝下 / 脸朝后 / 头跑到躯干下面 / 躯干仍然竖着"等情况。
 * 手推旋转矩阵来回绕极易出错（本项目已经连错三次），所以改成：
 * <b>枚举候选 → 逐张渲染 → 用能从像素直接算出来的判据打分 → 选最优</b>。
 *
 * <p>判据（都只依赖正视图渲染结果）：
 * <ol>
 *   <li><b>脸看得见</b>：五官（深色）像素占绿色像素的比例要大 —— 脸朝着镜头才会大；</li>
 *   <li><b>脸在头部</b>：五官垂直重心落在绿色包围盒的<b>偏上方</b>（头在前端上方，脸随之在上半部）；</li>
 *   <li><b>躯干躺平</b>：正视图里整体轮廓<b>宽 > 高</b>（横躺的躯干会摊开宽度）。</li>
 * </ol>
 *
 * <p>每个候选都<b>重新烘焙模型</b>，避免姿态在同一个 ModelPart 上累积
 * （{@code rotX +=} 是累加的，重用实例会让第二个候选的旋转翻倍）。
 */
public final class PoseSearch {

	private static final int WIDTH = 900;
	private static final int HEIGHT = 700;
	private static final float ORTHO_SPAN = 34.0F;

	/**
	 * 候选旋转：绕 X / 绕 Y / 绕 Z（度）。
	 *
	 * <p>前两组是"目标明确"的推导结果 —— 要求同时满足
	 * 「躯干长轴躺到 Z 轴」「脸朝 +Z（前）」「头的上方仍朝 +Y（正立）」。
	 * 推导给出的候选组合（躯干绕 X ±90 放平 + 头绕 X ∓90 补正）在列表最前面，
	 * 其余是按轴枚举出来用于兜底对比的。
	 */
	private static final float[] TARGET_ROTS = new float[0];

	/** 每个候选：(躯干绕X, 躯干绕Y, 躯干绕Z, 头额外绕X) */
	private static final float[][] CANDIDATES = {
			// —— 目标明确组：躯干放平 + 头单独补正 ——
			{ -90.0F, 0.0F, 0.0F, -90.0F },
			{ -90.0F, 0.0F, 0.0F, 90.0F },
			{ -90.0F, 0.0F, 0.0F, 180.0F },
			{ 90.0F, 0.0F, 0.0F, -90.0F },
			{ 90.0F, 0.0F, 0.0F, 90.0F },
			{ 90.0F, 0.0F, 0.0F, 180.0F },
			{ -90.0F, 180.0F, 0.0F, -90.0F },
			{ 90.0F, 180.0F, 0.0F, -90.0F },
			{ 90.0F, 180.0F, 0.0F, 90.0F },
			{ -90.0F, 180.0F, 0.0F, 90.0F },
			// —— 兜底：只转躯干，不做头部修正 ——
			{ -90.0F, 0.0F, 0.0F, 0.0F },
			{ 90.0F, 0.0F, 0.0F, 0.0F },
			{ 180.0F, 0.0F, 0.0F, 0.0F },
			{ 0.0F, 0.0F, -90.0F, 0.0F },
			{ 0.0F, 0.0F, 90.0F, 0.0F },
	};

	public static void main(String[] args) throws Exception {
		Path outDir = Path.of(args.length > 0 ? args[0] : "mobkit-out/pose-search");
		Files.createDirectories(outDir);

		Texture creeperTex = Texture.load("/assets/yg_mobs/textures/entity/creeper.png");
		List<Score> scores = new ArrayList<>();

		int i = 0;
		for (float[] rot : CANDIDATES) {
			i++;

			// 每个候选都重新烘焙 —— 姿态是写在 ModelPart 上的，重用实例会累积
			ModelPart phantomRoot = PhantomModel.createBodyLayer().bakeRoot();
			ModelPart creeperRoot = CreeperModel.createBodyLayer(CubeDeformation.NONE).bakeRoot();
			new PhantomCreeperModel(phantomRoot, creeperRoot);

			ModelPart phantomBody = phantomRoot.getChild(PhantomCreeperModel.PHANTOM_KEY);
			applyPhantomWingPose(phantomBody);

			// 头单独补正：躯干绕 X 放平时会把头一起带倒，靠这一步把脸扶正
			if (rot[3] != 0.0F) {
				creeperRoot.getChild("head").xRot += (float) Math.toRadians(rot[3]);
			}

			ModelShot.Preview preview = new ModelShot.Preview("cand", WIDTH, HEIGHT,
					List.of(new ModelShot.Part(creeperRoot, creeperTex,
							rot[0], rot[1], rot[2], 0.0F, 0.0F, 0.0F)),
					ORTHO_SPAN, List.of(ModelShot.View.FRONT, ModelShot.View.SIDE));

			Path file = outDir.resolve(String.format("c%02d-bodyX%+.0f-Y%+.0f-Z%+.0f-headX%+.0f",
					i, rot[0], rot[1], rot[2], rot[3]));
			ModelShot.renderView(preview, ModelShot.View.FRONT, Path.of(file + "-front.png"));
			ModelShot.renderView(preview, ModelShot.View.SIDE, Path.of(file + "-side.png"));

			Score score = score(Path.of(file + "-front.png"), rot);
			scores.add(score);
		}

		scores.sort(Comparator.comparingDouble(Score::total).reversed());

		System.out.println("[poseseek] ===== 排名 =====");
		for (int k = 0; k < scores.size(); k++) {
			Score s = scores.get(k);
			System.out.println(String.format("[poseseek] #%d  躯干X%+.0f Y%+.0f Z%+.0f 头X%+.0f | 脸占比=%.3f 脸重心=%.2f 宽高比=%.2f | 总分=%.3f",
					k + 1, s.rotX(), s.rotY(), s.rotZ(), s.headFix(), s.faceRatio(), s.faceCentroid(), s.aspect(), s.total()));
		}
	}

	/** 一个候选的评分。 */
	private record Score(float rotX, float rotY, float rotZ, float headFix,
			double faceRatio, double faceCentroid, double aspect, double total) {
	}

	/**
	 * 给一张正视图打分：脸占比（越大越好）、脸重心（越靠上越好）、整体宽高比（越扁越好）。
	 *
	 * <p>总分刻意让「脸看得见」占主导 —— 之前有一版权重主次颠倒，
	 * 结果选出一个"形状像但整张脸都看不见"的候选。
	 */
	private static Score score(Path file, float[] rot) throws Exception {
		BufferedImage image = ImageIO.read(file.toFile());
		int w = image.getWidth();
		int h = image.getHeight();

		int minX = w, maxX = -1, minY = h, maxY = -1;
		int green = 0;
		int dark = 0;
		long darkSumY = 0;

		for (int y = 0; y < h; y++) {
			for (int x = 0; x < w; x++) {
				int argb = image.getRGB(x, y);
				if (!isGreen(argb)) {
					continue;
				}

				green++;
				if (x < minX) minX = x;
				if (x > maxX) maxX = x;
				if (y < minY) minY = y;
				if (y > maxY) maxY = y;

				if (isDark(argb)) {
					dark++;
					darkSumY += y;
				}
			}
		}

		if (green < 50 || maxY < 0) {
			return new Score(rot[0], rot[1], rot[2], rot.length > 3 ? rot[3] : 0.0F, 0, 0, 0, 0);
		}

		int boxW = Math.max(1, maxX - minX + 1);
		int boxH = Math.max(1, maxY - minY + 1);

		double faceRatio = (double) dark / green;
		double faceCentroid = dark == 0 ? 2.0
				: ((double) darkSumY / dark - minY) / boxH;

		// 横躺的躯干 → 轮廓宽 > 高，所以用 宽/高 越大越好
		double aspect = (double) boxW / boxH;

		double total = faceRatio * 10.0                                  // 脸必须看得见（主导项）
				+ Math.max(0.0, 0.75 - faceCentroid) * 2.0               // 脸偏上（在头部）
				+ Math.min(aspect, 3.0) * 0.5;                           // 躯干躺平摊开宽度

		return new Score(rot[0], rot[1], rot[2], rot.length > 3 ? rot[3] : 0.0F,
				faceRatio, faceCentroid, aspect, total);
	}

	/** 苦力怕皮肤：绿为主、不透明。 */
	private static boolean isGreen(int argb) {
		if (((argb >>> 24) & 0xFF) < 8) {
			return false;
		}

		int r = (argb >> 16) & 0xFF;
		int g = (argb >> 8) & 0xFF;
		int b = argb & 0xFF;
		return g > r + 12 && g > b + 12;
	}

	/** 五官：贴图里脸是深灰/黑，被光照压暗后仍明显暗于皮肤。 */
	private static boolean isDark(int argb) {
		int r = (argb >> 16) & 0xFF;
		int g = (argb >> 8) & 0xFF;
		int b = argb & 0xFF;
		return r < 70 && g < 80 && b < 70;
	}

	private static void applyPhantomWingPose(ModelPart body) {
		float angle = (float) (Math.cos(0.375D * 7.448451D) * 16.0D) * 0.017453292F;

		ModelPart leftBase = body.getChild("left_wing_base");
		ModelPart rightBase = body.getChild("right_wing_base");

		leftBase.zRot = angle;
		leftBase.getChild("left_wing_tip").zRot = angle;
		rightBase.zRot = -angle;
		rightBase.getChild("right_wing_tip").zRot = -angle;
	}

	private PoseSearch() {
	}
}
