package me.cortex.vulkanite.lib.cmd;

import com.google.common.collect.HashMultimap;
import com.google.common.collect.Multimap;
import com.google.common.collect.Multimaps;
import com.mojang.blaze3d.systems.RenderSystem;
import io.netty.util.internal.shaded.org.jctools.queues.MessagePassingQueue.Consumer;
import it.unimi.dsi.fastutil.ints.Int2LongArrayMap;
import it.unimi.dsi.fastutil.ints.Int2LongMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectSortedMap;
import me.cortex.vulkanite.lib.base.VRef;
import me.cortex.vulkanite.lib.other.sync.VFence;
import me.cortex.vulkanite.lib.other.sync.VSemaphore;
import org.lwjgl.vulkan.*;

import java.nio.IntBuffer;
import java.nio.LongBuffer;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.ReentrantLock;

import static me.cortex.vulkanite.lib.other.VUtil._CHECK_;
import static org.lwjgl.system.MemoryStack.stackPush;
import static org.lwjgl.vulkan.VK10.*;
import static org.lwjgl.vulkan.VK12.vkGetSemaphoreCounterValue;
import static org.lwjgl.vulkan.VK12.vkWaitSemaphores;

//Manages multiple command queues and fence synchronizations
public class CommandManager {
    private final VkDevice device;
    private final Queue[] queues;
    private final ThreadLocal<VRef<VCommandPool>> threadLocalPool =
            ThreadLocal.withInitial(() -> {
                var pool = createSingleUsePool();
                pool.get().setDebugUtilsObjectName("Thread-local single use pool");
                return pool;
            });

    public CommandManager(VkDevice device, int queues) {
        this.device = device;
        this.queues = new Queue[queues];
        for (int i = 0; i < queues; i++) {
            this.queues[i] = new Queue(i, device);
        }
    }

    public VRef<VCommandPool> createSingleUsePool() {
        return createPool(VK_COMMAND_POOL_CREATE_TRANSIENT_BIT);
    }

    public VRef<VCommandPool> createPool(int flags) {
        return new VRef<>(new VCommandPool(device, flags));
    }

    public VCommandPool getSingleUsePool() {
        return threadLocalPool.get().get();
    }

    public void submitOnceAndWait(int queueId, final VRef<VCmdBuff> cmdBuff) {
        long exec = this.submit(queueId, cmdBuff);
        this.hostWaitForExecution(queueId, exec);
        cmdBuff.close();
    }

    public void executeWait(Consumer<VCmdBuff> cmdbuf) {
        var cmd = getSingleUsePool().createCommandBuffer();
        cmdbuf.accept(cmd.get());
        submitOnceAndWait(0, cmd);
    }

    /**
     * Enqueues a wait for a timeline value on a queue
     *
     * @param waitQueueId      The queue that will wait
     * @param executionQueueId The queue whose timeline value will be waited for
     * @param execution        The timeline value to wait for
     */
    public void queueWaitForExeuction(int waitQueueId, int executionQueueId, long execution) {
        queues[waitQueueId].waitForExecution(executionQueueId, execution);
    }

    /**
     * Enqueues a wait for a timeline value on a queue
     *
     * @param waitQueueId      The queue that will wait
     * @param executionQueueId The queue whose timeline value will be waited for
     * @param executions       The timeline values to wait for
     */
    public void queueWaitForExecutions(int waitQueueId, int executionQueueId, List<Long> executions) {
        queues[waitQueueId].waitForExecutions(executionQueueId, executions);
    }

    /**
     * Wait on the host for a timeline value on a queue
     *
     * @param waitQueueId The queue whose timeline value will be waited for
     * @param execution   The timeline value to wait for
     */
    public void hostWaitForExecution(int waitQueueId, long execution) {
        var waitQueue = queues[waitQueueId];

        try (var stack = stackPush()) {
            VkSemaphoreWaitInfo waitInfo = VkSemaphoreWaitInfo.calloc(stack)
                    .sType$Default()
                    .pSemaphores(stack.longs(waitQueue.timelineSema.get().address()))
                    .semaphoreCount(1)
                    .pValues(stack.longs(execution));

            _CHECK_(vkWaitSemaphores(device, waitInfo, -1));
        }

        waitQueue.updateCompletedTimestamp(execution);
        waitQueue.collect();
    }

