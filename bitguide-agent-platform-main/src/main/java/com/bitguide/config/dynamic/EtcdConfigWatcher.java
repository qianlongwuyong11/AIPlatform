package com.bitguide.config.dynamic;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.etcd.jetcd.ByteSequence;
import io.etcd.jetcd.Client;
import io.etcd.jetcd.KeyValue;
import io.etcd.jetcd.Watch;
import io.etcd.jetcd.kv.GetResponse;
import io.etcd.jetcd.options.GetOption;
import io.etcd.jetcd.options.WatchOption;
import io.etcd.jetcd.watch.WatchEvent;
import io.etcd.jetcd.watch.WatchResponse;
import lombok.extern.slf4j.Slf4j;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Etcd 配置监听器
 * 负责监听 Etcd 中的配置变更并通知监听器
 */
@Slf4j
public class EtcdConfigWatcher {
    
    private final EtcdConfig config;
    private final ObjectMapper objectMapper;
    private final List<ConfigChangeListener> listeners = new CopyOnWriteArrayList<>();
    
    private Client etcdClient;
    private Watch.Watcher watcher;
    
    /**
     * 连接状态标志
     */
    private volatile boolean connected = false;
    
    /**
     * 当前配置缓存（用于判断是新增还是更新）
     */
    private final Map<String, String> configCache = new ConcurrentHashMap<>();
    
    public EtcdConfigWatcher(EtcdConfig config) {
        this.config = config;
        this.objectMapper = new ObjectMapper();
    }
    
    /**
     * 初始化并启动监听
     */
    public void start() {
        if (!config.isEnabled()) {
            log.info("动态配置未启用，跳过 Etcd 连接");
            return;
        }
        
        try {
            // 创建 Etcd 客户端 (jetcd 0.7.6 API)
            Client client = Client.builder()
                    .endpoints(config.getEndpoints())
                    .build();
            
            // 如果配置了认证
            if (config.getUsername() != null && config.getPassword() != null) {
                client = Client.builder()
                        .endpoints(config.getEndpoints())
                        .user(ByteSequence.from(config.getUsername(), StandardCharsets.UTF_8))
                        .password(ByteSequence.from(config.getPassword(), StandardCharsets.UTF_8))
                        .build();
            }
            
            this.etcdClient = client;
            this.connected = true;
            log.info("Etcd 客户端连接成功: {}", String.join(",", config.getEndpoints()));
            
            // 初始加载所有配置
            loadAllConfigs();
            
            // 启动 Watch 监听
            startWatching();
            
        } catch (Throwable t) {
            this.connected = false;
            log.warn("Etcd 客户端初始化失败: {}，应用将继续运行，等待动态配置", t.getMessage());
            log.debug("Etcd 连接失败详情", t);
            // 不抛出异常，允许应用继续运行
        }
    }
    
    /**
     * 检查是否已连接
     */
    public boolean isConnected() {
        return connected;
    }
    
    /**
     * 初始加载所有配置
     */
    private void loadAllConfigs() {
        String prefix = config.getConfigPrefix();
        ByteSequence prefixBytes = ByteSequence.from(prefix, StandardCharsets.UTF_8);
        
        try {
            // jetcd 0.7.6 使用 GetOption.builder()
            GetOption getOption = GetOption.builder()
                    .isPrefix(true)
                    .build();
            
            GetResponse response = etcdClient.getKVClient()
                    .get(prefixBytes, getOption)
                    .get(config.getRequestTimeoutMs(), TimeUnit.MILLISECONDS);
            
            List<KeyValue> kvs = response.getKvs();
            log.info("从 Etcd 加载配置数量: {}", kvs.size());
            
            if (kvs.isEmpty()) {
                log.info("Etcd 中暂无配置，请通过动态配置添加 Agent");
                return;
            }
            
            for (KeyValue kv : kvs) {
                String key = kv.getKey().toString(StandardCharsets.UTF_8);
                String value = kv.getValue().toString(StandardCharsets.UTF_8);
                configCache.put(key, value);
                
                // 触发初始加载事件（作为 PUT 事件）
                ConfigEvent event = new ConfigEvent(ConfigEventType.PUT, key, value, null);
                notifyListeners(event);
            }
            
        } catch (InterruptedException | ExecutionException | TimeoutException e) {
            log.warn("加载 Etcd 配置失败: {}，应用将继续启动，等待动态配置", e.getMessage());
            // 不抛出异常，允许应用继续运行
        }
    }
    
