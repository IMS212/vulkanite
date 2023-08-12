package me.cortex.vulkanite.lib.base;

import me.cortex.vulkanite.compat.IRenderTargetVkGetter;
import me.cortex.vulkanite.lib.cmd.CommandManager;
import me.cortex.vulkanite.lib.cmd.VCmdBuff;
import me.cortex.vulkanite.lib.cmd.VCommandPool;
import me.cortex.vulkanite.lib.memory.VImage;
import me.cortex.vulkanite.lib.other.sync.SyncManager;
import me.cortex.vulkanite.lib.memory.MemoryManager;
import me.cortex.vulkanite.lib.other.sync.VFence;
import me.cortex.vulkanite.lib.other.sync.VSemaphore;
import net.minecraft.client.MinecraftClient;
import org.lwjgl.glfw.GLFW;
import org.lwjgl.glfw.GLFWNativeWin32;
import org.lwjgl.glfw.GLFWVulkan;
import org.lwjgl.opengl.GL43C;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.windows.WinBase;
import org.lwjgl.vulkan.*;

import java.nio.LongBuffer;

import static org.lwjgl.system.MemoryStack.stackPush;
import static org.lwjgl.vulkan.EXTHdrMetadata.VK_STRUCTURE_TYPE_HDR_METADATA_EXT;
import static org.lwjgl.vulkan.EXTHdrMetadata.vkSetHdrMetadataEXT;
import static org.lwjgl.vulkan.KHRSurface.vkDestroySurfaceKHR;
import static org.lwjgl.vulkan.KHRSwapchain.VK_IMAGE_LAYOUT_PRESENT_SRC_KHR;
import static org.lwjgl.vulkan.VK10.VK_PIPELINE_STAGE_COLOR_ATTACHMENT_OUTPUT_BIT;
import static org.lwjgl.vulkan.VK13.*;

public class VContext {
    public final VkDevice device;


    public final MemoryManager memory;
    public final SyncManager sync;
    public final CommandManager cmd;
    public final DeviceProperties properties;
    public SwapChain swapChain;
    private final VCommandPool singleUsePool;
    private VCmdBuff buffer;
    public  long vkSurface;
    private VkInstance instance;
    public long window;

    public VContext(VkInstance instance, VkDevice device, int queueCount, boolean hasDeviceAddresses) {
        this.device = device;
        this.instance = instance;
        memory = new MemoryManager(device, hasDeviceAddresses);
        sync = new SyncManager(device);
        cmd = new CommandManager(device, queueCount);
        properties = new DeviceProperties(device);
        singleUsePool = cmd.createSingleUsePool();



    }

    public void waitValid() {
        try (MemoryStack stack = stackPush()) {
            GLFW.glfwDefaultWindowHints();
            GLFW.glfwWindowHint(GLFW.GLFW_CLIENT_API, 0);
            GLFW.glfwWindowHint(GLFW.GLFW_VISIBLE, 1);
            this.window = GLFW.glfwCreateWindow(MinecraftClient.getInstance().getWindow().getWidth(), MinecraftClient.getInstance().getWindow().getHeight(), "VK Window", GLFW.glfwGetPrimaryMonitor(), 0);

            LongBuffer pSurface = stack.mallocLong(1);
            GLFWVulkan.glfwCreateWindowSurface(instance, window,
                    null, pSurface);
            vkSurface = pSurface.get(0);

            this.swapChain = new SwapChain(device, vkSurface, window, 3, true);

        }
        this.buffer = singleUsePool.createCommandBuffer();

    }

    public void brokeFormat() {
        swapChain.cleanup();
        this.swapChain = new SwapChain(device, vkSurface, window, 3, true);
    }

