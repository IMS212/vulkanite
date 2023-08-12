package me.cortex.vulkanite.lib.memory;

import org.lwjgl.vulkan.VkImageSubresourceLayers;
import org.lwjgl.vulkan.VkImageSubresourceRange;

import static org.lwjgl.vulkan.VK10.VK_IMAGE_ASPECT_COLOR_BIT;

public class VImage {
    private final VmaAllocator.ImageAllocation allocation;
    public final int width;
    public final int height;
    public final int mipLayers;
    public final int format;
    public final VkImageSubresourceRange subresourceRange;
    public VkImageSubresourceLayers subresourceLayers;

    VImage(VmaAllocator.ImageAllocation allocation, int width, int height, int mipLayers, int format) {
        this.allocation = allocation;
        this.width = width;
        this.height = height;
        this.mipLayers = mipLayers;
        this.format = format;
        this.subresourceRange = VkImageSubresourceRange.calloc().set(VK_IMAGE_ASPECT_COLOR_BIT, 0, mipLayers, 0, 1);
        this.subresourceLayers = VkImageSubresourceLayers.calloc().set(VK_IMAGE_ASPECT_COLOR_BIT, mipLayers - 1, 0, 1);
    }

    public void free() {
        allocation.free();
    }

    public long image() {
        return allocation.image;
    }
}
