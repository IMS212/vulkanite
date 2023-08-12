package me.cortex.vulkanite.lib.base;

import me.cortex.vulkanite.lib.other.sync.VSemaphore;
import net.coderbot.iris.Iris;
import net.minecraft.client.MinecraftClient;
import org.lwjgl.system.*;
import org.lwjgl.vulkan.*;

import java.nio.*;
import java.util.Arrays;

import static me.cortex.vulkanite.lib.other.VUtil._CHECK_;
import static org.lwjgl.system.MemoryStack.stackPush;
import static org.lwjgl.vulkan.EXTHdrMetadata.VK_STRUCTURE_TYPE_HDR_METADATA_EXT;
import static org.lwjgl.vulkan.EXTHdrMetadata.vkSetHdrMetadataEXT;
import static org.lwjgl.vulkan.VK11.*;

public class SwapChain {

    private final VkDevice device;
    private final ImageView[] imageViews;
    private final SurfaceFormat surfaceFormat;
    private final VkExtent2D swapChainExtent;
    private final SyncSemaphores[] syncSemaphoresList;
    private final long vkSwapChain;

    private int currentFrame;

    public SwapChain(VkDevice device, long surface, long window, int requestedImages, boolean vsync) {
        Iris.logger.debug("Creating Vulkan SwapChain");
        this.device = device;
        try (MemoryStack stack = MemoryStack.stackPush()) {

            VkPhysicalDevice physicalDevice = device.getPhysicalDevice();

            // Get surface capabilities
            VkSurfaceCapabilitiesKHR surfCapabilities = VkSurfaceCapabilitiesKHR.calloc(stack);
            vkCheck(KHRSurface.vkGetPhysicalDeviceSurfaceCapabilitiesKHR(device.getPhysicalDevice(),
                    surface, surfCapabilities), "Failed to get surface capabilities");

            int numImages = calcNumImages(surfCapabilities, requestedImages);

            surfaceFormat = calcSurfaceFormat(physicalDevice, surface);

            swapChainExtent = calcSwapChainExtent(window, surfCapabilities);

            VkSwapchainCreateInfoKHR vkSwapchainCreateInfo = VkSwapchainCreateInfoKHR.calloc(stack)
                    .sType(KHRSwapchain.VK_STRUCTURE_TYPE_SWAPCHAIN_CREATE_INFO_KHR)
                    .surface(surface)
                    .minImageCount(numImages)
                    .imageFormat(surfaceFormat.imageFormat())
                    .imageColorSpace(surfaceFormat.colorSpace())
                    .imageExtent(swapChainExtent)
                    .imageArrayLayers(1)
                    .imageUsage(VK_IMAGE_USAGE_COLOR_ATTACHMENT_BIT | VK_IMAGE_USAGE_TRANSFER_DST_BIT)
                    .imageSharingMode(VK_SHARING_MODE_EXCLUSIVE)
                    .preTransform(surfCapabilities.currentTransform())
                    .compositeAlpha(KHRSurface.VK_COMPOSITE_ALPHA_OPAQUE_BIT_KHR)
                    .clipped(true);
            if (vsync) {
                vkSwapchainCreateInfo.presentMode(KHRSurface.VK_PRESENT_MODE_FIFO_KHR);
            } else {
                vkSwapchainCreateInfo.presentMode(KHRSurface.VK_PRESENT_MODE_IMMEDIATE_KHR);
            }
            LongBuffer lp = stack.mallocLong(1);
            vkCheck(KHRSwapchain.vkCreateSwapchainKHR(device, vkSwapchainCreateInfo, null, lp),
                    "Failed to create swap chain");
            vkSwapChain = lp.get(0);

            imageViews = createImageViews(stack, device, vkSwapChain, surfaceFormat.imageFormat);
            numImages = imageViews.length;
            syncSemaphoresList = new SyncSemaphores[numImages];
            for (int i = 0; i < numImages; i++) {
                syncSemaphoresList[i] = new SyncSemaphores(device);
            }
            currentFrame = 0;
        }
    }

