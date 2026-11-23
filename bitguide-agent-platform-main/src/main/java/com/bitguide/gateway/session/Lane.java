package com.bitguide.gateway.session;

import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 车道（处理队列）
 * 每个Session独占一个Lane，实现串行处理
 */
public class Lane {
    
    private final String laneId;
    private final AtomicReference<LaneStatus> status;
    private final AtomicLong queueSize;
    private final AtomicLong processedCount;
    
    public Lane(String laneId) {
        this.laneId = laneId;
        this.status = new AtomicReference<>(LaneStatus.IDLE);
        this.queueSize = new AtomicLong(0);
        this.processedCount = new AtomicLong(0);
    }
    
    /**
     * 尝试进入车道
     * @return 是否成功进入
     */
    public boolean tryEnter() {
        // CAS操作，只有IDLE状态才能进入
        return status.compareAndSet(LaneStatus.IDLE, LaneStatus.PROCESSING);
    }
    
    /**
     * 离开车道（处理完成)
     */
    public void leave() {
        status.set(LaneStatus.IDLE);
        processedCount.incrementAndGet();
        queueSize.decrementAndGet();
    }
    
    /**
     * 加入队列等待
     */
    public void enqueue() {
        queueSize.incrementAndGet();
    }
    
    /**
     * 获取车道状态
     */
    public LaneStatus getStatus() {
        return status.get();
    }
    
    /**
     * 获取队列大小
     */
    public long getQueueSize() {
        return queueSize.get();
    }
    
    /**
     * 获取已处理数量
     */
    public long getProcessedCount() {
        return processedCount.get();
    }
    
    /**
     * 是否空闲
     */
    public boolean isIdle() {
        return status.get() == LaneStatus.IDLE;
    }
    
    /**
     * 车道ID
     */
    public String getLaneId() {
        return laneId;
    }
    
    /**
     * 车道状态枚举
     */
    public enum LaneStatus {
        IDLE,       // 空闲
        PROCESSING, // 处理中
        BLOCKED     // 阻塞
    }
}