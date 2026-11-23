package com.bitguide.rag;

import com.bitguide.config.RagConfig;

import java.util.ArrayList;
import java.util.List;

/**
 * 文档分割器 - 将长文本分割成合适大小的片段
 */
public class DocumentSplitter {
    private final int maxSegmentSize;
    private final int overlap;

    public DocumentSplitter(RagConfig.SplitterConfig config) {
        this.maxSegmentSize = config.getMaxSegmentSize();
        this.overlap = config.getOverlap();
    }

    /**
     * 分割文本
     */
    public List<String> split(String text) {
        List<String> segments = new ArrayList<>();
        
        if (text == null || text.isEmpty()) {
            return segments;
        }

        // 首先按段落分割
        String[] paragraphs = text.split("\n\n+");
        
        StringBuilder currentSegment = new StringBuilder();
        
        for (String paragraph : paragraphs) {
            // 如果当前段落本身超过最大大小，需要进一步分割
            if (paragraph.length() > maxSegmentSize) {
                // 先保存当前累积的内容
                if (currentSegment.length() > 0) {
                    segments.add(currentSegment.toString().trim());
                    currentSegment = new StringBuilder();
                }
                
                // 按句子分割长段落
                List<String> sentenceSegments = splitLongParagraph(paragraph);
                segments.addAll(sentenceSegments);
            } else {
                // 检查添加此段落后是否超过最大大小
                if (currentSegment.length() + paragraph.length() + 2 > maxSegmentSize) {
                    if (currentSegment.length() > 0) {
                        segments.add(currentSegment.toString().trim());
                    }
                    // 处理重叠
                    currentSegment = new StringBuilder();
                    if (overlap > 0 && !segments.isEmpty()) {
                        String lastSegment = segments.get(segments.size() - 1);
                        int overlapStart = Math.max(0, lastSegment.length() - overlap);
                        currentSegment.append(lastSegment.substring(overlapStart)).append("\n\n");
                    }
                }
                currentSegment.append(paragraph).append("\n\n");
            }
        }
        
        // 添加最后一个段落
        if (currentSegment.length() > 0) {
            segments.add(currentSegment.toString().trim());
        }
        
        return segments;
    }

    /**
     * 分割长段落
     */
    private List<String> splitLongParagraph(String paragraph) {
        List<String> segments = new ArrayList<>();
        
        // 按句子分割（支持中英文）
        String[] sentences = paragraph.split("(?<=[。！？.!?])\\s*");
        
        StringBuilder currentSegment = new StringBuilder();
        
        for (String sentence : sentences) {
            if (currentSegment.length() + sentence.length() + 1 > maxSegmentSize) {
                if (currentSegment.length() > 0) {
                    segments.add(currentSegment.toString().trim());
                    currentSegment = new StringBuilder();
                    
                    // 处理重叠
                    if (overlap > 0 && !segments.isEmpty()) {
                        String lastSegment = segments.get(segments.size() - 1);
                        int overlapStart = Math.max(0, lastSegment.length() - overlap);
                        currentSegment.append(lastSegment.substring(overlapStart)).append(" ");
                    }
                }
                
                // 如果单个句子超过最大大小，强制分割
                if (sentence.length() > maxSegmentSize) {
                    for (int i = 0; i < sentence.length(); i += maxSegmentSize - overlap) {
                        int end = Math.min(i + maxSegmentSize, sentence.length());
                        segments.add(sentence.substring(i, end));
                    }
                } else {
                    currentSegment.append(sentence);
                }
            } else {
                currentSegment.append(sentence);
            }
        }
        
        if (currentSegment.length() > 0) {
            segments.add(currentSegment.toString().trim());
        }
        
        return segments;
    }
}