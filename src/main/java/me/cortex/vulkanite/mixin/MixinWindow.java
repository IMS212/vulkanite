package me.cortex.vulkanite.mixin;

import me.cortex.vulkanite.client.Vulkanite;
import net.minecraft.client.WindowEventHandler;
import net.minecraft.client.WindowSettings;
import net.minecraft.client.util.Monitor;
import net.minecraft.client.util.MonitorTracker;
import net.minecraft.client.util.VideoMode;
import net.minecraft.client.util.Window;
import org.lwjgl.glfw.GLFW;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.Optional;

@Mixin(Window.class)
public class MixinWindow {
    @Shadow @Final private long handle;

    @Shadow private Optional<VideoMode> videoMode;

    @Inject(method = "<init>", at = @At(value = "INVOKE", target = "Lorg/lwjgl/glfw/GLFW;glfwCreateWindow(IILjava/lang/CharSequence;JJ)J"))
    private void makeHidden(WindowEventHandler eventHandler, MonitorTracker monitorTracker, WindowSettings settings, String videoMode, String title, CallbackInfo ci) {
        GLFW.glfwWindowHint(GLFW.GLFW_VISIBLE, GLFW.GLFW_FALSE);
    }
    @Inject(method = "<init>", at = @At(value = "TAIL"))
    private void makeHidde2n(WindowEventHandler eventHandler, MonitorTracker monitorTracker, WindowSettings settings, String videoMode, String title, CallbackInfo ci) {

    }

    @Redirect(method = "<init>", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/util/Monitor;getHandle()J"))
    private long nope(Monitor instance) {
        return 0L;
    }

    @Redirect(method = "<init>", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/util/Monitor;findClosestVideoMode(Ljava/util/Optional;)Lnet/minecraft/client/util/VideoMode;"))
    private VideoMode findFS(Monitor instance, Optional<VideoMode> videoMode) {
        return new VideoMode(2560, 1440, 16, 16, 16, 60);
    }

    @Redirect(method = "updateWindowRegion", at = @At(value = "FIELD", target = "Lnet/minecraft/client/util/Window;fullscreen:Z", ordinal =  0))
    private boolean yes(Window instance) {
        return true;
    }


    @Redirect(method = "updateWindowRegion", at = @At(value = "INVOKE", target = "Lorg/lwjgl/glfw/GLFW;glfwSetWindowMonitor(JJIIIII)V"))
    private void yes2(long window, long monitor, int xpos, int ypos, int width, int height, int refreshRate) {
        GLFW.glfwSetWindowMonitor(window, 0L, xpos, ypos, width, height, refreshRate);
        if (Vulkanite.INSTANCE.getCtx().window != 0L) {
            GLFW.glfwSetWindowMonitor(Vulkanite.INSTANCE.getCtx().window, monitor, xpos, ypos, width, height, refreshRate);
            Vulkanite.INSTANCE.getCtx().brokeFormat();
        }
    }

    @Overwrite
    public long getHandle() {
        if (Vulkanite.INSTANCE.getCtx().window != 0L) return Vulkanite.INSTANCE.getCtx().window;
        return this.handle;
    }

}
