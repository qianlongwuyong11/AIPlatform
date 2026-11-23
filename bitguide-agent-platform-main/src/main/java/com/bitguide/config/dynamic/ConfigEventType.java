package com.bitguide.config.dynamic;

/**
 * 配置变更事件类型
 */
public enum ConfigEventType {
    
    /**
     * 配置新增
     */
    PUT,
    
    /**
     * 配置删除
     */
    DELETE,
    
    /**
     * 配置更新（业务层面，表示 key 已存在时的 PUT）
     */
    UPDATE
}