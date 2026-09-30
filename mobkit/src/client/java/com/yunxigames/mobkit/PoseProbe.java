package com.yunxigames.mobkit;

import com.mojang.blaze3d.vertex.PoseStack;
import com.yunxigames.mobs.client.PhantomCreeperModel;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.client.model.geom.builders.CubeDeformation;
import net.minecraft.client.model.monster.creeper.CreeperModel;
import net.minecraft.client.model.monster.phantom.PhantomModel;
import org.joml.Matrix4f;
import org.joml.Vector4f;

import java.util.ArrayList;
import java.util.List;

/**
 * 姿态探针：把模型各部件在<b>变换之后</b>的世界包围盒打印出来，用数字判断姿态对不对。
 *
 * <p>为什么需要它：判断"躯干躺平没有 / 头在不在躯干前端上方 / 脸朝哪"，
 * 靠肉眼看渲染图非常低效（这个项目在这一点上绕了很久）。直接算包围盒就一目了然：
 * <ul>
 *   <li>躯干躺平 ⟺ 它在 <b>Z 方向的跨度最大</b>（长轴沿 Z），而不是 Y 方向最大；</li>
 *   <li>头在躯干前端上方 ⟺ 头的中心 Z 比躯干大（更靠前）、Y 比躯干高；</li>
 *   <li>脸朝前 ⟺ 头正面的法线指向 +Z，等价于"头的局部 -Z 面"落在世界 +Z 侧。</li>
 * </ul>
 *
 * <p>输出的是每个部件的包围盒尺寸与中心，以及头部"脸面"的外法线方向。
 */
public final class PoseProbe {

	public static void main(String[] args) {
		// 依次报告：无旋转（原版苦力怕）、以及若干候选姿态
		// 输出刻意用 ASCII —— Windows 控制台编码会把中文吞掉，导致看不到结果
		report("vanilla (no rotation)", 0.0F, 0.0F, 0.0F, 0.0F);
		report("bodyX+90 headX-90", 90.0F, 0.0F, 0.0F, -90.0F);
		report("bodyX-90 headX+90", -90.0F, 0.0F, 0.0F, 90.0F);
		report("bodyX-90", -90.0F, 0.0F, 0.0F, 0.0F);
		report("bodyX+90", 90.0F, 0.0F, 0.0F, 0.0F);
		report("bodyX-90 headX-90", -90.0F, 0.0F, 0.0F, -90.0F);
		report("bodyX+90 headX+90", 90.0F, 0.0F, 0.0F, 90.0F);
	}

	private static void report(String label, float bodyX, float bodyY, float bodyZ, float headX) {
		ModelPart phantomRoot = PhantomModel.createBodyLayer().bakeRoot();
		ModelPart creeperRoot = CreeperModel.createBodyLayer(CubeDeformation.NONE).bakeRoot();
		new PhantomCreeperModel(phantomRoot, creeperRoot);

		creeperRoot.xRot += (float) Math.toRadians(bodyX);
		creeperRoot.yRot += (float) Math.toRadians(bodyY);
		creeperRoot.zRot += (float) Math.toRadians(bodyZ);

		ModelPart head = creeperRoot.getChild("head");
		head.xRot += (float) Math.toRadians(headX);

		Bounds body = boundsOf(creeperRoot, "body");
		Bounds headBounds = boundsOf(head, "head");

		String flat = body.sizeZ() > body.sizeY()
				? "BODY-FLAT (long axis = Z)"
				: "BODY-UPRIGHT (long axis = Y)";

		// 脸面法线：头的局部 -Z 面中点相对头中心的方向
		PoseStack pose = new PoseStack();
		ModelWalker.applyTransform(creeperRoot, pose);
		ModelWalker.applyTransform(head, pose);
		Matrix4f m = new Matrix4f(pose.last().pose());

		Vector4f center = new Vector4f(0.0F, 0.0F, 0.0F, 1.0F).mul(m);
		Vector4f facePoint = new Vector4f(0.0F, 0.0F, -4.0F, 1.0F).mul(m);

		float nx = facePoint.x - center.x;
		float ny = facePoint.y - center.y;
		float nz = facePoint.z - center.z;

		System.out.println("PROBE " + label);
		System.out.println("  body size=" + f(body.sizeX()) + "/" + f(body.sizeY()) + "/" + f(body.sizeZ())
				+ " center=" + f(body.centerX()) + "," + f(body.centerY()) + "," + f(body.centerZ()));
		System.out.println("  head size=" + f(headBounds.sizeX()) + "/" + f(headBounds.sizeY()) + "/" + f(headBounds.sizeZ())
				+ " center=" + f(headBounds.centerX()) + "," + f(headBounds.centerY()) + "," + f(headBounds.centerZ()));
		System.out.println("  face-normal=(" + f(nx) + "," + f(ny) + "," + f(nz) + ") -> " + dominant(nx, ny, nz));
		System.out.println("  " + flat + " | head-above-body=" + (headBounds.centerY() > body.centerY())
				+ " | head-in-front=" + (headBounds.centerZ() > body.centerZ()));
	}

