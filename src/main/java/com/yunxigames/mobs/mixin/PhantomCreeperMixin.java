package com.yunxigames.mobs.mixin;

import com.yunxigames.MobsConfig;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.monster.Phantom;
import net.minecraft.world.level.Level;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * 幻翼 × 苦力怕混合生物的<b>爆炸能力</b>（yg-mobs 包）。
 *
 * <p><b>为什么挂在 {@code Mob} 上而不是 {@code Phantom} 上</b>：俯冲命中走的是
 * {@code Mob#doHurtTarget}，而 26.2 的 {@code Phantom} <b>并没有重写</b>这个方法
 * （javap 可验证：Phantom 只有 getAmbientSound / getHurtSound / getDeathSound 等）。
 * Mixin 的注入目标必须是目标类自身字节码里声明的方法，继承来的方法找不到，
 * 直接挂 Phantom 会报 “could not find any targets matching 'doHurtTarget'”。
 * 所以这里挂到父类 {@code Mob}，运行时再用 {@code instanceof Phantom} 精确过滤 ——
 * 只有幻翼会触发，其余生物零开销（多一次类型判断而已）。
 *
 * <p>为避免混合生物每次俯冲都把自己炸死（那样就失去「幻翼原能力」了），引爆前
 * 临时设为无敌、炸完再还原：只有目标与周围方块 / 实体受损，幻翼自己存活、继续飞行。
 */
@Mixin(Mob.class)
public abstract class PhantomCreeperMixin {

	/** 俯冲命中即引爆 —— 苦力怕的爆炸能力（仅幻翼生效）。 */
	@Inject(method = "doHurtTarget", at = @At("HEAD"))
	private void yg$explodeOnHit(ServerLevel level, Entity target, CallbackInfoReturnable<Boolean> cir) {
		if (!MobsConfig.get().phantomCreeperEnabled) {
			return;
		}

		float power = MobsConfig.get().phantomCreeperExplosionPower;
		if (power <= 0.0F) {
			return;
		}

		// 只对幻翼生效（本注入挂在 Mob 上，其余生物直接返回）
		Mob self = (Mob) (Object) this;
		if (!(self instanceof Phantom phantom)) {
			return;
		}

		// 音效不在这里播：俯冲音效已挪到「俯冲刚开始」那一刻
		// （见 PhantomSweepSoundMixin），命中只负责爆炸。
		boolean fire = MobsConfig.get().phantomCreeperExplosionFire;

		// 临时无敌：避免混合生物每次俯冲都把自己炸死，保住「幻翼原能力」。
		boolean wasInvuln = phantom.isInvulnerable();
		phantom.setInvulnerable(true);
		try {
			level.explode(phantom, phantom.getX(), phantom.getY(), phantom.getZ(),
					power, fire, Level.ExplosionInteraction.MOB);
		} finally {
			phantom.setInvulnerable(wasInvuln);
		}
	}
}
