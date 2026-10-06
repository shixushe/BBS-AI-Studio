package mchorse.bbs_mod.mixin.client;

import mchorse.bbs_mod.forms.structure.StructureWand;
import net.minecraft.client.MinecraftClient;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * The structure wand takes the mouse buttons here, before the game decides what a click means:
 * this is the one place a click passes through whether it lands on a block, an entity or the air,
 * and the wand must act the same on all three.
 */
@Mixin(MinecraftClient.class)
public class MinecraftClientMixin
{
    @Inject(method = "doAttack", at = @At("HEAD"), cancellable = true)
    public void wandAttack(CallbackInfoReturnable<Boolean> cir)
    {
        if (StructureWand.onAttack())
        {
            cir.setReturnValue(false);
        }
    }

    @Inject(method = "doItemUse", at = @At("HEAD"), cancellable = true)
    public void wandUse(CallbackInfo ci)
    {
        if (StructureWand.onUse())
        {
            ci.cancel();
        }
    }

    /**
     * End-of-frame hook: everything has been drawn into the framebuffer and
     * it has not been swapped away yet - the one moment a screenshot of what
     * the user actually sees is possible (between-frames reads hit an
     * already-cleared buffer and come out black).
     */
    @Inject(method = "render", at = @At("RETURN"))
    public void bbs$onFrameEnd(CallbackInfo ci)
    {
        mchorse.bbs_mod.ai.AiVision.onFrameEnd();
    }
}
