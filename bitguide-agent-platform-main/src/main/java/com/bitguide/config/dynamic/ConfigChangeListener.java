package com.bitguide.config.dynamic;

/**
 * 配置变更监听器
 */
public interface ConfigChangeListener {
    
    /**
     * 配置变更回调
     * 
     * @param event 配置变更事件
     */
    void onConfigChange(ConfigEvent event);
}