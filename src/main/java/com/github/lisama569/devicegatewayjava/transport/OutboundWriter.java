package com.github.lisama569.devicegatewayjava.transport;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.OutputStream;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/**
 * 每会话单写线程 + 有界待发队列（按帧数计上限；帧长本身受 maxFrameLength 限制，
 * 故待发送字节也有界）。队列满即拒绝入队，不阻塞调用方、不无界排队。
 * 单写线程保证同连接帧严格串行，绝不串帧。
 */
public final class OutboundWriter {

    private static final Logger log = LoggerFactory.getLogger(OutboundWriter.class);

    private final OutputStream out;
    private final BlockingQueue<Runnable> queue;
    private final ThreadPoolExecutor executor;
    private volatile boolean closed = false;

    public OutboundWriter(OutputStream out, int capacity, String connectionId) {
        this.out = out;
        this.queue = new ArrayBlockingQueue<>(Math.max(1, capacity));
        this.executor = new ThreadPoolExecutor(1, 1, 0L, TimeUnit.MILLISECONDS,
                queue,
                r -> {
                    Thread t = new Thread(r, "gw-write-" + connectionId);
                    t.setDaemon(true);
                    return t;
                },
                new ThreadPoolExecutor.AbortPolicy());
    }

    /** 入队一帧；关闭中或队列满返回 false（调用方按 OUTBOUND_BUFFER_FULL 处理）。 */
    public boolean offer(byte[] frame) {
        if (closed) return false;
        try {
            executor.execute(() -> doWrite(frame));
            return true;
        } catch (RuntimeException e) {
            return false; // RejectedExecutionException：队列已满
        }
    }

    private void doWrite(byte[] frame) {
        try {
            synchronized (out) {
                out.write(frame);
                out.flush();
            }
        } catch (IOException e) {
            log.debug("write failed, closing writer: {}", e.getMessage());
            shutdown();
        }
    }

    public boolean isClosed() { return closed; }

    public int queuedFrames() { return queue.size(); }

    /**
     * 排空后关闭：把“flush + afterDrain + 停止执行器”作为写线程的最后一个排队动作，
     * 保证此前入队的所有帧先全部写出，再执行 afterDrain（通常是关闭 socket）。可重复调用，只生效一次。
     */
    public void closeAfterDrain(Runnable afterDrain) {
        if (closed) return;
        closed = true;
        try {
            executor.execute(() -> {
                try {
                    out.flush();
                } catch (IOException ignored) {
                    // 尽力 flush
                }
                if (afterDrain != null) {
                    afterDrain.run();
                }
                executor.shutdown();
            });
        } catch (RuntimeException e) {
            shutdown(); // 队列异常时兜底强制关闭
        }
    }

    /** 优雅关闭：停止接收新帧，排空已入队帧；超时强制退出。 */
    public void close() {
        shutdown();
    }

    /** 等待写线程把已入队动作（含 closeAfterDrain 的收尾回调）执行完；超时或中断则返回。 */
    public void awaitTerminationQuietly(long ms) {
        try {
            executor.awaitTermination(ms, TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private void shutdown() {
        if (closed) return;
        closed = true;
        executor.shutdown();
        try {
            if (!executor.awaitTermination(2, TimeUnit.SECONDS)) {
                executor.shutdownNow();
            }
        } catch (InterruptedException e) {
            executor.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }
}