    /**
     * 启动 Watch 监听
     */
    private void startWatching() {
        String prefix = config.getConfigPrefix();
        ByteSequence prefixBytes = ByteSequence.from(prefix, StandardCharsets.UTF_8);
        
        // jetcd 0.7.6 使用 WatchOption.builder()
        WatchOption watchOption = WatchOption.builder()
                .isPrefix(true)
                .withPrevKV(true)
                .build();
        
        // 使用 Watch.listener() 创建监听器
        Watch.Listener listener = Watch.listener(this::handleWatchResponse);
        
        watcher = etcdClient.getWatchClient().watch(prefixBytes, watchOption, listener);
        
        log.info("开始监听 Etcd 配置变更，前缀: {}", prefix);
    }
    
    /**
     * 处理 Watch 响应
     */
    private void handleWatchResponse(WatchResponse response) {
        for (WatchEvent event : response.getEvents()) {
            try {
                ConfigEvent configEvent = convertToConfigEvent(event);
                if (configEvent != null) {
                    // 更新缓存
                    updateCache(configEvent);
                    
                    // 通知监听器
                    notifyListeners(configEvent);
                }
            } catch (Exception e) {
                log.error("处理配置变更事件失败", e);
            }
        }
    }
    
    /**
     * 转换 Etcd 事件为配置事件
     */
    private ConfigEvent convertToConfigEvent(WatchEvent event) {
        String key = event.getKeyValue().getKey().toString(StandardCharsets.UTF_8);
        String newValue = event.getKeyValue().getValue().toString(StandardCharsets.UTF_8);
        String oldValue = null;
        
        ConfigEventType eventType;
        switch (event.getEventType()) {
            case PUT:
                // 判断是新增还是更新
                if (configCache.containsKey(key)) {
                    eventType = ConfigEventType.UPDATE;
                    oldValue = configCache.get(key);
                } else {
                    eventType = ConfigEventType.PUT;
                }
                break;
            case DELETE:
                eventType = ConfigEventType.DELETE;
                oldValue = event.getPrevKV() != null 
                        ? event.getPrevKV().getValue().toString(StandardCharsets.UTF_8)
                        : configCache.get(key);
                break;
            default:
                log.debug("忽略未知事件类型: {}", event.getEventType());
                return null;
        }
        
        return new ConfigEvent(eventType, key, newValue, oldValue);
    }
    
    /**
     * 更新配置缓存
     */
    private void updateCache(ConfigEvent event) {
        String key = event.getKey();
        switch (event.getType()) {
            case PUT:
            case UPDATE:
                configCache.put(key, event.getNewValue());
                break;
            case DELETE:
                configCache.remove(key);
                break;
        }
    }
    
    /**
     * 通知所有监听器
     */
    private void notifyListeners(ConfigEvent event) {
        log.info("配置变更事件: {}", event);
        for (ConfigChangeListener listener : listeners) {
            try {
                listener.onConfigChange(event);
            } catch (Exception e) {
                log.error("监听器处理配置变更失败: {}", listener.getClass().getName(), e);
            }
        }
    }
    
    /**
     * 添加配置变更监听器
     */
    public void addListener(ConfigChangeListener listener) {
        listeners.add(listener);
    }
    
    /**
     * 移除配置变更监听器
     */
    public void removeListener(ConfigChangeListener listener) {
        listeners.remove(listener);
    }
    
    /**
     * 获取当前缓存的配置
     */
    public Map<String, String> getConfigCache() {
        return new ConcurrentHashMap<>(configCache);
    }
    
    /**
     * 手动获取指定 key 的配置
     */
    public String getConfig(String key) {
        if (!connected || etcdClient == null) {
            return null;
        }
        try {
            ByteSequence keyBytes = ByteSequence.from(key, StandardCharsets.UTF_8);
            GetResponse response = etcdClient.getKVClient()
                    .get(keyBytes)
                    .get(config.getRequestTimeoutMs(), TimeUnit.MILLISECONDS);
            
            List<KeyValue> kvs = response.getKvs();
            if (!kvs.isEmpty()) {
                return kvs.get(0).getValue().toString(StandardCharsets.UTF_8);
            }
            return null;
        } catch (Exception e) {
            log.error("获取配置失败: {}", key, e);
            return null;
        }
    }
    
    /**
     * 停止监听
     */
    public void stop() {
        if (watcher != null) {
            try {
                watcher.close();
                log.info("Etcd Watch 已关闭");
            } catch (Exception e) {
                log.warn("关闭 Etcd Watch 失败", e);
            }
        }
        
        if (etcdClient != null) {
            try {
                etcdClient.close();
                log.info("Etcd 客户端已关闭");
            } catch (Exception e) {
                log.warn("关闭 Etcd 客户端失败", e);
            }
        }
        
        connected = false;
        configCache.clear();
    }
}