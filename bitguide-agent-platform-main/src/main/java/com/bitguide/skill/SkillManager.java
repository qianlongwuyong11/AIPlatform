package com.bitguide.skill;

import com.bitguide.log.ConsoleLog;
import com.bitguide.agent.Agent;
import dev.langchain4j.model.chat.ChatLanguageModel;
import lombok.extern.slf4j.Slf4j;

import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 技能管理器
 * 负责技能的注册、查找、路由匹配和增强执行
 *
 * 对齐 Go 版本的 Manager 设计，新增：
 * - matchAndExecute: 匹配技能并通过 SkillEnhancer 增强 Agent 执行
 * - 保留 matchSkills / matchSkillsByLLM 路由逻辑
 */
@Slf4j
public class SkillManager {
    
    /**
     * 技能注册表
     */
    private final Map<String, Skill> skills = new HashMap<>();
    
    /**
     * 技能加载器
     */
    private final SkillLoader loader;
    
    /**
     * 技能增强器（可选，设置后支持 matchAndExecute）
     */
    private SkillEnhancer enhancer;
    
    /**
     * 是否启用热重载
     */
    private boolean hotReloadEnabled = true;
    
    /**
     * LLM 模型（用于智能路由）
     */
    private ChatLanguageModel model;
    
    /**
     * 是否使用 LLM 路由
     */
    private boolean useLlmRouting = true;
    
    public SkillManager() {
        this.loader = new SkillLoader();
    }
    
    public SkillManager(String skillsDirectory) {
        this.loader = new SkillLoader(skillsDirectory);
    }
    
    public SkillManager(SkillLoader loader) {
        this.loader = loader;
    }
    
    /**
     * 设置技能增强器
     */
    public void setEnhancer(SkillEnhancer enhancer) {
        this.enhancer = enhancer;
    }
    
    /**
     * 设置 LLM 模型（用于智能路由）
     */
    public void setModel(ChatLanguageModel model) {
        this.model = model;
    }
    
    /**
     * 设置是否使用 LLM 路由
     */
    public void setUseLlmRouting(boolean useLlmRouting) {
        this.useLlmRouting = useLlmRouting;
    }
    
    /**
     * 从目录加载所有技能
     */
    public void loadFromDirectory() {
        List<Skill> loadedSkills = loader.loadAll();
        loadedSkills.forEach(this::registerSkill);
        ConsoleLog.info("[Skill]", "从目录加载了 %d 个技能", loadedSkills.size());
    }
    
    /**
     * 从指定目录加载所有技能
     */
    public void loadFromDirectory(String directory) {
        SkillLoader dirLoader = new SkillLoader(directory);
        List<Skill> loadedSkills = dirLoader.loadAll();
        loadedSkills.forEach(this::registerSkill);
        log.info("从目录 {} 加载了 {} 个技能", directory, loadedSkills.size());
    }
    
    /**
     * 注册技能（以 id 为 key，与 Agent 的 skillRefs 对齐）
     */
    public void registerSkill(Skill skill) {
        if (skill == null || skill.getId() == null) {
            log.warn("尝试注册空技能，已忽略");
            return;
        }

        skills.put(skill.getId(), skill);
        log.info("技能已注册: {} - {}", skill.getId(), skill.getName());
    }
    
    /**
     * 批量注册技能
     */
    public void registerSkills(List<Skill> skillList) {
        if (skillList == null) return;
        skillList.forEach(this::registerSkill);
    }
    
    /**
     * 获取技能（支持热重载）
     */
    public Skill getSkill(String name) {
        Skill skill = skills.get(name);
        
        if (skill != null && hotReloadEnabled) {
            Skill reloaded = loader.reload(skill);
            if (reloaded != null && reloaded.getLastModified() > skill.getLastModified()) {
                skills.put(name, reloaded);
                return reloaded;
            }
        }
        
        return skill;
    }
    
    /**
     * 获取所有技能
     */
    public Collection<Skill> getAllSkills() {
        return Collections.unmodifiableCollection(skills.values());
    }
    
    /**
     * 获取所有技能名称
     */
    public Set<String> getSkillNames() {
        return Collections.unmodifiableSet(skills.keySet());
    }
    
    /**
     * 检查技能是否存在
     */
    public boolean hasSkill(String name) {
        return skills.containsKey(name);
    }
    
    /**
     * 注销技能
     */
    public void unregisterSkill(String name) {
        Skill removed = skills.remove(name);
        if (removed != null) {
            log.info("技能已注销: {}", name);
        }
    }
    
