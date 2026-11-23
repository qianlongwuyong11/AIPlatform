package com.bitguide.config.dynamic;

import lombok.Data;

/**
 * Etcd 连接配置
 */
@Data
public class EtcdConfig {
    
    /**
     * Etcd 集群地址列表
     */
    private String[] endpoints = {"http://localhost:2379"};
    
    /**
     * 配置根路径
     */
    private String configPrefix = "/bitguide/config";
    
    /**
     * 连接超时时间（毫秒）
     */
    private long connectionTimeoutMs = 5000;
    
    /**
     * 请求超时时间（毫秒）
     */
    private long requestTimeoutMs = 10000;
    
    /**
     * Watch 事件队列大小
     */
    private int watchQueueSize = 100;
    
    /**
     * 是否启用动态配置
     */
    private boolean enabled = true;
    
    /**
     * 用户名（可选，用于认证）
     */
    private String username;
    
    /**
     * 密码（可选，用于认证）
     */
    private String password;
}