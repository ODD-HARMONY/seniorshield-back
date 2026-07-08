package com.seniorshield.pipeline;

import java.util.concurrent.locks.ReentrantLock;
import java.util.logging.Logger;

/**
 * /api/analyze 요청을 FIFO 순서로 순차 처리하고, 요청 완료 후 GAP_MS만큼 대기 후
 * 다음 요청을 시작한다. 추출기 밴 / Gemini 429 방지용.
 */
public class AnalyzeQueue {

    private static final Logger log = Logger.getLogger(AnalyzeQueue.class.getName());
    private static final AnalyzeQueue INSTANCE = new AnalyzeQueue();
    public static AnalyzeQueue getInstance() { return INSTANCE; }

    private static final long GAP_MS = 1_000L;
    private final ReentrantLock lock = new ReentrantLock(true); // fair = FIFO 보장
    private volatile long lastFinishedAt = 0L;

    private AnalyzeQueue() {}

    public interface Task<T> {
        T run() throws Exception;
    }

    /**
     * 태스크를 큐에 제출한다. 앞선 요청이 끝난 뒤 GAP_MS 이후에 실행된다.
     * 스레드 인터럽트 시 InterruptedException을 그대로 던진다.
     */
    public <T> T submit(Task<T> task) throws Exception {
        lock.lockInterruptibly();
        try {
            long wait = GAP_MS - (System.currentTimeMillis() - lastFinishedAt);
            if (wait > 0) {
                log.info("AnalyzeQueue: gap wait " + wait + "ms (queue=" + (lock.getQueueLength()) + ")");
                Thread.sleep(wait);
            }
            return task.run();
        } finally {
            lastFinishedAt = System.currentTimeMillis();
            lock.unlock();
        }
    }
}