    public long getQueueCurrentExecution(int queueId) {
        return queues[queueId].getCurrentExecution();
    }

    public long submit(int queueId, final VRef<VCmdBuff> cmdBuff) {
        return submit(queueId, cmdBuff, null, null, null);
    }

    public long submit(int queueId, final VRef<VCmdBuff> cmdBuff, List<VRef<VSemaphore>> waits, List<VRef<VSemaphore>> triggers, VFence fence) {
        if (queueId == 0) {
            RenderSystem.assertOnRenderThread();
        }
        return queues[queueId].submit(cmdBuff, queues, waits, triggers, fence);
    }

    public void waitQueueIdle(int queue) {
        queues[queue].waitIdle();
        queues[queue].collect();
    }

    public void newFrame() {
        for (var queue : queues) {
            queue.newFrame();
        }
    }

    private static class Queue {
        private record Submission(long t, VRef<VCmdBuff> ref) {}

        public final VkQueue queue;
        private final Int2LongArrayMap waitingFor = new Int2LongArrayMap();
        private final List<Submission> submitted = new ArrayList<>();
        public final VRef<VSemaphore> timelineSema;
//        public final Deque<Long> frameTimestamps = new ArrayDeque<>(3);
        public AtomicLong timeline = new AtomicLong(1);
        public AtomicLong completedTimestamp = new AtomicLong(0);

        public Queue(int queueId, VkDevice device) {
            try (var stack = stackPush()) {
                var pQ = stack.pointers(0);
                vkGetDeviceQueue(device, 0, queueId, pQ);

                this.queue = new VkQueue(pQ.get(0), device);

                var timelineCreateInfo = VkSemaphoreTypeCreateInfo.calloc(stack)
                        .sType$Default()
                        .semaphoreType(VK12.VK_SEMAPHORE_TYPE_TIMELINE)
                        .initialValue(0);

                var semaphoreCreateInfo = VkSemaphoreCreateInfo.calloc(stack)
                        .sType$Default()
                        .pNext(timelineCreateInfo.address());

                var pSemaphore = stack.longs(0);
                vkCreateSemaphore(device, semaphoreCreateInfo, null, pSemaphore);
                this.timelineSema = VSemaphore.create(device, pSemaphore.get(0));
            }
        }

        public void updateCompletedTimestamp(long newTimestamp) {
            // completedTimestamp.atomicMax(newTimestamp);
            long current;
            do {
                current = completedTimestamp.get();
            } while (current < newTimestamp && !completedTimestamp.compareAndSet(current, newTimestamp));
        }

        public void newFrame() {
//            if (frameTimestamps.size() >= 3) {
//                long oldest = frameTimestamps.removeFirst();
//                completedTimestamp = Long.max(completedTimestamp, oldest);
//            }
//            frameTimestamps.addLast(completedTimestamp);

            updateCompletedTimestamp(getCurrentExecution());
            collect();
        }

        public void collect() {
            synchronized (submitted) {
                long completedTimestamp = this.completedTimestamp.get();

                submitted.removeIf((rec) -> {
                    if (rec.t <= completedTimestamp) {
                        rec.ref.close();
                        return true;
                    }
                    return false;
                });
            }
        }

        public void waitIdle() {
            synchronized (waitingFor) {
                vkQueueWaitIdle(queue);
                waitingFor.clear();
            }

            updateCompletedTimestamp(getCurrentExecution());
            collect();
        }

        public void waitForExecution(int execQueue, long execution) {
            synchronized (waitingFor) {
                waitingFor.put(execQueue, Long.max(waitingFor.getOrDefault(execQueue, 0), execution));
            }
        }

        public void waitForExecutions(int execQueue, List<Long> executions) {
            synchronized (waitingFor) {
                long execMax = executions.stream().mapToLong(Long::longValue).max().orElse(waitingFor.getOrDefault(execQueue, 0));
                waitingFor.put(execQueue, execMax);
            }
        }

