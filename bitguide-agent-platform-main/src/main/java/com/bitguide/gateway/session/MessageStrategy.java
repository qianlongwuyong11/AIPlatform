package com.bitguide.gateway.session;

/**
 * 消息处理策略
 */
public enum MessageStrategy {
    
    /**
     * 串行策略 - 同一Session的消息严格按顺序处理
     * 适用场景：需要保证顺序的多轮对话
     */
    SEQUENTIAL("sequential"),
    
    /**
     * 并行策略 - 消息可以并行处理
     * 适用场景：独立的查询任务
     */
    PARALLEL("parallel"),
    
    /**
     * 取消策略 - 新消息取消之前正在处理的消息
     * 适用场景：用户频繁修改输入
     */
    CANCEL("cancel"),
    
    /**
     * 合并策略 - 短时间内的多个消息合并处理
     * 适用场景：用户快速输入多条消息
     */
    MERGE("merge");
    
    private final String value;
    
    MessageStrategy(String value) {
        this.value = value;
    }
    
    public String getValue() {
        return value;
    }
    
    public static MessageStrategy fromValue(String value) {
        for (MessageStrategy strategy : values()) {
            if (strategy.value.equalsIgnoreCase(value)) {
                return strategy;
            }
        }
        return SEQUENTIAL; // 默认串行
    }
}