    /**
     * 重新加载所有技能
     */
    public void reloadAll() {
        log.info("重新加载所有技能...");
        skills.clear();
        loadFromDirectory();
    }
    
    /**
     * 重新加载指定技能
     */
    public boolean reload(String name) {
        Skill skill = skills.get(name);
        if (skill == null) {
            log.warn("技能不存在，无法重新加载: {}", name);
            return false;
        }
        
        Skill reloaded = loader.reload(skill);
        if (reloaded != null) {
            skills.put(name, reloaded);
            log.info("技能已重新加载: {}", name);
            return true;
        }
        return false;
    }
    
    /**
     * 清空所有技能
     */
    public void clear() {
        skills.clear();
        log.info("所有技能已清空");
    }
    
    /**
     * 获取技能数量
     */
    public int size() {
        return skills.size();
    }
    
    /**
     * 设置是否启用热重载
     */
    public void setHotReloadEnabled(boolean enabled) {
        this.hotReloadEnabled = enabled;
    }
    
    /**
     * 获取技能描述列表（用于 LLM 理解可用技能）
     */
    public String getSkillDescriptions() {
        if (skills.isEmpty()) {
            return "当前没有可用技能";
        }
        
        StringBuilder sb = new StringBuilder("可用技能列表：\n");
        for (Skill skill : skills.values()) {
            sb.append("- ").append(skill.getName())
              .append(": ").append(skill.getDescription());
            
            if (skill.getToolRefs() != null && !skill.getToolRefs().isEmpty()) {
                sb.append(" [依赖工具: ").append(String.join(", ", skill.getToolRefs())).append("]");
            }
            sb.append("\n");
        }
        return sb.toString();
    }
    
    /**
     * 匹配技能并通过 SkillEnhancer 增强 Agent 执行
     * 对齐 Go 版本的 MatchAndExecute
     *
     * @param agent     目标 Agent
     * @param userInput 用户输入
     * @param sessionId 会话ID
     * @return MatchResult 包含匹配结果（是否匹配、执行结果、错误信息）
     */
    public MatchResult matchAndExecute(Agent agent, String userInput, String sessionId) {
        List<Skill> matched = matchSkills(userInput);
        if (matched.isEmpty()) {
            return MatchResult.notMatched();
        }
        
        Skill skill = matched.get(0);
        ConsoleLog.skillMatched(skill.getName(), "");
        
        if (enhancer == null) {
            log.warn("[Skill] SkillEnhancer 未设置，无法执行技能");
            return MatchResult.error("SkillEnhancer 未设置");
        }
        
        try {
            String response = enhancer.enhanceAndExecute(agent, skill, userInput, sessionId);
            return MatchResult.success(response, skill.getName());
        } catch (Exception e) {
            ConsoleLog.skillError(skill.getName(), e);
            return MatchResult.error("技能执行失败: " + e.getMessage());
        }
    }
    
    /**
     * 根据用户输入匹配合适的技能
     * 优先使用 LLM 路由，降级到关键词匹配
     */
    public List<Skill> matchSkills(String userInput) {
        if (skills.isEmpty()) {
            return Collections.emptyList();
        }
        
        // 如果启用了 LLM 路由且有模型，使用 LLM 路由
        if (useLlmRouting && model != null) {
            try {
                Skill matched = matchSkillByLLM(userInput);
                if (matched != null) {
                    return Collections.singletonList(matched);
                }
            } catch (Exception e) {
                log.warn("LLM 路由失败，降级到关键词匹配: {}", e.getMessage());
            }
        }
        
        // 降级：关键词匹配
        return matchSkillsByKeywords(userInput);
    }
    
    /**
     * 使用 LLM 进行技能路由
     */
    private Skill matchSkillByLLM(String userInput) {
        if (skills.isEmpty()) {
            return null;
        }
        
        String skillList = getSkillDescriptionsForRouting();
        String routingPrompt = buildRoutingPrompt(userInput, skillList);
        
        String response = model.generate(routingPrompt);
        String selectedSkill = parseRoutingResponse(response);
        
        log.info("LLM 路由结果: {} -> {}", userInput, selectedSkill);
        
        if (selectedSkill == null || "none".equalsIgnoreCase(selectedSkill.trim())) {
            return null;
        }
        
        return skills.get(selectedSkill.trim());
    }
    