    public boolean acquireNextImage() {
        boolean resize = false;
        try (MemoryStack stack = MemoryStack.stackPush()) {
            IntBuffer ip = stack.mallocInt(1);
            int err = KHRSwapchain.vkAcquireNextImageKHR(device, vkSwapChain, ~0L,
                    syncSemaphoresList[currentFrame].imgAcquisitionSemaphore().semaphore(), MemoryUtil.NULL, ip);
            if (err == KHRSwapchain.VK_ERROR_OUT_OF_DATE_KHR) {
                resize = true;
            } else if (err == KHRSwapchain.VK_SUBOPTIMAL_KHR) {
                // Not optimal but swapchain can still be used
            } else if (err != VK_SUCCESS) {
                throw new RuntimeException("Failed to acquire image: " + err);
            }
            currentFrame = ip.get(0);
        }

        return resize;
    }

    private int calcNumImages(VkSurfaceCapabilitiesKHR surfCapabilities, int requestedImages) {
        int maxImages = surfCapabilities.maxImageCount();
        int minImages = surfCapabilities.minImageCount();
        int result = minImages;
        if (maxImages != 0) {
            result = Math.min(requestedImages, maxImages);
        }
        result = Math.max(result, minImages);
        Iris.logger.warn("Requested [{}] images, got [{}] images. Surface capabilities, maxImages: [{}], minImages [{}]",
                requestedImages, result, maxImages, minImages);

        return result;
    }

    private SurfaceFormat calcSurfaceFormat(VkPhysicalDevice physicalDevice, long surface) {
        int imageFormat;
        int colorSpace;
        try (MemoryStack stack = MemoryStack.stackPush()) {

            IntBuffer ip = stack.mallocInt(1);
            vkCheck(KHRSurface.vkGetPhysicalDeviceSurfaceFormatsKHR(physicalDevice,
                    surface, ip, null), "Failed to get the number surface formats");
            int numFormats = ip.get(0);
            if (numFormats <= 0) {
                throw new RuntimeException("No surface formats retrieved");
            }

            VkSurfaceFormatKHR.Buffer surfaceFormats = VkSurfaceFormatKHR.calloc(numFormats, stack);
            vkCheck(KHRSurface.vkGetPhysicalDeviceSurfaceFormatsKHR(physicalDevice,
                    surface, ip, surfaceFormats), "Failed to get surface formats");

            imageFormat = VK_FORMAT_R16G16B16A16_SFLOAT;
            colorSpace = surfaceFormats.get(0).colorSpace();
            for (int i = 0; i < numFormats; i++) {
                VkSurfaceFormatKHR surfaceFormatKHR = surfaceFormats.get(i);
                if (surfaceFormatKHR.format() == VK_FORMAT_R16G16B16A16_SFLOAT) {
                    imageFormat = surfaceFormatKHR.format();
                    colorSpace = surfaceFormatKHR.colorSpace();
                    break;
                }
            }
        }
        return new SurfaceFormat(imageFormat, colorSpace);
    }

    public VkExtent2D calcSwapChainExtent(long window, VkSurfaceCapabilitiesKHR surfCapabilities) {
        VkExtent2D result = VkExtent2D.calloc();
        if (surfCapabilities.currentExtent().width() == 0xFFFFFFFF) {
            // Surface size undefined. Set to the window size if within bounds
            // TODO RESIZE
            int width = Math.min(MinecraftClient.getInstance().getWindow().getWidth(), surfCapabilities.maxImageExtent().width());
            width = Math.max(width, surfCapabilities.minImageExtent().width());

            int height = Math.min(MinecraftClient.getInstance().getWindow().getHeight(), surfCapabilities.maxImageExtent().height());
            height = Math.max(height, surfCapabilities.minImageExtent().height());

            result.width(width);
            result.height(height);
        } else {
            // Surface already defined, just use that for the swap chain
            result.set(surfCapabilities.currentExtent());
        }
        return result;
    }

    public void cleanup() {
        swapChainExtent.free();
        Arrays.asList(imageViews).forEach(ImageView::cleanup);
        Arrays.asList(syncSemaphoresList).forEach(SyncSemaphores::cleanup);
        KHRSwapchain.vkDestroySwapchainKHR(device, vkSwapChain, null);
    }

    public static void vkCheck(int err, String errMsg) {
        if (err != VK_SUCCESS) {
            throw new RuntimeException(errMsg + ": " + err);
        }
    }

