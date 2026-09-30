package com.yunxigames.mobs.mixin;

import com.yunxigames.mobs.PhantomSound;
import net.minecraft.world.entity.monster.Phantom;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 幻翼<b>俯冲开始</b>时播放自定义音频（yg-mobs 包）。
 *
 * <p><b>为什么要挂「俯冲目标」这个内部类</b>：26.2 的原版幻翼把俯冲拆成两个目标类配合 ——
 * {@code Phantom$PhantomAttackStrategyGoal} 决定何时俯冲（把 {@code attackPhase} 置为
 * {@code SWOOP}），真正的俯冲动作由 {@code Phantom$PhantomSweepAttackGoal} 执行，而后者的
 * {@code canUse()} 判据就是 {@code getTarget() != null && attackPhase == SWOOP}。
 * 也就是说：<b>这个目标类一开始运行 == 俯冲开始</b>，是我们要的那个时机；
 * 而 {@code attackPhase} 本身是私有字段、其类型 {@code Phantom$AttackPhase} 又是包级私有的
 * （外部源码无法引用），直接读它编不过，所以改成挂在「俯冲执行者」身上。
 *
 * <p>音效只在每次俯冲的<b>第一刻</b>播一次：{@code canUse()} 为真后目标类立刻启动并逐 tick 调用
 * {@code tick()}，用一个 {@code @Unique} 标记记住「这次俯冲已经响过了」，目标停止时（{@code stop()}）
 * 清掉标记，下一次俯冲重新响。这样既不会每 tick 刷屏，也不会漏掉第二次俯冲。
 *
 * <p>触发时机是「刚开始下冲」，比原件（命中瞬间才响）提前约十几 tick —— 玩家有反应时间；
 * 同时音效与「俯冲命中爆炸」解耦，改成爆炸开关不影响声音。
 */
@Mixin(targets = "net.minecraft.world.entity.monster.Phantom$PhantomSweepAttackGoal")
public abstract class PhantomSweepSoundMixin {

	/**
	 * 内部类的合成外持字段 {@code this$0}（指向幻翼本体）。
	 *
	 * <p>可见性必须与目标类一致（包级私有，见 javap 输出），所以这里不加修饰符 ——
	 * Mixin 靠它把引用重定向到目标类的同名字段。
	 */
	@Shadow
	@Final
	Phantom this$0;

	/** 本次俯冲是否已经响过音效（每次俯冲只响一次）。 */
	@Unique
	private boolean yg$swoopSoundPlayed;

	@Inject(method = "tick", at = @At("HEAD"))
	private void yg$playSoundOnDiveStart(CallbackInfo ci) {
		if (yg$swoopSoundPlayed) {
			return;
		}
		yg$swoopSoundPlayed = true;

		if (!PhantomSound.enabled()) {
			return;
		}
		PhantomSound.playDive(this$0.level(), this$0.getX(), this$0.getY(), this$0.getZ());
	}

	/** 俯冲结束（目标停止）时复位标记，让下一次俯冲能再次响。 */
	@Inject(method = "stop", at = @At("HEAD"))
	private void yg$resetSwoopSound(CallbackInfo ci) {
		yg$swoopSoundPlayed = false;
	}
}
