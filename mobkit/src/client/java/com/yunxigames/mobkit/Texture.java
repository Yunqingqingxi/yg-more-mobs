package com.yunxigames.mobkit;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.InputStream;

/**
 * 一张实体贴图（含采样）。
 *
 * <p><b>UV 单位</b>：{@code ModelPart.Vertex} 里的 u/v 是 <b>0~1 归一化</b>坐标
 * （实测范围 u[0,0.938]、v[0,1]），<b>不是</b>「1/16 像素」的纹理像素坐标。
 * 这一点踩过坑：按像素单位采样会一直打在左上角那个透明像素上，结果一个面都画不出来
 * （表现是"几何全对、着色像素 0"）。
 */
public record Texture(int width, int height, int[] pixels) {

	/** 从 classpath 读一张 PNG。 */
	public static Texture load(String resource) throws IOException {
		try (InputStream in = Texture.class.getResourceAsStream(resource)) {
			if (in == null) {
				throw new IOException("classpath 上找不到贴图：" + resource);
			}

			BufferedImage image = ImageIO.read(in);
			if (image == null) {
				throw new IOException("贴图解码失败：" + resource);
			}

			int w = image.getWidth();
			int h = image.getHeight();
			int[] pixels = new int[w * h];
			image.getRGB(0, 0, w, h, pixels, 0, w);
			return new Texture(w, h, pixels);
		}
	}

	/** 归一化 UV → 贴图像素（就近取整，钳制在边界内）。 */
	public int sample(float u, float v) {
		int px = Math.max(0, Math.min(width - 1, Math.round(u * width - 0.5F)));
		int py = Math.max(0, Math.min(height - 1, Math.round(v * height - 0.5F)));
		return pixels[py * width + px];
	}

	/** 该 texel 是否透明（alpha 阈值 8，与原版实体剔除行为近似）。 */
	public static boolean isTransparent(int texel) {
		return (texel >>> 24) < 8;
	}

	/** 直接按像素坐标取色（合成贴图等工具用）。 */
	public int samplePx(int px, int py) {
		if (px < 0 || py < 0 || px >= width || py >= height) {
			return 0;
		}

		return pixels[py * width + px];
	}

	/** 像素数组（合成贴图用）。 */
	public int[] rawPixels() {
		return pixels;
	}
}
