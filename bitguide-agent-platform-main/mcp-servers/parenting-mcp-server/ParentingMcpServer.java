/**
 * Parenting Tools MCP Server - 亲子教育工具集
 * 
 * 独立 MCP Server，提供亲子教育场景相关工具：
 * - age_calculator: 根据出生年月计算精确年龄及适龄活动建议
 * - get_weather: 查询天气（辅助户外活动决策）
 * - get_forecast: 查询天气预报
 * 
 * 运行方式：java mcp-servers/parenting-mcp-server/ParentingMcpServer.java
 */
import java.io.*;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.Period;
import java.time.temporal.ChronoUnit;
import java.util.*;

public class ParentingMcpServer {
    
    private static final String SERVER_NAME = "parenting-tools";
    private static final String SERVER_VERSION = "1.0.0";
    private static final String PROTOCOL_VERSION = "2024-11-05";
    
    private static final HttpClient httpClient = HttpClient.newBuilder()
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();
    private static BufferedReader reader;
    private static BufferedWriter writer;
    
    public static void main(String[] args) {
        reader = new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8));
        writer = new BufferedWriter(new OutputStreamWriter(System.out, StandardCharsets.UTF_8));
        logError("Parenting Tools MCP Server 启动");
        try {
            String line;
            while ((line = reader.readLine()) != null) {
                processRequest(line);
            }
        } catch (Exception e) {
            logError("Server 错误: " + e.getMessage());
        }
        logError("Parenting Tools MCP Server 退出");
    }
    
    private static void processRequest(String json) {
        try {
            Map<String, Object> request = parseJson(json);
            String method = (String) request.get("method");
            Object id = request.get("id");
            @SuppressWarnings("unchecked")
            Map<String, Object> params = (Map<String, Object>) request.get("params");
            logError("收到请求: method=" + method);
            Object result = null;
            Map<String, Object> error = null;
            try {
                switch (method) {
                    case "initialize": result = handleInitialize(); break;
                    case "notifications/initialized": return;
                    case "tools/list": result = handleToolsList(); break;
                    case "tools/call": result = handleToolsCall(params); break;
                    default: error = createError(-32601, "方法不存在: " + method);
                }
            } catch (Exception e) {
                error = createError(-32000, "执行错误: " + e.getMessage());
                logError("执行错误: " + e.getMessage());
            }
            if (id != null) sendResponse(id, result, error);
        } catch (Exception e) {
            logError("解析请求失败: " + e.getMessage());
        }
    }
    
    private static Map<String, Object> handleInitialize() {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("protocolVersion", PROTOCOL_VERSION);
        Map<String, Object> serverInfo = new LinkedHashMap<>();
        serverInfo.put("name", SERVER_NAME);
        serverInfo.put("version", SERVER_VERSION);
        result.put("serverInfo", serverInfo);
        Map<String, Object> capabilities = new LinkedHashMap<>();
        capabilities.put("tools", Collections.singletonMap("listChanged", false));
        result.put("capabilities", capabilities);
        return result;
    }
    
    private static Map<String, Object> handleToolsList() {
        Map<String, Object> result = new LinkedHashMap<>();
        List<Map<String, Object>> tools = new ArrayList<>();
        
        // age_calculator 工具
        Map<String, Object> ageSchema = new LinkedHashMap<>();
        ageSchema.put("type", "object");
        Map<String, Object> ageProps = new LinkedHashMap<>();
        Map<String, Object> birthYearProp = new LinkedHashMap<>();
        birthYearProp.put("type", "integer");
        birthYearProp.put("description", "出生年份，如 2021");
        ageProps.put("birth_year", birthYearProp);
        Map<String, Object> birthMonthProp = new LinkedHashMap<>();
        birthMonthProp.put("type", "integer");
        birthMonthProp.put("description", "出生月份（1-12），如果不清楚具体月份可以不填");
        ageProps.put("birth_month", birthMonthProp);
        ageSchema.put("properties", ageProps);
        ageSchema.put("required", List.of("birth_year"));
        tools.add(createToolDefinition("age_calculator", "根据出生年月计算精确年龄。适用于计算孩子的年龄，输入出生年份和月份，返回X岁X月的精确年龄及适龄活动建议。", ageSchema));
        
        // get_weather 工具
        Map<String, Object> weatherSchema = new LinkedHashMap<>();
        weatherSchema.put("type", "object");
        Map<String, Object> weatherProps = new LinkedHashMap<>();
        Map<String, Object> cityProp = new LinkedHashMap<>();
        cityProp.put("type", "string");
        cityProp.put("description", "城市名称，支持中文和英文，如：北京、Shanghai");
        weatherProps.put("city", cityProp);
        weatherSchema.put("properties", weatherProps);
        weatherSchema.put("required", List.of("city"));
        tools.add(createToolDefinition("get_weather", "查询指定城市的当前天气情况，包括温度、湿度、风速等。可用于判断是否适合户外亲子活动。", weatherSchema));
        
        // get_forecast 工具
        Map<String, Object> forecastSchema = new LinkedHashMap<>();
        forecastSchema.put("type", "object");
        Map<String, Object> forecastProps = new LinkedHashMap<>();
        Map<String, Object> fcityProp = new LinkedHashMap<>();
        fcityProp.put("type", "string");
        fcityProp.put("description", "城市名称，支持中文和英文");
        forecastProps.put("city", fcityProp);
        forecastSchema.put("properties", forecastProps);
        forecastSchema.put("required", List.of("city"));
        tools.add(createToolDefinition("get_forecast", "查询指定城市未来3天的天气预报，可用于规划亲子户外活动。", forecastSchema));
        
        result.put("tools", tools);
        return result;
    }
    
    private static Map<String, Object> createToolDefinition(String name, String description, Map<String, Object> inputSchema) {
        Map<String, Object> tool = new LinkedHashMap<>();
        tool.put("name", name);
        tool.put("description", description);
        tool.put("inputSchema", inputSchema);
        return tool;
    }
    
    @SuppressWarnings("unchecked")
    private static Map<String, Object> handleToolsCall(Map<String, Object> params) throws Exception {
        String toolName = (String) params.get("name");
        Map<String, Object> arguments = (Map<String, Object>) params.get("arguments");
        if (arguments == null) arguments = Collections.emptyMap();
        logError("调用工具: " + toolName + ", 参数: " + arguments);
        String output;
        boolean isError = false;
        try {
            switch (toolName) {
                case "age_calculator": output = doAgeCalculator(arguments); break;
                case "get_weather": output = doGetWeather(arguments); break;
                case "get_forecast": output = doGetForecast(arguments); break;
                default: output = "未知工具: " + toolName; isError = true;
            }
        } catch (Exception e) {
            output = "工具执行错误: " + e.getMessage();
            isError = true;
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("content", List.of(Map.of("type", "text", "text", output)));
        result.put("isError", isError);
        return result;
    }
    
    // ==================== age_calculator 实现 ====================
    
    private static String doAgeCalculator(Map<String, Object> args) {
        int birthYear = parseInt(args.get("birth_year"));
        if (birthYear == 0) return "错误：birth_year 参数必须是数字";
        int currentYear = LocalDate.now().getYear();
        if (birthYear < 1900 || birthYear > currentYear) return "出生年份不合理: " + birthYear;
        
        boolean monthProvided = args.containsKey("birth_month") && args.get("birth_month") != null;
        int birthMonth = 1;
        if (monthProvided) {
            birthMonth = parseInt(args.get("birth_month"));
            if (birthMonth < 1 || birthMonth > 12) birthMonth = 1;
        }
        
        LocalDate birthDate = LocalDate.of(birthYear, birthMonth, 1);
        LocalDate now = LocalDate.now();
        Period period = Period.between(birthDate, now);
        int years = period.getYears();
        int months = period.getMonths();
        int totalMonths = years * 12 + months;
        long totalDays = ChronoUnit.DAYS.between(birthDate, now);
        LocalDate birthdayThisYear = LocalDate.of(now.getYear(), birthMonth, 1);
        boolean hadBirthday = !now.isBefore(birthdayThisYear);
        
        StringBuilder result = new StringBuilder();
        if (!monthProvided) {
            result.append(String.format("出生年份: %d年\n", birthYear));
            result.append(String.format("估算年龄: 约%d岁（按%d年初计算）\n", years, birthYear));
            result.append("精确年龄需提供出生月份");
        } else {
            result.append(String.format("出生日期: %d年%d月\n", birthYear, birthMonth));
            result.append(String.format("当前年龄: %d岁%d个月（共%d个月）\n", years, months, totalMonths));
            result.append(String.format("今年生日: %s\n", hadBirthday ? "已过" : "未过"));
            result.append(String.format("出生到今天共 %d 天", totalDays));
        }
        
        result.append("\n\n适龄参考:");
        if (totalMonths <= 12) result.append("\n- 婴儿期（0-1岁）：适合感官刺激、亲子互动");
        else if (totalMonths <= 36) result.append("\n- 幼儿期（1-3岁）：适合简单游戏、户外探索、动物园");
        else if (totalMonths <= 72) result.append("\n- 学龄前期（3-6岁）：适合主题乐园、手工活动、博物馆儿童区");
        else if (totalMonths <= 144) result.append("\n- 学龄期（6-12岁）：适合科普教育、运动体验、文化旅行");
        else result.append("\n- 青少年期（12岁+）：适合深度文化体验、户外探险");
        
        return result.toString();
    }
    
    private static int parseInt(Object value) {
        if (value == null) return 0;
        if (value instanceof Number) return ((Number) value).intValue();
        if (value instanceof String) { try { return Integer.parseInt((String) value); } catch (Exception e) { return 0; } }
        return 0;
    }
    
    // ==================== 天气工具 ====================
    
    private static String doGetWeather(Map<String, Object> args) throws Exception {
        String city = (String) args.get("city");
        if (city == null || city.trim().isEmpty()) return "错误：请提供城市名称";
        String url = "https://wttr.in/" + URLEncoder.encode(city.trim(), "UTF-8") + "?format=j1";
        try {
            HttpRequest req = HttpRequest.newBuilder().uri(URI.create(url))
                .header("Accept", "application/json").header("User-Agent", "curl")
                .timeout(java.time.Duration.ofSeconds(15)).GET().build();
            HttpResponse<String> resp = httpClient.send(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (resp.statusCode() != 200) return "查询失败: HTTP " + resp.statusCode();
            return parseWeather(resp.body(), city);
        } catch (java.net.http.HttpTimeoutException e) { return "请求超时"; }
        catch (java.net.UnknownHostException e) { return "网络不可用"; }
        catch (Exception e) { return "查询失败: " + e.getMessage(); }
    }
    
    private static String doGetForecast(Map<String, Object> args) throws Exception {
        String city = (String) args.get("city");
        if (city == null || city.trim().isEmpty()) return "错误：请提供城市名称";
        String url = "https://wttr.in/" + URLEncoder.encode(city.trim(), "UTF-8") + "?format=j1";
        try {
            HttpRequest req = HttpRequest.newBuilder().uri(URI.create(url))
                .header("Accept", "application/json").header("User-Agent", "curl")
                .timeout(java.time.Duration.ofSeconds(15)).GET().build();
            HttpResponse<String> resp = httpClient.send(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (resp.statusCode() != 200) return "查询失败: HTTP " + resp.statusCode();
            return parseForecast(resp.body(), city);
        } catch (Exception e) { return "查询失败: " + e.getMessage(); }
    }
    
    @SuppressWarnings("unchecked")
    private static String parseWeather(String json, String city) {
        try {
            Map<String, Object> data = parseJson(json);
            List<?> cl = (List<?>) data.get("current_condition");
            if (cl == null || cl.isEmpty()) return "无法获取天气数据";
            Map<String, Object> c = (Map<String, Object>) cl.get(0);
            String desc = "未知";
            Object d = c.get("weatherDesc");
            if (d instanceof List) { List<?> dl = (List<?>) d; if (!dl.isEmpty() && dl.get(0) instanceof Map) desc = getString((Map<String, Object>) dl.get(0), "value", desc); }
            return "【" + city + " 天气】\n天气: " + desc + "\n温度: " + getString(c, "temp_C", "N/A") + "°C\n体感: " + getString(c, "FeelsLikeC", "N/A") + "°C\n湿度: " + getString(c, "humidity", "N/A") + "%\n风速: " + getString(c, "windspeedKmph", "N/A") + " km/h\n能见度: " + getString(c, "visibility", "N/A") + " km";
        } catch (Exception e) { return "解析天气失败"; }
    }
    
    @SuppressWarnings("unchecked")
    private static String parseForecast(String json, String city) {
        try {
            Map<String, Object> data = parseJson(json);
            List<Map<String, Object>> wl = (List<Map<String, Object>>) data.get("weather");
            if (wl == null || wl.isEmpty()) return "无法获取预报";
            StringBuilder sb = new StringBuilder("【" + city + " 未来3天预报】\n\n");
            for (int i = 0; i < Math.min(3, wl.size()); i++) {
                Map<String, Object> day = wl.get(i);
                List<Map<String, Object>> hourly = (List<Map<String, Object>>) day.get("hourly");
                String desc = "未知", rain = "0";
                if (hourly != null && !hourly.isEmpty()) {
                    Map<String, Object> noon = hourly.get(hourly.size() / 2);
                    List<Map<String, Object>> dl = (List<Map<String, Object>>) noon.get("weatherDesc");
                    if (dl != null && !dl.isEmpty()) desc = getString(dl.get(0), "value", desc);
                    rain = getString(noon, "chanceofrain", "0");
                }
                sb.append("📅 ").append(getString(day, "date", "")).append("\n   天气: ").append(desc)
                  .append("\n   温度: ").append(getString(day, "mintempC", "N/A")).append("°C ~ ").append(getString(day, "maxtempC", "N/A")).append("°C")
                  .append("\n   降雨概率: ").append(rain).append("%\n\n");
            }
            return sb.toString().trim();
        } catch (Exception e) { return "解析预报失败"; }
    }
    
    private static String getString(Map<String, Object> m, String k, String d) { Object v = m.get(k); return v != null ? v.toString() : d; }
    
    // ==================== JSON-RPC ====================
    
    private static void sendResponse(Object id, Object result, Map<String, Object> error) {
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("jsonrpc", "2.0"); r.put("id", id);
        r.put(error != null ? "error" : "result", error != null ? error : result);
        try { writer.write(toJson(r)); writer.newLine(); writer.flush(); } catch (Exception e) { logError("发送失败: " + e.getMessage()); }
    }
    private static Map<String, Object> createError(int code, String msg) { Map<String, Object> e = new LinkedHashMap<>(); e.put("code", code); e.put("message", msg); return e; }
    
    // ==================== JSON 解析器 ====================
    private static Map<String, Object> parseJson(String json) { return new SJP(json).parseObject(); }
    private static String toJson(Object o) {
        if (o == null) return "null";
        if (o instanceof String) return "\"" + esc((String)o) + "\"";
        if (o instanceof Boolean) return o.toString();
        if (o instanceof Number) { Number n = (Number)o; if (n instanceof Double || n instanceof Float) { double d = n.doubleValue(); if (d == Math.floor(d) && !Double.isInfinite(d)) return String.valueOf((long)d); return n.toString(); } return String.valueOf(n.longValue()); }
        if (o instanceof Map) { StringBuilder s = new StringBuilder("{"); boolean f = true; for (Map.Entry<?,?> e : ((Map<?,?>)o).entrySet()) { if (!f) s.append(","); s.append("\"").append(e.getKey()).append("\":").append(toJson(e.getValue())); f = false; } return s.append("}").toString(); }
        if (o instanceof List) { StringBuilder s = new StringBuilder("["); boolean f = true; for (Object i : (List<?>)o) { if (!f) s.append(","); s.append(toJson(i)); f = false; } return s.append("]").toString(); }
        return "\"" + esc(o.toString()) + "\"";
    }
    private static String esc(String s) { return s.replace("\\","\\\\").replace("\"","\\\"").replace("\n","\\n").replace("\r","\\r").replace("\t","\\t"); }
    static class SJP {
        private final String j; private int p = 0;
        SJP(String j) { this.j = j.trim(); }
        Map<String,Object> parseObject() { Map<String,Object> m = new LinkedHashMap<>(); skip(); if (p>=j.length()||j.charAt(p)!='{') return m; p++; while(p<j.length()){skip();if(p>=j.length())break;if(j.charAt(p)=='}'){p++;break;}if(j.charAt(p)==','){p++;continue;}String k=parseString();skip();if(p<j.length()&&j.charAt(p)==':')p++;skip();m.put(k,parseValue());} return m; }
        Object parseValue() { skip(); if(p>=j.length())return null; char c=j.charAt(p); if(c=='"')return parseString(); if(c=='{')return parseObject(); if(c=='[')return parseArray(); if(c=='t'||c=='f')return parseBoolean(); if(c=='n'){p+=4;return null;} if(c=='-'||Character.isDigit(c))return parseNumber(); return null; }
        String parseString() { skip(); if(p>=j.length()||j.charAt(p)!='"')return ""; p++; StringBuilder s=new StringBuilder(); while(p<j.length()){char c=j.charAt(p++);if(c=='"')break;if(c=='\\'&&p<j.length()){char n=j.charAt(p++);if(n=='n')s.append('\n');else if(n=='t')s.append('\t');else if(n=='r')s.append('\r');else if(n=='u'&&p+3<j.length()){try{s.append((char)Integer.parseInt(j.substring(p,p+4),16));}catch(Exception e){}p+=4;}else s.append(n);}else s.append(c);} return s.toString(); }
        List<Object> parseArray() { List<Object> l=new ArrayList<>(); p++; while(p<j.length()){skip();if(p>=j.length())break;if(j.charAt(p)==']'){p++;break;}if(j.charAt(p)==','){p++;continue;}l.add(parseValue());} return l; }
        Number parseNumber() { int s=p; if(j.charAt(p)=='-')p++; while(p<j.length()&&"0123456789.eE+-".indexOf(j.charAt(p))>=0)p++; String ns=j.substring(s,p); try{return ns.contains(".")||ns.contains("e")||ns.contains("E")?Double.parseDouble(ns):Integer.parseInt(ns);}catch(Exception e){return 0;} }
        Boolean parseBoolean() { if(j.substring(p).startsWith("true")){p+=4;return true;} if(j.substring(p).startsWith("false")){p+=5;return false;} return false; }
        void skip() { while(p<j.length()&&Character.isWhitespace(j.charAt(p)))p++; }
    }
    
    private static void logError(String m) { System.err.println("[Parenting-MCP-Server] " + m); }
}