package com.yunxigames.mobkit;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import net.minecraft.client.model.geom.ModelPart;

import java.util.Collection;
import java.util.List;
import java.util.Map;

/**
 * 模型遍历工具：把一棵真实的 {@link ModelPart} 树按原版规则展开成可渲染的面。
 *
 * <p>这里做的三件事都必须与原版 {@code ModelPart} 的行为严格一致，否则预览图会骗人：
 * <ul>
 *   <li><b>变换顺序</b>：位移到部件原点 → 按 Z、Y、X 旋转 → 缩放
 *       （对应原版 {@code ModelPart.translateAndRotate}）；</li>
 *   <li><b>可见性</b>：{@code visible=false} 整棵子树跳过；
 *       {@code skipDraw=true} 只跳过<b>自身方块</b>、子部件照常渲染
 *       —— 这正是"藏躯干、保翅膀"的关键；</li>
 *   <li><b>UV</b>：逐顶点取 {@code ModelPart.Vertex} 里的 u/v（0~1 归一化，
 *       含镜像与纹理翻转标记），所以贴图采样与游戏内一致。</li>
 * </ul>
 *
 * <p>原版把 {@code cubes} / {@code children} 两个字段设为私有且没有公开访问器，
 * 这里用反射读一次并缓存（字段名变了会在类初始化时立刻抛错，不会静默出错）。
 */
public final class ModelWalker {

	/** 一个待渲染的面：屏幕坐标 + UV + 世界法线 + 该用的贴图。 */
	public record Face(float[][] xy, float[][] uv, org.joml.Vector3f normal, Texture texture) {
	}

	private static final java.lang.reflect.Field CUBES = field("cubes");
	private static final java.lang.reflect.Field CHILDREN = field("children");

	private ModelWalker() {
	}

	/**
	 * 收集一棵部件树的全部可见面。
	 *
	 * @param part    子树根
	 * @param texture 该子树使用的贴图
	 * @param project 世界坐标 → 屏幕坐标的投影函数
	 * @param into    结果收集容器
	 */
	public static void collect(ModelPart part, Texture texture, Projection project, List<Face> into) {
		collect(part, new PoseStack(), texture, project, into);
	}

	private static void collect(ModelPart part, PoseStack pose, Texture texture,
			Projection project, List<Face> into) {

		if (!part.visible) {
			return;
		}

		pose.pushPose();
		applyTransform(part, pose);

		if (!part.skipDraw) {
			collectCubes(part, pose, texture, project, into);
		}

		// 只递归直接子部件：ModelPart.getAllParts() 返回的表里第一个元素是部件自身，
		// 用它递归会自己调自己（实测直接 StackOverflowError）。
		for (ModelPart child : childrenOf(part)) {
			collect(child, pose, texture, project, into);
		}

		pose.popPose();
	}

	private static void collectCubes(ModelPart part, PoseStack pose, Texture texture,
			Projection project, List<Face> into) {

		org.joml.Matrix4f matrix = new org.joml.Matrix4f(pose.last().pose());

		for (ModelPart.Cube cube : cubesOf(part)) {
			for (ModelPart.Polygon polygon : cube.polygons) {
				ModelPart.Vertex[] vertices = polygon.vertices();
				float[][] xy = new float[vertices.length][];
				float[][] uv = new float[vertices.length][];

				for (int i = 0; i < vertices.length; i++) {
					ModelPart.Vertex vertex = vertices[i];
					org.joml.Vector4f p = new org.joml.Vector4f(vertex.x(), vertex.y(), vertex.z(), 1.0F).mul(matrix);
					xy[i] = project.project(p.x, p.y, p.z);
					uv[i] = new float[] { vertex.u(), vertex.v() };
				}

				into.add(new Face(xy, uv, new org.joml.Vector3f(polygon.normal()).normalize(), texture));
			}
		}
	}

	/**
	 * 应用部件的位移 / 旋转 / 缩放，顺序与原版完全一致。
	 *
	 * <p>顺序错了姿势就全错 —— 原版是 Z→Y→X，不是 X→Y→Z。
	 */
	public static void applyTransform(ModelPart part, PoseStack pose) {
		pose.translate(part.x / 16.0F, part.y / 16.0F, part.z / 16.0F);

		if (part.zRot != 0.0F) {
			pose.mulPose(Axis.ZP.rotation(part.zRot));
		}
		if (part.yRot != 0.0F) {
			pose.mulPose(Axis.YP.rotation(part.yRot));
		}
		if (part.xRot != 0.0F) {
			pose.mulPose(Axis.XP.rotation(part.xRot));
		}

		pose.scale(part.xScale, part.yScale, part.zScale);
	}

	/** 世界坐标 → 屏幕坐标的投影。 */
	public interface Projection {
		float[] project(float x, float y, float z);
	}

	// ------------------------------------------------------------ 反射取私有字段

	private static java.lang.reflect.Field field(String name) {
		try {
			java.lang.reflect.Field field = ModelPart.class.getDeclaredField(name);
			field.setAccessible(true);
			return field;
		} catch (ReflectiveOperationException e) {
			throw new IllegalStateException("拿不到 ModelPart." + name + "（原版字段改名了？）", e);
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
	private static Collection<ModelPart> childrenOf(ModelPart part) {
		try {
			return ((Map<String, ModelPart>) CHILDREN.get(part)).values();
		} catch (IllegalAccessException e) {
			throw new IllegalStateException(e);
		}
	}

	/** 取一个部件的立方体列表（公开给探针 / 工具用）。 */
	public static List<ModelPart.Cube> cubes(ModelPart part) {
		return cubesOf(part);
	}

	/** 取一个部件的直接子部件（公开给探针 / 工具用）。 */
	public static Collection<ModelPart> children(ModelPart part) {
		return childrenOf(part);
	}
}
