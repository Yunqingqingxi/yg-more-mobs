package com.yunxigames.mobkit;

import net.minecraft.client.model.geom.ModelPart;

import javax.imageio.ImageIO;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * 生物外观预览的核心 API：把若干「模型部件 + 贴图」用正交相机渲染成三视图 PNG。
 *
 * <p>用法（三步）：
 * <pre>{@code
 * ModelPart root = PhantomModel.createBodyLayer().bakeRoot();   // 1. 烘焙真实模型
 * Preview preview = new Preview("我的生物", 900, 700,
 *         List.of(new Preview.Part(root.getChild("body"), Texture.load("/assets/.../x.png"))));
 * ModelShot.render(preview, Path.of("out"));                    // 2. 渲染
 * }</pre>
 *
 * <p>为什么用<b>正交投影</b>而不是透视：三视图要的是能对齐比较尺寸的工程图，
 * 而且模型单位恰好对应像素，缩放一目了然；透视会让远处部件变小，反而不好判断对位。
 *
 * <p>多个 {@code Part} 按列表顺序绘制、共用同一个深度缓冲 —— 所以「先画 A 再画 B」
 * 就等于游戏里"两趟提交"的先后，先画的会被后画的正确遮挡。
 * 这一点直接对应 {@code PhantomCreeperRenderer} 的实现（第一趟幻翼、第二趟苦力怕）。
 */
public final class ModelShot {

	/** 相机方位：{@code azimuth} 绕竖直轴（0 = 站在 +Z 侧看向 -Z），{@code elevation} 抬头为正。 */
	public record View(String name, float azimuthDeg, float elevationDeg) {
		public static final View FRONT = new View("front", 0.0F, 8.0F);
		public static final View SIDE = new View("side", 90.0F, 8.0F);
		public static final View TOP = new View("top", 0.0F, 89.0F);
		public static final View BACK = new View("back", 180.0F, 8.0F);

		/**
		 * 等轴/斜视机位：同时看到正面、顶面与侧面。
		 *
		 * <p><b>和"参考图"比对时必须用这类机位</b>：正交三视图里每一张都只包含参考图的一部分
		 * 信息（正面图看不到顶面与侧面），拿它逐像素比必然低分，比出来的相似度没有意义。
		 *
		 * <p>{@code azimuth=45} 表示相机在 +X+Z 象限（看到实体的右前方）；模型朝向 +Z，
		 * 所以这个位置同时能看到脸、头顶与右侧面。
		 */
		public static final View ISO_RIGHT_FRONT = new View("iso-rf", 45.0F, 30.0F);
		public static final View ISO_LEFT_FRONT = new View("iso-lf", -45.0F, 30.0F);
		public static final View ISO_RIGHT_BACK = new View("iso-rb", 135.0F, 30.0F);
		public static final View ISO_LEFT_BACK = new View("iso-lb", -135.0F, 30.0F);

		/** 默认三视图：正面 / 侧面 / 俯视。 */
		public static final List<View> TRIPLE = List.of(FRONT, SIDE, TOP);

		/** 四个等轴机位：与参考图比对时先挑出最接近的那个。 */
		public static final List<View> ISO_QUAD = List.of(
				ISO_RIGHT_FRONT, ISO_LEFT_FRONT, ISO_RIGHT_BACK, ISO_LEFT_BACK);
	}

	/**
	 * 一个要画的部件。
	 *
	 * @param node      部件子树根（可见性 / skipDraw 由它自己与子部件决定，与游戏一致）
	 * @param texture   这张子树用的贴图
	 * @param rotXDeg   额外姿态修正：绕 X 轴旋转角度（°）
	 * @param rotYDeg   额外姿态修正：绕 Y 轴旋转角度（°）
	 * @param rotZDeg   额外姿态修正：绕 Z 轴旋转角度（°）
	 * @param offsetX   位置微调（模型单位，16 = 1 格）
	 */
	public record Part(ModelPart node, Texture texture,
			float rotXDeg, float rotYDeg, float rotZDeg,
			float offsetX, float offsetY, float offsetZ) {

		/** 不做任何姿态修正 / 位移。 */
		public static Part of(ModelPart node, Texture texture) {
			return new Part(node, texture, 0.0F, 0.0F, 0.0F, 0.0F, 0.0F, 0.0F);
		}

		/** 只做位移。 */
		public static Part offset(ModelPart node, Texture texture, float x, float y, float z) {
			return new Part(node, texture, 0.0F, 0.0F, 0.0F, x, y, z);
		}
	}

