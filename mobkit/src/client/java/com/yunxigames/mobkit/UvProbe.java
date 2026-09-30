package com.yunxigames.mobkit;

import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.client.model.geom.builders.CubeDeformation;
import net.minecraft.client.model.monster.creeper.CreeperModel;
import net.minecraft.client.model.monster.phantom.PhantomModel;

import java.util.List;

/**
 * UV 探针：打印指定立方体各面的 UV 矩形，用来核对"把某张贴图的某个面贴到另一个模型上"的对应关系。
 *
 * <p>用途举例：把苦力怕的<b>脸</b>贴到幻翼的<b>头正面</b>上时，必须知道两者各自的 UV 矩形，
 * 否则贴上去会错位或拉伸。这个探针把数据直接打出来，省掉反复试错。
 *
 * <p>输出用 ASCII，避免 Windows 控制台编码吞掉结果。
 */
public final class UvProbe {

	public static void main(String[] args) {
		ModelPart phantomRoot = PhantomModel.createBodyLayer().bakeRoot();
		ModelPart creeperRoot = CreeperModel.createBodyLayer(CubeDeformation.NONE).bakeRoot();

		System.out.println("=== VANILLA CREEPER: all cubes with per-face UV rects ===");
		report(creeperRoot, "");

		System.out.println();
		System.out.println("=== VANILLA PHANTOM: all cubes with per-face UV rects ===");
		report(phantomRoot, "");
	}

	private static void report(ModelPart part, String indent) {
		List<ModelPart.Cube> cubes = ModelWalker.cubes(part);

		for (int i = 0; i < cubes.size(); i++) {
			ModelPart.Cube cube = cubes.get(i);
			System.out.println(String.format("%s%s cube#%d  box=(%.0f..%.0f, %.0f..%.0f, %.0f..%.0f)",
					indent, nameOf(part), i, cube.minX, cube.maxX, cube.minY, cube.maxY, cube.minZ, cube.maxZ));

			for (ModelPart.Polygon polygon : cube.polygons) {
				ModelPart.Vertex[] v = polygon.vertices();
				float minU = Float.MAX_VALUE, maxU = -Float.MAX_VALUE;
				float minV = Float.MAX_VALUE, maxV = -Float.MAX_VALUE;

				for (ModelPart.Vertex vertex : v) {
					minU = Math.min(minU, vertex.u());
					maxU = Math.max(maxU, vertex.u());
					minV = Math.min(minV, vertex.v());
					maxV = Math.max(maxV, vertex.v());
				}

				// 原版 UV 是 0~1 归一化；贴图 64x64，所以乘以 64 得到像素矩形
				System.out.println(String.format("%s    normal=(%+.0f,%+.0f,%+.0f)  uv=%.4f..%.4f / %.4f..%.4f  => px %.1f..%.1f / %.1f..%.1f",
						indent,
						polygon.normal().x(), polygon.normal().y(), polygon.normal().z(),
						minU, maxU, minV, maxV,
						minU * 64, maxU * 64, minV * 64, maxV * 64));
			}
		}

		for (ModelPart child : ModelWalker.children(part)) {
			report(child, indent + "  ");
		}
	}

	private static String nameOf(ModelPart part) {
		if (part.hasChild("head") && part.hasChild("body")) {
			return "ROOT";
		}
		if (part.hasChild("head")) {
			return "CREEPER-BODY(=root's body)";
		}
		return "part";
	}

	private UvProbe() {
	}
}