    static public void cmdCopyImageToImage(VCmdBuff cmdBuf, VImage srcImage, ImageView dstImage, VkExtent3D extent3D) {
        try ( MemoryStack stack = stackPush() ) {
            //
            var readMemoryBarrierTemplate = VkImageMemoryBarrier2.calloc(stack)
                    .sType(VK_STRUCTURE_TYPE_IMAGE_MEMORY_BARRIER_2)
                    .srcStageMask(VK_PIPELINE_STAGE_2_ALL_TRANSFER_BIT | VK_PIPELINE_STAGE_2_HOST_BIT | VK_PIPELINE_STAGE_2_COPY_BIT)
                    .srcAccessMask(VK_ACCESS_2_MEMORY_READ_BIT | VK_ACCESS_2_MEMORY_WRITE_BIT | VK_ACCESS_2_TRANSFER_READ_BIT | VK_ACCESS_2_TRANSFER_WRITE_BIT)
                    .dstStageMask(VK_PIPELINE_STAGE_2_ALL_COMMANDS_BIT)
                    .dstAccessMask(VK_ACCESS_2_MEMORY_READ_BIT | VK_ACCESS_2_MEMORY_WRITE_BIT)
                    .srcQueueFamilyIndex(VK_QUEUE_FAMILY_IGNORED)
                    .dstQueueFamilyIndex(VK_QUEUE_FAMILY_IGNORED)
                    .oldLayout(VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL)
                    .newLayout(VK_IMAGE_LAYOUT_UNDEFINED);

            //
            var writeMemoryBarrierTemplate = VkImageMemoryBarrier2.calloc(stack)
                    .sType(VK_STRUCTURE_TYPE_IMAGE_MEMORY_BARRIER_2)
                    .srcStageMask(VK_PIPELINE_STAGE_2_ALL_TRANSFER_BIT | VK_PIPELINE_STAGE_2_COPY_BIT)
                    .srcAccessMask(VK_ACCESS_2_MEMORY_READ_BIT | VK_ACCESS_2_MEMORY_WRITE_BIT | VK_ACCESS_2_TRANSFER_READ_BIT | VK_ACCESS_2_TRANSFER_WRITE_BIT)
                    .dstStageMask(VK_PIPELINE_STAGE_2_ALL_COMMANDS_BIT)
                    .dstAccessMask(VK_ACCESS_2_MEMORY_READ_BIT | VK_ACCESS_2_MEMORY_WRITE_BIT)
                    .srcQueueFamilyIndex(VK_QUEUE_FAMILY_IGNORED)
                    .dstQueueFamilyIndex(VK_QUEUE_FAMILY_IGNORED)
                    .oldLayout(VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL)
                    .newLayout(VK_IMAGE_LAYOUT_PRESENT_SRC_KHR);

            //
            var preReadMemoryBarrierTemplate = VkImageMemoryBarrier2.calloc(stack)
                    .sType(VK_STRUCTURE_TYPE_IMAGE_MEMORY_BARRIER_2)
                    .srcStageMask(VK_PIPELINE_STAGE_2_ALL_COMMANDS_BIT)
                    .srcAccessMask(VK_ACCESS_2_MEMORY_READ_BIT | VK_ACCESS_2_MEMORY_WRITE_BIT)
                    .dstStageMask(VK_PIPELINE_STAGE_2_ALL_TRANSFER_BIT | VK_PIPELINE_STAGE_2_HOST_BIT | VK_PIPELINE_STAGE_2_COPY_BIT)
                    .dstAccessMask(VK_ACCESS_2_MEMORY_READ_BIT | VK_ACCESS_2_MEMORY_WRITE_BIT | VK_ACCESS_2_TRANSFER_READ_BIT | VK_ACCESS_2_TRANSFER_WRITE_BIT)
                    .srcQueueFamilyIndex(VK_QUEUE_FAMILY_IGNORED)
                    .dstQueueFamilyIndex(VK_QUEUE_FAMILY_IGNORED)
                    .oldLayout(VK_IMAGE_LAYOUT_UNDEFINED)
                    .newLayout(VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL);

            //
            var preWriteMemoryBarrierTemplate = VkImageMemoryBarrier2.calloc(stack)
                    .sType(VK_STRUCTURE_TYPE_IMAGE_MEMORY_BARRIER_2)
                    .srcStageMask(VK_PIPELINE_STAGE_2_ALL_COMMANDS_BIT)
                    .srcAccessMask(VK_ACCESS_2_MEMORY_READ_BIT | VK_ACCESS_2_MEMORY_WRITE_BIT)
                    .dstStageMask(VK_PIPELINE_STAGE_2_ALL_TRANSFER_BIT | VK_PIPELINE_STAGE_2_COPY_BIT)
                    .dstAccessMask(VK_ACCESS_2_MEMORY_READ_BIT | VK_ACCESS_2_MEMORY_WRITE_BIT | VK_ACCESS_2_TRANSFER_READ_BIT | VK_ACCESS_2_TRANSFER_WRITE_BIT)
                    .srcQueueFamilyIndex(VK_QUEUE_FAMILY_IGNORED)
                    .dstQueueFamilyIndex(VK_QUEUE_FAMILY_IGNORED)
                    .oldLayout(VK_IMAGE_LAYOUT_UNDEFINED)
                    .newLayout(VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL);

            //
            var imageMemoryBarrier = VkImageMemoryBarrier2.calloc(2, stack);
            imageMemoryBarrier.get(0).set(readMemoryBarrierTemplate).image(srcImage.image()).subresourceRange(srcImage.subresourceRange);
            imageMemoryBarrier.get(1).set(writeMemoryBarrierTemplate).image(dstImage.origImage).subresourceRange(dstImage.getSubresourceRange());

            //
            var preImageMemoryBarrier = VkImageMemoryBarrier2.calloc(2, stack);
            preImageMemoryBarrier.get(0).set(preReadMemoryBarrierTemplate).image(srcImage.image()).subresourceRange(srcImage.subresourceRange);
            preImageMemoryBarrier.get(1).set(preWriteMemoryBarrierTemplate).image(dstImage.origImage).subresourceRange(dstImage.getSubresourceRange());

            //
            var imageCopyRegion = VkImageCopy2.calloc(1, stack).sType(VK_STRUCTURE_TYPE_IMAGE_COPY_2).dstSubresource(dstImage.subResourceLayers).srcSubresource(srcImage.subresourceLayers)
                    .srcOffset(vkOffset3D -> vkOffset3D.set(0, 0, 0)).dstOffset(vkOffset3D -> vkOffset3D.set(0, 0, 0)).extent(extent3D);
            vkCmdPipelineBarrier2(cmdBuf.buffer, VkDependencyInfoKHR.calloc(stack).sType(VK_STRUCTURE_TYPE_DEPENDENCY_INFO).pImageMemoryBarriers(preImageMemoryBarrier));
            vkCmdCopyImage2(cmdBuf.buffer, VkCopyImageInfo2.calloc(stack).sType(VK_STRUCTURE_TYPE_COPY_IMAGE_INFO_2).dstImage(dstImage.origImage).dstImageLayout(VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL).srcImageLayout(VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL).srcImage(srcImage.image()).pRegions(imageCopyRegion));
            vkCmdPipelineBarrier2(cmdBuf.buffer, VkDependencyInfoKHR.calloc(stack).sType(VK_STRUCTURE_TYPE_DEPENDENCY_INFO).pImageMemoryBarriers(imageMemoryBarrier));
        }
    }

