package com.yunxigames.mobkit;

/**
 * 软件光栅化器：把 {@link ModelWalker.Face} 画进一张 ARGB 画布。
 *
 * <p>为什么自己写光栅化而不是用 Minecraft 的渲染管线：后者的延迟提交 / {@code RenderType}
 * 只是"把顶点交给 GPU"的封装，真正的像素计算（三角形填充、深度测试、UV 插值）在着色器里，
 * 拿不出来单独跑。而预览图要的是"模型 + 贴图长什么样"，
 * 这套固定的管线（正交投影 + 深度缓冲 + 最近邻采样 + 单向光）已经足够，
 * 且完全确定性、无需 GPU。
 *
 * <p>已知与游戏内不一致之处（都不影响判断外观）：光照是近似的、没有雾与附魔光效、
 * 没有各向异性过滤（用最近邻，保持像素风）。
 */
public final class Rasterizer {

	/** 主光方向（屏幕空间，已归一化）：左上偏前，接近游戏内实体光照观感。 */
	private static final float[] KEY_LIGHT = normalize(new float[] { -0.42F, -0.72F, 0.55F });

	/** 环境光下限，保证背光面仍看得清贴图。 */
	private static final float AMBIENT = 0.62F;

	private final int width;
	private final int height;
	private final int[] color;
	private final float[] depth;

	public Rasterizer(int width, int height, int skyTop, int skyBottom) {
		this.width = width;
		this.height = height;
		this.color = new int[width * height];
		this.depth = new float[width * height];
		java.util.Arrays.fill(this.depth, Float.NEGATIVE_INFINITY);

		// 天空渐变背景，方便看清剪影
		for (int y = 0; y < height; y++) {
			int rgb = lerpColor(skyTop, skyBottom, (float) y / height);
			for (int x = 0; x < width; x++) {
				this.color[y * width + x] = rgb;
			}
		}
	}

	public int width() {
		return width;
	}

	public int height() {
		return height;
	}

	/**
	 * 全透明背景的画布。
	 *
	 * <p>与参考图比较相似度时必须用这个：这样才能按 <b>alpha 掩膜</b> 把"实体的形状"
	 * 单独抠出来比（IoU / 形状重合度），不受背景色与光照差异影响。
	 */
	public static Rasterizer transparent(int width, int height) {
		return new Rasterizer(width, height, 0x00000000, 0x00000000);
	}

	/** 读某个像素的 ARGB（诊断与比对用）。 */
	public int pixelAt(int x, int y) {
		if (x < 0 || y < 0 || x >= width || y >= height) {
			return 0;
		}

		return color[y * width + x];
	}

	/** 画一个面；返回实际着色像素数（便于诊断"模型有没有画出来"）。 */
	public int draw(ModelWalker.Face face) {
		float[][] xy = face.xy();
		float[][] uv = face.uv();
		int n = xy.length;
		int painted = 0;

		// 四边形拆成三角形扇；只对第一个三角形做背面剔除，避免把四边形误剔掉一半
		for (int i = 1; i + 1 < n; i++) {
			painted += triangle(xy[0], xy[i], xy[i + 1],
					uv[0], uv[i], uv[i + 1],
					face.normal(), face.texture(), i == 1);
		}

		return painted;
	}