	/** 世界包围盒（由部件的全部立方体角点算出）。 */
	private record Bounds(float minX, float minY, float minZ, float maxX, float maxY, float maxZ) {
		float sizeX() { return maxX - minX; }
		float sizeY() { return maxY - minY; }
		float sizeZ() { return maxZ - minZ; }
		float centerX() { return (minX + maxX) / 2.0F; }
		float centerY() { return (minY + maxY) / 2.0F; }
		float centerZ() { return (minZ + maxZ) / 2.0F; }
	}

	private static Bounds boundsOf(ModelPart root, String ignored) {
		PoseStack pose = new PoseStack();
		List<float[]> points = new ArrayList<>();
		collectPoints(root, pose, points, true);

		float minX = Float.MAX_VALUE, minY = Float.MAX_VALUE, minZ = Float.MAX_VALUE;
		float maxX = -Float.MAX_VALUE, maxY = -Float.MAX_VALUE, maxZ = -Float.MAX_VALUE;

		for (float[] p : points) {
			minX = Math.min(minX, p[0]);
			minY = Math.min(minY, p[1]);
			minZ = Math.min(minZ, p[2]);
			maxX = Math.max(maxX, p[0]);
			maxY = Math.max(maxY, p[1]);
			maxZ = Math.max(maxZ, p[2]);
		}

		return new Bounds(minX, minY, minZ, maxX, maxY, maxZ);
	}

	/** 收集子树里所有立方体角点的世界坐标。 */
	private static void collectPoints(ModelPart part, PoseStack pose, List<float[]> into, boolean isRoot) {
		pose.pushPose();
		ModelWalker.applyTransform(part, pose);

		Matrix4f m = new Matrix4f(pose.last().pose());
		for (ModelPart.Cube cube : cubesOf(part)) {
			for (float x : new float[] { cube.minX, cube.maxX }) {
				for (float y : new float[] { cube.minY, cube.maxY }) {
					for (float z : new float[] { cube.minZ, cube.maxZ }) {
						Vector4f p = new Vector4f(x, y, z, 1.0F).mul(m);
						into.add(new float[] { p.x, p.y, p.z });
					}
				}
			}
		}

		for (ModelPart child : childrenOf(part)) {
			collectPoints(child, pose, into, false);
		}

		pose.popPose();
	}

	private static String dominant(float x, float y, float z) {
		float ax = Math.abs(x), ay = Math.abs(y), az = Math.abs(z);
		if (az >= ax && az >= ay) {
			return z > 0 ? "+Z (FORWARD) OK" : "-Z (BACKWARD) BAD";
		}
		if (ay >= ax) {
			return y > 0 ? "+Y (UP) BAD" : "-Y (DOWN) BAD";
		}
		return x > 0 ? "+X (RIGHT) BAD" : "-X (LEFT) BAD";
	}

	// 反射取私有字段
	private static final java.lang.reflect.Field CUBES = field("cubes");
	private static final java.lang.reflect.Field CHILDREN = field("children");

	private static java.lang.reflect.Field field(String name) {
		try {
			java.lang.reflect.Field f = ModelPart.class.getDeclaredField(name);
			f.setAccessible(true);
			return f;
		} catch (ReflectiveOperationException e) {
			throw new IllegalStateException(e);
		}
	}

	@SuppressWarnings("unchecked")
	private static List<ModelPart.Cube> cubesOf(ModelPart part) {
		try {
			return (List<ModelPart.Cube>) CUBES.get(part);
		} catch (IllegalAccessException e) {
			throw new IllegalStateException(e);
		}
	}

	@SuppressWarnings("unchecked")
	private static java.util.Collection<ModelPart> childrenOf(ModelPart part) {
		try {
			return ((java.util.Map<String, ModelPart>) CHILDREN.get(part)).values();
		} catch (IllegalAccessException e) {
			throw new IllegalStateException(e);
		}
	}

	private static String f(float v) {
		return String.format(java.util.Locale.ROOT, "%+.1f", v);
	}

	private PoseProbe() {
	}
}
