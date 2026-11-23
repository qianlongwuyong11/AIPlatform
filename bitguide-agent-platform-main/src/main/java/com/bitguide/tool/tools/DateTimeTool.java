package com.bitguide.tool.tools;

import com.bitguide.tool.Tool;
import com.bitguide.tool.ToolResult;
import lombok.extern.slf4j.Slf4j;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.temporal.TemporalAdjusters;
import java.util.Map;

/**
 * 日期时间工具
 * 获取当前日期和时间，自动输出今天/明天/后天等参考信息
 *
 * 增强：对齐 Go 项目 pkg/tool/tools/datetime.go 的丰富输出
 */
@Slf4j
public class DateTimeTool implements Tool {

    private static final Map<DayOfWeek, String> WEEKDAY_CN = Map.of(
            DayOfWeek.MONDAY, "周一",
            DayOfWeek.TUESDAY, "周二",
            DayOfWeek.WEDNESDAY, "周三",
            DayOfWeek.THURSDAY, "周四",
            DayOfWeek.FRIDAY, "周五",
            DayOfWeek.SATURDAY, "周六",
            DayOfWeek.SUNDAY, "周日"
    );

    @Override
    public String getName() {
        return "datetime";
    }

    @Override
    public String getDescription() {
        return "获取当前日期和时间。当用户提到'今天'、'明天'、'后天'、'这周末'等相对时间时调用此工具，返回详细的日期参考信息。";
    }

    @Override
    public String getParametersSchema() {
        return """
            {
              "type": "object",
              "properties": {
                "timezone": {
                  "type": "string",
                  "description": "时区（可选），例如: Asia/Shanghai, UTC，默认本地时区"
                }
              },
              "required": []
            }
            """;
    }

    @Override
    public ToolResult execute(Map<String, Object> params) {
        try {
            LocalDateTime now = LocalDateTime.now();
            LocalDate today = now.toLocalDate();

            String weekday = WEEKDAY_CN.getOrDefault(now.getDayOfWeek(), "未知");

            StringBuilder result = new StringBuilder();
            result.append(String.format("当前时间: %s (%s) %s\n\n",
                    today.format(DateTimeFormatter.ofPattern("yyyy-MM-dd")),
                    weekday,
                    now.format(DateTimeFormatter.ofPattern("HH:mm:ss"))));

            result.append("日期参考:\n");

            // 输出今天到后3天
            String[] labels = {"今天", "明天", "后天", "大后天"};
            for (int i = 0; i <= 3; i++) {
                LocalDate d = today.plusDays(i);
                String dWeekday = WEEKDAY_CN.getOrDefault(d.getDayOfWeek(), "未知");
                result.append(String.format("- %s: %s %s\n", labels[i],
                        d.format(DateTimeFormatter.ofPattern("yyyy-MM-dd")), dWeekday));
            }

            // 本周末信息
            LocalDate saturday = today.with(TemporalAdjusters.nextOrSame(DayOfWeek.SATURDAY));
            LocalDate sunday = today.with(TemporalAdjusters.nextOrSame(DayOfWeek.SUNDAY));

            // 如果今天是周六或周日，saturday/sunday 就是今天
            if (today.getDayOfWeek() == DayOfWeek.SATURDAY || today.getDayOfWeek() == DayOfWeek.SUNDAY) {
                saturday = today.with(TemporalAdjusters.previousOrSame(DayOfWeek.SATURDAY));
                sunday = today.with(TemporalAdjusters.nextOrSame(DayOfWeek.SUNDAY));
            }

            result.append(String.format("- 本周六: %s\n", saturday.format(DateTimeFormatter.ofPattern("yyyy-MM-dd"))));
            result.append(String.format("- 本周日: %s\n", sunday.format(DateTimeFormatter.ofPattern("yyyy-MM-dd"))));

            // 距周末天数
            if (today.getDayOfWeek() == DayOfWeek.SATURDAY || today.getDayOfWeek() == DayOfWeek.SUNDAY) {
                result.append("- 今天就是周末！\n");
            } else {
                long daysToWeekend = java.time.temporal.ChronoUnit.DAYS.between(today, saturday);
                result.append(String.format("- 距周末还有 %d 天\n", daysToWeekend));
            }

            log.debug("日期时间查询结果: {}", result);
            return ToolResult.success(result.toString());

        } catch (Exception e) {
            log.error("获取日期时间失败: {}", e.getMessage());
            return ToolResult.failure("获取日期时间失败: " + e.getMessage());
        }
    }
}