	private int triangle(float[] a, float[] b, float[] c,
			float[] ua, float[] ub, float[] uc,
			org.joml.Vector3f normal, Texture texture, boolean cull) {

		float area = edge(a, b, c);
		if (Math.abs(area) < 1.0E-6F) {
			return 0;
		}

		// 屏幕 y 轴向下，所以"朝外"的三角形有向面积为负
		if (cull && area > 0.0F) {
			return 0;
		}

		int minX = (int) Math.max(0, Math.floor(Math.min(a[0], Math.min(b[0], c[0]))));
		int maxX = (int) Math.min(width - 1, Math.ceil(Math.max(a[0], Math.max(b[0], c[0]))));
		int minY = (int) Math.max(0, Math.floor(Math.min(a[1], Math.min(b[1], c[1]))));
		int maxY = (int) Math.min(height - 1, Math.ceil(Math.max(a[1], Math.max(b[1], c[1]))));

		if (minX > maxX || minY > maxY) {
			return 0;
		}

		float brightness = brightness(normal);
		float invArea = 1.0F / area;
		int painted = 0;

		for (int y = minY; y <= maxY; y++) {
			for (int x = minX; x <= maxX; x++) {
				float px = x + 0.5F;
				float py = y + 0.5F;

				float w0 = edge(b, c, px, py);
				float w1 = edge(c, a, px, py);
				float w2 = edge(a, b, px, py);

				boolean inside = area < 0
						? (w0 <= 0 && w1 <= 0 && w2 <= 0)
						: (w0 >= 0 && w1 >= 0 && w2 >= 0);
				if (!inside) {
					continue;
				}

				float l0 = w0 * invArea;
				float l1 = w1 * invArea;
				float l2 = w2 * invArea;

				float z = l0 * a[2] + l1 * b[2] + l2 * c[2];
				int index = y * width + x;
				if (z <= depth[index]) {
					continue;
				}

				float u = l0 * ua[0] + l1 * ub[0] + l2 * uc[0];
				float v = l0 * ua[1] + l1 * ub[1] + l2 * uc[1];

				int texel = texture.sample(u, v);
				if (Texture.isTransparent(texel)) {
					continue;
				}

				depth[index] = z;
				color[index] = shade(texel, brightness);
				painted++;
			}
		}

		return painted;
	}

	/** 输出成图片。 */
	public java.awt.image.BufferedImage toImage() {
		java.awt.image.BufferedImage image = new java.awt.image.BufferedImage(
				width, height, java.awt.image.BufferedImage.TYPE_INT_ARGB);
		image.setRGB(0, 0, width, height, color, 0, width);
		return image;
	}

	private float brightness(org.joml.Vector3f normal) {
		float dot = Math.abs(normal.x * KEY_LIGHT[0] + normal.y * KEY_LIGHT[1] + normal.z * KEY_LIGHT[2]);
		return AMBIENT + (1.0F - AMBIENT) * dot;
	}

	private static int shade(int argb, float brightness) {
		int a = (argb >>> 24) & 0xFF;
		int r = clamp255((int) (((argb >> 16) & 0xFF) * brightness));
		int g = clamp255((int) (((argb >> 8) & 0xFF) * brightness));
		int b = clamp255((int) ((argb & 0xFF) * brightness));
		return (a << 24) | (r << 16) | (g << 8) | b;
	}

	/** 有向面积（用于重心坐标）。 */
	private static float edge(float[] p, float[] q, float px, float py) {
		return (q[0] - p[0]) * (py - p[1]) - (q[1] - p[1]) * (px - p[0]);
	}

	private static float edge(float[] p, float[] q, float[] r) {
		return edge(p, q, r[0], r[1]);
	}

	private static int clamp255(int v) {
		return v < 0 ? 0 : Math.min(v, 255);
	}

	private static int lerpColor(int from, int to, float t) {
		int r = (int) (((from >> 16) & 0xFF) + ((((to >> 16) & 0xFF) - ((from >> 16) & 0xFF)) * t));
		int g = (int) (((from >> 8) & 0xFF) + ((((to >> 8) & 0xFF) - ((from >> 8) & 0xFF)) * t));
		int b = (int) ((from & 0xFF) + (((to & 0xFF) - (from & 0xFF)) * t));
		return 0xFF000000 | (r << 16) | (g << 8) | b;
	}

	private static float[] normalize(float[] v) {
		float len = (float) Math.sqrt(v[0] * v[0] + v[1] * v[1] + v[2] * v[2]);
		return new float[] { v[0] / len, v[1] / len, v[2] / len };
	}
}