	/**
	 * 一份预览定义。
	 *
	 * @param name         名字（用于文件名前缀）
	 * @param width        画布宽
	 * @param height       画布高
	 * @param parts        要画的部件（按顺序绘制，共用深度缓冲）
	 * @param orthoSpan    正交取景范围（模型单位）
	 * @param views        要出的视图
	 */
	public record Preview(String name, int width, int height, List<Part> parts, float orthoSpan, List<View> views) {

		public Preview(String name, int width, int height, List<Part> parts, float orthoSpan) {
			this(name, width, height, parts, orthoSpan, View.TRIPLE);
		}
	}

	private ModelShot() {
	}

	/** 渲染一份预览，每个视图写一个 PNG；返回写出的文件列表。 */
	public static List<Path> render(Preview preview, Path outDir) throws IOException {
		Files.createDirectories(outDir);
		List<Path> written = new ArrayList<>();

		for (View view : preview.views()) {
			Path file = outDir.resolve(preview.name() + "-" + view.name() + ".png");
			renderView(preview, view, file);
			written.add(file);
		}

		return written;
	}

	/** 渲染一个视图，返回着色像素数（诊断用："几何收进来了但一个像素没画"是常见故障）。 */
	public static int renderView(Preview preview, View view, Path file) throws IOException {
		// 输出目录可能不存在（调用方给了多级子目录），这里统一兜底
		if (file.getParent() != null) {
			Files.createDirectories(file.getParent());
		}

		Rasterizer canvas = new Rasterizer(preview.width(), preview.height(), 0xFFAECBEA, 0xFFE6EFFA);
		ModelWalker.Projection projection = projectionFor(view, preview.width(), preview.height(), preview.orthoSpan());

		int painted = 0;
		for (Part part : preview.parts()) {
			applyPose(part);

			// 每个部件单独收集，但共用同一个 canvas（含深度缓冲）——
			// 于是绘制顺序就等于游戏里"两趟提交"的先后，后者正确遮挡前者。
			List<ModelWalker.Face> faces = new ArrayList<>();
			ModelWalker.collect(part.node(), part.texture(), projection, faces);

			for (ModelWalker.Face face : faces) {
				painted += canvas.draw(face);
			}
		}

		ImageIO.write(canvas.toImage(), "PNG", file.toFile());
		return painted;
	}

	/** 把 {@link Part} 上的姿态修正写回部件（部件本身就是可变姿态对象）。 */
	private static void applyPose(Part part) {
		ModelPart node = part.node();
		node.xRot += (float) Math.toRadians(part.rotXDeg());
		node.yRot += (float) Math.toRadians(part.rotYDeg());
		node.zRot += (float) Math.toRadians(part.rotZDeg());
		node.x += part.offsetX();
		node.y += part.offsetY();
		node.z += part.offsetZ();
	}

	/**
	 * 正交投影：世界坐标 → 屏幕坐标（x 右、y 下、z 为朝相机的深度）。
	 *
	 * <p>注意 {@code azimuth=0} 表示相机站在 <b>+Z 侧</b>；实体模型默认朝向 +Z，
	 * 所以正面视图用 azimuth 0。
	 */
	public static ModelWalker.Projection projectionFor(View view, int width, int height, float orthoSpan) {
		double az = Math.toRadians(view.azimuthDeg());
		double el = Math.toRadians(view.elevationDeg());

		float[] toCamera = normalize(new float[] {
				(float) (Math.cos(el) * Math.sin(az)),
				(float) Math.sin(el),
				(float) (Math.cos(el) * Math.cos(az)),
		});

		float[] forward = new float[] { -toCamera[0], -toCamera[1], -toCamera[2] };
		float[] right = normalize(cross(new float[] { 0.0F, 1.0F, 0.0F }, forward));
		float[] up = cross(forward, right);

		float scale = Math.min(width, height) / orthoSpan;

		return (x, y, z) -> {
			float camX = x * right[0] + y * right[1] + z * right[2];
			float camY = x * up[0] + y * up[1] + z * up[2];
			float camZ = x * forward[0] + y * forward[1] + z * forward[2];

			return new float[] {
					width / 2.0F + camX * scale,
					height / 2.0F - camY * scale,
					camZ,
			};
		};
	}

	private static float[] cross(float[] a, float[] b) {
		return new float[] {
				a[1] * b[2] - a[2] * b[1],
				a[2] * b[0] - a[0] * b[2],
				a[0] * b[1] - a[1] * b[0],
		};
	}

	private static float[] normalize(float[] v) {
		float len = (float) Math.sqrt(v[0] * v[0] + v[1] * v[1] + v[2] * v[2]);
		return new float[] { v[0] / len, v[1] / len, v[2] / len };
	}
}