        public long submit(final VRef<VCmdBuff> cmdBuff, Queue[] queues, List<VRef<VSemaphore>> waits, List<VRef<VSemaphore>> triggers, VFence fence) {
            long t = timeline.getAndIncrement();

            synchronized (waitingFor) {
                // We need to hold the lock until we have submitted cmdbuf into the queue
                // Otherwise it might be possible that another thread will submit a cmdbuf without waiting for anything
                // Queue submits has strong API ordering
                var timelineWaitingEntries = waitingFor.int2LongEntrySet();
                waitingFor.clear();

                try (var stack = stackPush()) {

                    int waitCount = (waits == null ? 0 : waits.size()) + timelineWaitingEntries.size();
                    int triggerCount = (triggers == null ? 0 : triggers.size()) + 1;

                    LongBuffer waitSemaphores = stack.mallocLong(waitCount);
                    LongBuffer signalSemaphores = stack.mallocLong(triggerCount);
                    LongBuffer waitTimelineValues = stack.mallocLong(waitCount);
                    LongBuffer signalTimelineValues = stack.mallocLong(triggerCount);
                    IntBuffer waitStages = stack.mallocInt(waitCount);
                    // Traditional binary semaphores
                    if (waits != null) {
                        for (var wait : waits) {
                            waitSemaphores.put(wait.get().address());
                            waitTimelineValues.put(0);
                            waitStages.put(VK_PIPELINE_STAGE_TOP_OF_PIPE_BIT);
                            cmdBuff.get().addSemaphoreRef(wait);
                        }
                    }
                    if (triggers != null) {
                        for (var trigger : triggers) {
                            signalSemaphores.put(trigger.get().address());
                            signalTimelineValues.put(0);
                            cmdBuff.get().addSemaphoreRef(trigger);
                        }
                    }
                    // Timeline semaphores
                    for (var entry : timelineWaitingEntries) {
                        if (entry.getLongValue() == 0) {
                            continue;
                        }
                        var sema = queues[entry.getIntKey()].timelineSema;
                        waitSemaphores.put(sema.get().address());
                        waitTimelineValues.put(entry.getLongValue());
                        waitStages.put(VK_PIPELINE_STAGE_TOP_OF_PIPE_BIT);
                        cmdBuff.get().addSemaphoreRef(sema);
                    }
                    signalSemaphores.put(timelineSema.get().address());
                    signalTimelineValues.put(t);
                    cmdBuff.get().addSemaphoreRef(timelineSema);

                    waitSemaphores.rewind();
                    signalSemaphores.rewind();
                    waitTimelineValues.rewind();
                    signalTimelineValues.rewind();
                    waitStages.rewind();

                    var timelineSubmitInfo = VkTimelineSemaphoreSubmitInfo.calloc(stack)
                            .sType$Default()
                            .pWaitSemaphoreValues(waitTimelineValues)
                            .waitSemaphoreValueCount(waitCount)
                            .pSignalSemaphoreValues(signalTimelineValues)
                            .signalSemaphoreValueCount(triggerCount);

                    var submit = VkSubmitInfo.calloc(stack).sType$Default()
                            .pCommandBuffers(stack.pointers(cmdBuff.get().seal()))
                            .pWaitSemaphores(waitSemaphores)
                            .waitSemaphoreCount(waitCount)
                            .pWaitDstStageMask(waitStages)
                            .pSignalSemaphores(signalSemaphores)
                            .pNext(timelineSubmitInfo.address());
                    _CHECK_(vkQueueSubmit(queue, submit, fence == null ? 0 : fence.address()));
                }
            }

            synchronized (submitted) {
                submitted.add(new Submission(t, cmdBuff.addRef()));
            }

            return t;
        }

        public long getCurrentExecution() {
            try (var stack = stackPush()) {
                var lp = stack.longs(0);
                vkGetSemaphoreCounterValue(queue.getDevice(), timelineSema.get().address(), lp);
                return lp.get(0);
            }
        }
    }
}