    public void submit() {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            GL43C.glFinish();
            buffer.begin(VK_COMMAND_BUFFER_USAGE_ONE_TIME_SUBMIT_BIT);
            VK11.vkCmdSetViewport(buffer.buffer, 0, VkViewport.calloc(1).width(-MinecraftClient.getInstance().getWindow().getWidth()).height(-MinecraftClient.getInstance().getWindow().getHeight()).x(0).y(MinecraftClient.getInstance().getWindow().getHeight()));

            if (swapChain.getImageViews().length < swapChain.getCurrentFrame()) return;

            cmdCopyImageToImage(buffer, ((IRenderTargetVkGetter) MinecraftClient.getInstance().getFramebuffer()).getMain(), swapChain.getImageViews()[swapChain.getCurrentFrame()], VkExtent3D.calloc().set(MinecraftClient.getInstance().getWindow().getWidth(), MinecraftClient.getInstance().getWindow().getHeight(), 1));
            buffer.end();

            int idx = swapChain.getCurrentFrame();
            VSemaphore link = sync.createBinarySemaphore();

            VFence buildFence = sync.createFence();

            cmd.submit(0, new VCmdBuff[]{buffer}, new VSemaphore[0], new int[0],
                    new VSemaphore[]{link},
                    buildFence);

        vkWaitForFences(device, buildFence.address(), true, -1);
        VK11.vkDeviceWaitIdle(device);
        }
    }

    public void drawFrame() {
        swapChain.acquireNextImage();

        submit();

        swapChain.presentImage(cmd.queues[0]);
    }
}
