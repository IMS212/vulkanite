package me.cortex.vulkanite.mixin;

import me.cortex.vulkanite.client.Vulkanite;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.RunArgs;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(MinecraftClient.class)
public class MixinMinecraftClient {
    @Inject(method = "render", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/render/GameRenderer;render(FJZ)V", shift = At.Shift.BEFORE))
    private void onRenderTick(boolean tick, CallbackInfo ci) {
        Vulkanite.INSTANCE.renderTick();
    }

    @Inject(method = "<init>", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/MinecraftClient;onWindowFocusChanged(Z)V"))
    private void finishStart(RunArgs args, CallbackInfo ci) {
        Vulkanite.INSTANCE.getCtx().waitValid();
    }

    @Inject(method = "render", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/render/GameRenderer;render(FJZ)V", shift = At.Shift.AFTER))
    private void afterRenderTick(boolean tick, CallbackInfo ci) {
        Vulkanite.INSTANCE.renderTickAfter();
    }
}