    private ImageView[] createImageViews(MemoryStack stack, VkDevice device, long swapChain, int format) {
        ImageView[] result;

        IntBuffer ip = stack.mallocInt(1);
        vkCheck(KHRSwapchain.vkGetSwapchainImagesKHR(device, swapChain, ip, null),
                "Failed to get number of surface images");
        int numImages = ip.get(0);

        LongBuffer swapChainImages = stack.mallocLong(numImages);
        vkCheck(KHRSwapchain.vkGetSwapchainImagesKHR(device, swapChain, ip, swapChainImages),
                "Failed to get surface images");

        result = new ImageView[numImages];
        ImageView.ImageViewData imageViewData = new ImageView.ImageViewData().format(format).aspectMask(VK_IMAGE_ASPECT_COLOR_BIT);
        for (int i = 0; i < numImages; i++) {
            result[i] = new ImageView(device, swapChainImages.get(i), imageViewData);
        }

        return result;
    }

    public int getCurrentFrame() {
        return currentFrame;
    }

    public VkDevice getDevice() {
        return device;
    }

    public ImageView[] getImageViews() {
        return imageViews;
    }

    public int getNumImages() {
        return imageViews.length;
    }

    public SurfaceFormat getSurfaceFormat() {
        return surfaceFormat;
    }

    public VkExtent2D getSwapChainExtent() {
        return swapChainExtent;
    }

    public SyncSemaphores[] getSyncSemaphoresList() {
        return syncSemaphoresList;
    }

    public long getVkSwapChain() {
        return vkSwapChain;
    }

    public boolean presentImage(VkQueue queue) {
        VkHdrMetadataEXT.Buffer hdr = VkHdrMetadataEXT.calloc(1).sType(VK_STRUCTURE_TYPE_HDR_METADATA_EXT).pNext(0L).displayPrimaryRed(VkXYColorEXT.calloc().set(0.647466f, 0.335936f)).displayPrimaryGreen(VkXYColorEXT.calloc().set(0.647466f, 0.335936f)).displayPrimaryBlue(VkXYColorEXT.calloc().set(0.647466f, 0.335936f)).whitePoint(VkXYColorEXT.calloc().set(0.317466f, 0.325936f)).maxLuminance(330).minLuminance(100).maxContentLightLevel(400).maxFrameAverageLightLevel(330);
        //vkSetHdrMetadataEXT(device, new long[] {vkSwapChain},hdr);
        boolean resize = false;

        if (syncSemaphoresList.length < currentFrame) return false;

        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkPresentInfoKHR present = VkPresentInfoKHR.calloc(stack)
                    .sType(KHRSwapchain.VK_STRUCTURE_TYPE_PRESENT_INFO_KHR)
                    .pWaitSemaphores(stack.longs(
                            syncSemaphoresList[currentFrame].renderCompleteSemaphore().semaphore()))
                    .swapchainCount(1)
                    .pSwapchains(stack.longs(vkSwapChain))
                    .pImageIndices(stack.ints(currentFrame));

            int err = KHRSwapchain.vkQueuePresentKHR(queue, present);
            if (err == KHRSwapchain.VK_ERROR_OUT_OF_DATE_KHR) {
                resize = true;
            } else if (err == KHRSwapchain.VK_SUBOPTIMAL_KHR) {
                // Not optimal but swap chain can still be used
            } else if (err != VK_SUCCESS) {
                throw new RuntimeException("Failed to present KHR: " + err);
            }
        }
        currentFrame = (currentFrame + 1) % imageViews.length;

        vkDeviceWaitIdle(device);
        return resize;
    }

    public record SurfaceFormat(int imageFormat, int colorSpace) {
    }

    public static VSemaphore createBinarySemaphore(VkDevice device) {
        try (var stack = stackPush()) {
            LongBuffer res = stack.callocLong(1);
            _CHECK_(vkCreateSemaphore(device, VkSemaphoreCreateInfo.calloc(stack).sType$Default(), null, res));
            return new VSemaphore(device, res.get(0));
        }
    }


    public record SyncSemaphores(VSemaphore imgAcquisitionSemaphore, VSemaphore renderCompleteSemaphore) {

        public SyncSemaphores(VkDevice device) {
            this(createBinarySemaphore(device), createBinarySemaphore(device));
        }

        public void cleanup() {
            imgAcquisitionSemaphore.free();
            renderCompleteSemaphore.free();
        }
    }
}