package com.bitguide.skill;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import lombok.extern.slf4j.Slf4j;

import java.io.File;
import java.io.IOException;
import java.nio.file.*;
import java.util.*;

/**
 * 技能加载器
 * 从 SKILL.md 文件加载技能定义（YAML front matter + Markdown 正文）
 *
 * 格式说明：
 * - YAML front matter: 包含 name, description, tools, examples 等元数据
 * - Markdown 正文: 整体作为 systemPrompt（技能的专业执行指南）
 *
 * 对齐 Go 版本的 Loader 设计
 */
@Slf4j
public class SkillLoader {
    
    private static final String SKILL_FILE_NAME = "SKILL.md";
    private static final String FRONT_MATTER_DELIMITER = "---";
    private static final ObjectMapper YAML_MAPPER = new ObjectMapper(new YAMLFactory());
    
    private final Path skillsDirectory;
    
    public SkillLoader() {
        this.skillsDirectory = Paths.get("skills");
    }
    
    public SkillLoader(String skillsDirectory) {
        this.skillsDirectory = Paths.get(skillsDirectory);
    }
    
    /**
     * 加载所有技能
     */
    public List<Skill> loadAll() {
        List<Skill> skills = new ArrayList<>();
        
        if (!Files.exists(skillsDirectory)) {
            log.warn("技能目录不存在: {}", skillsDirectory.toAbsolutePath());
            return skills;
        }
        
        try {
            Files.walk(skillsDirectory, 2)
                    .filter(this::isSkillFile)
                    .forEach(skillFile -> {
                        try {
                            Skill skill = loadSkill(skillFile);
                            if (skill != null) {
                                skills.add(skill);
                                log.info("加载技能: {} - {}", skill.getName(), skill.getDescription());
                            }
                        } catch (Exception e) {
                            log.error("加载技能失败: {} - {}", skillFile, e.getMessage());
                        }
                    });
        } catch (IOException e) {
            log.error("扫描技能目录失败: {}", e.getMessage());
        }
        
        return skills;
    }
    
    /**
     * 加载单个技能
     */
    public Skill loadSkill(Path skillFile) {
        try {
            String content = Files.readString(skillFile);
            return parseSkillFile(content, skillFile);
        } catch (IOException e) {
            log.error("读取技能文件失败: {} - {}", skillFile, e.getMessage());
            return null;
        }
    }
    
    /**
     * 重新加载技能（用于热重载）
     */
    public Skill reload(Skill skill) {
        if (skill == null || skill.getFilePath() == null) {
            return null;
        }
        
        Path skillFile = Paths.get(skill.getFilePath());
        if (!Files.exists(skillFile)) {
            log.warn("技能文件不存在: {}", skillFile);
            return null;
        }
        
        try {
            long lastModified = Files.getLastModifiedTime(skillFile).toMillis();
            if (lastModified <= skill.getLastModified()) {
                log.debug("技能文件未变更: {}", skill.getName());
                return skill;
            }
            
            Skill reloaded = loadSkill(skillFile);
            if (reloaded != null) {
                log.info("技能已重新加载: {}", skill.getName());
            }
            return reloaded;
        } catch (IOException e) {
            log.error("重新加载技能失败: {} - {}", skill.getName(), e.getMessage());
            return null;
        }
    }
    
    /**
     * 检查是否为技能文件
     */
    private boolean isSkillFile(Path path) {
        return path.getFileName().toString().equals(SKILL_FILE_NAME);
    }
    
    /**
     * 解析技能文件
     * 格式：YAML front matter + Markdown 正文（整体作为 SystemPrompt）
     */
    private Skill parseSkillFile(String content, Path skillFile) throws IOException {
        // 从路径提取技能ID（目录名）
        String skillId = skillFile.getParent().getFileName().toString();
        
        // 获取文件信息
        String filePath = skillFile.toAbsolutePath().toString();
        long lastModified = Files.getLastModifiedTime(skillFile).toMillis();
        
        // 解析 YAML front matter
        FrontMatterResult fmResult = parseYAMLFrontMatter(content);
        
        // 如果名称为空，使用 skillId
        if (fmResult.name == null || fmResult.name.isEmpty()) {
            fmResult.name = skillId;
        }
        
        return Skill.builder()
                .id(skillId)
                .name(fmResult.name)
                .description(fmResult.description)
                .systemPrompt(fmResult.body)
                .toolRefs(fmResult.toolRefs != null ? fmResult.toolRefs : new ArrayList<>())
                .examples(fmResult.examples != null ? fmResult.examples : new ArrayList<>())
                .filePath(filePath)
                .lastModified(lastModified)
                .build();
    }
    
    /**
     * 解析 YAML front matter
     * 分隔符 --- 之间的内容为 YAML 元数据，之后的内容整体作为 body（systemPrompt）
     */
    private FrontMatterResult parseYAMLFrontMatter(String content) {
        FrontMatterResult result = new FrontMatterResult();
        content = content.trim();
        
        if (!content.startsWith(FRONT_MATTER_DELIMITER)) {
            // 没有 front matter，整个内容作为 systemPrompt
            result.body = content;
            return result;
        }
        
        // 找到 front matter 的结束位置
        int endIndex = content.indexOf("\n" + FRONT_MATTER_DELIMITER, 4);
        if (endIndex == -1) {
            // 没有结束标记，整个内容作为 systemPrompt
            result.body = content;
            return result;
        }
        
        String yamlContent = content.substring(3, endIndex).trim();
        String body = content.substring(endIndex + 4).trim();
        
        // 解析 YAML
        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> yamlMap = YAML_MAPPER.readValue(yamlContent, Map.class);
            
            result.name = (String) yamlMap.get("name");
            result.description = (String) yamlMap.get("description");
            
            // 解析 tools 列表
            Object toolsObj = yamlMap.get("tools");
            if (toolsObj instanceof List) {
                result.toolRefs = new ArrayList<>();
                for (Object tool : (List<?>) toolsObj) {
                    if (tool != null) {
                        result.toolRefs.add(tool.toString().trim());
                    }
                }
            }
            
            // 解析 examples 列表
            Object examplesObj = yamlMap.get("examples");
            if (examplesObj instanceof List) {
                result.examples = new ArrayList<>();
                for (Object exObj : (List<?>) examplesObj) {
                    if (exObj instanceof Map) {
                        @SuppressWarnings("unchecked")
                        Map<String, String> exMap = (Map<String, String>) exObj;
                        result.examples.add(Skill.Example.builder()
                                .userInput(exMap.get("userInput"))
                                .response(exMap.get("response"))
                                .build());
                    }
                }
            }
        } catch (Exception e) {
            log.warn("解析 YAML front matter 失败: {}", e.getMessage());
        }
        
        result.body = body;
        return result;
    }
    
    /**
     * Front matter 解析结果
     */
    private static class FrontMatterResult {
        String name;
        String description;
        List<String> toolRefs;
        List<Skill.Example> examples;
        String body;
    }
}