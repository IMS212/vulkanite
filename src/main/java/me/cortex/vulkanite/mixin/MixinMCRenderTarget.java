package me.cortex.vulkanite.mixin;

import com.mojang.blaze3d.platform.GlStateManager;
import me.cortex.vulkanite.client.Vulkanite;
import me.cortex.vulkanite.compat.IRenderTargetVkGetter;
import me.cortex.vulkanite.lib.memory.VGImage;
import net.coderbot.iris.gl.IrisRenderSystem;
import net.minecraft.client.gl.Framebuffer;
import org.lwjgl.opengl.GL32C;
import org.lwjgl.opengl.GL46C;
import org.lwjgl.vulkan.VK10;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.nio.IntBuffer;

import static org.lwjgl.opengl.GL11C.GL_RGBA;
import static org.lwjgl.opengl.GL30C.GL_RGBA32F;
import static org.lwjgl.vulkan.VK10.*;

@Mixin(Framebuffer.class)
public class MixinMCRenderTarget implements IRenderTargetVkGetter {
    @Shadow public int textureWidth;
    @Shadow public int textureHeight;
    @Shadow protected int colorAttachment;
    @Unique
    private VGImage vgColorTexture;

    @Redirect(method = "initFbo", at = @At(value = "INVOKE", target = "Lcom/mojang/blaze3d/platform/GlStateManager;_glFramebufferTexture2D(IIIII)V", ordinal = 0))
    private void lolNope(int target, int attachment, int textureTarget, int texture, int level) {
        var ctx = Vulkanite.INSTANCE.getCtx();
        var glfmt = GL46C.GL_RGBA16F;
        var vkfmt = VK10.VK_FORMAT_R16G16B16A16_SFLOAT;
        vgColorTexture = ctx.memory.createSharedImage(textureWidth, textureHeight, 1, vkfmt, glfmt, VK_IMAGE_USAGE_TRANSFER_SRC_BIT | VK_IMAGE_USAGE_STORAGE_BIT , VK_MEMORY_PROPERTY_DEVICE_LOCAL_BIT);
        GlStateManager._bindTexture(vgColorTexture.glId);

        GlStateManager._glFramebufferTexture2D(target, attachment, textureTarget, vgColorTexture.glId, level);
        this.colorAttachment = vgColorTexture.glId;
    }

    @Override
    public VGImage getMain() {
        return vgColorTexture;
    }

    @Override
    public VGImage getAlt() {
        return vgColorTexture;
    }
}