    /**
     * 构建路由提示词
     */
    private String buildRoutingPrompt(String userInput, String skillList) {
        return """
你是一个技能路由器。根据用户输入，从可用技能列表中选择最合适的一个技能。

用户输入:
%s

可用技能:
%s

请分析用户意图，返回最合适的技能名称（只需要返回技能名称，不要其他任何内容）。
如果没有匹配的技能，返回 "none"。
""".formatted(userInput, skillList);
    }
    
    /**
     * 获取用于路由的技能描述（同时包含 id 和 name）
     */
    private String getSkillDescriptionsForRouting() {
        StringBuilder sb = new StringBuilder();
        int index = 1;
        for (Skill skill : skills.values()) {
            sb.append(index++).append(". ")
              .append(skill.getId())
              .append(" (").append(skill.getName()).append(")")
              .append(": ")
              .append(skill.getDescription() != null ? skill.getDescription() : "无描述")
              .append("\n");
        }
        return sb.toString();
    }
    
    /**
     * 解析 LLM 路由响应
     */
    private String parseRoutingResponse(String response) {
        if (response == null || response.isBlank()) {
            return "none";
        }

        String cleaned = response.trim();

        // 尝试直接匹配技能 id
        for (String skillId : skills.keySet()) {
            if (cleaned.equalsIgnoreCase(skillId) ||
                cleaned.contains(skillId)) {
                return skillId;
            }
        }

        // 尝试按 name 匹配
        for (Skill skill : skills.values()) {
            if (skill.getName() != null &&
                (cleaned.equalsIgnoreCase(skill.getName()) ||
                 cleaned.contains(skill.getName()))) {
                return skill.getId();
            }
        }

        // 尝试提取数字索引
        Pattern numPattern = Pattern.compile("^(\\d+)[.、)\\s]");
        Matcher matcher = numPattern.matcher(cleaned);
        if (matcher.find()) {
            try {
                int index = Integer.parseInt(matcher.group(1));
                if (index > 0 && index <= skills.size()) {
                    return new ArrayList<>(skills.values()).get(index - 1).getId();
                }
            } catch (NumberFormatException ignored) {}
        }

        // 如果响应包含 "none" 或不匹配任何技能
        if (cleaned.toLowerCase().contains("none") ||
            cleaned.toLowerCase().contains("无") ||
            cleaned.toLowerCase().contains("不匹配")) {
            return "none";
        }

        return "none";
    }
    
    /**
     * 基于关键词匹配技能（降级方案）
     */
    private List<Skill> matchSkillsByKeywords(String userInput) {
        List<Skill> matched = new ArrayList<>();
        String inputLower = userInput.toLowerCase();
        
        for (Skill skill : skills.values()) {
            // 匹配技能名称
            if (inputLower.contains(skill.getName().toLowerCase())) {
                matched.add(skill);
                continue;
            }
            
            // 匹配描述中的关键词
            if (skill.getDescription() != null && 
                containsKeywords(inputLower, skill.getDescription().toLowerCase())) {
                matched.add(skill);
                continue;
            }
            
            // 匹配示例
            if (skill.getExamples() != null) {
                for (Skill.Example example : skill.getExamples()) {
                    if (example.getUserInput() != null && 
                        inputLower.contains(example.getUserInput().toLowerCase())) {
                        matched.add(skill);
                        break;
                    }
                }
            }
        }
        
        return matched;
    }
    
    /**
     * 检查是否包含关键词
     */
    private boolean containsKeywords(String input, String text) {
        String[] keywords = text.split("[,，\\s]+");
        for (String keyword : keywords) {
            if (keyword.length() > 1 && input.contains(keyword)) {
                return true;
            }
        }
        return false;
    }
    
    /**
     * 匹配执行结果
     * 对齐 Go 版本的 matchAndExecute 返回值
     */
    public static class MatchResult {
        private final boolean matched;
        private final boolean success;
        private final String response;
        private final String skillName;
        private final String error;
        
        private MatchResult(boolean matched, boolean success, String response, String skillName, String error) {
            this.matched = matched;
            this.success = success;
            this.response = response;
            this.skillName = skillName;
            this.error = error;
        }
        
        public static MatchResult notMatched() {
            return new MatchResult(false, false, null, null, null);
        }
        
        public static MatchResult success(String response, String skillName) {
            return new MatchResult(true, true, response, skillName, null);
        }
        
        public static MatchResult error(String error) {
            return new MatchResult(true, false, null, null, error);
        }
        
        public boolean isMatched() { return matched; }
        public boolean isSuccess() { return success; }
        public String getResponse() { return response; }
        public String getSkillName() { return skillName; }
        public String getError() { return error; }
    }
}