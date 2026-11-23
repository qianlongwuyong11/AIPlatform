import java.io.*;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.*;

public class LegalMcpServer {

    private static final String SERVER_NAME = "legal-tools";
    private static final String SERVER_VERSION = "1.0.0";
    private static final String PROTOCOL_VERSION = "2024-11-05";

    private static BufferedReader reader;
    private static BufferedWriter writer;

    private static final Map<String, Integer> PERIODS = Map.of(
        "合同纠纷", 3, "劳动争议", 1, "人身损害", 1,
        "知识产权", 3, "房屋租赁", 1
    );

    public static void main(String[] args) {
        reader = new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8));
        writer = new BufferedWriter(new OutputStreamWriter(System.out, StandardCharsets.UTF_8));
        logError("Legal Tools MCP Server 启动");
        try {
            String line;
            while ((line = reader.readLine()) != null) {
                processRequest(line);
            }
        } catch (Exception e) {
            logError("Server 错误: " + e.getMessage());
        }
    }

    private static void processRequest(String json) {
        try {
            Map<String, Object> request = parseJson(json);
            String method = (String) request.get("method");
            Object id = request.get("id");
            @SuppressWarnings("unchecked")
            Map<String, Object> params = (Map<String, Object>) request.get("params");
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

        // statute_of_limitations 工具
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        Map<String, Object> props = new LinkedHashMap<>();
        Map<String, Object> caseTypeProp = new LinkedHashMap<>();
        caseTypeProp.put("type", "string");
        caseTypeProp.put("description", "案件类型：合同纠纷/劳动争议/人身损害/知识产权/房屋租赁");
        props.put("caseType", caseTypeProp);
        Map<String, Object> dateProp = new LinkedHashMap<>();
        dateProp.put("type", "string");
        dateProp.put("description", "事发日期，格式 YYYY-MM-DD，如 2024-06-15");
        props.put("incidentDate", dateProp);
        schema.put("properties", props);
        schema.put("required", List.of("caseType", "incidentDate"));
        tools.add(createToolDefinition("statute_of_limitations",
            "根据案件类型和发生日期计算诉讼时效。支持：合同纠纷(3年)、劳动争议(1年)、人身损害(1年)、知识产权(3年)、房屋租赁(1年)",
            schema));

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
        String output;
        boolean isError = false;
        try {
            switch (toolName) {
                case "statute_of_limitations": output = doStatuteOfLimitations(arguments); break;
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

    // ==================== statute_of_limitations 实现 ====================

    private static String doStatuteOfLimitations(Map<String, Object> args) {
        String caseType = (String) args.get("caseType");
        String dateStr = (String) args.get("incidentDate");

        Integer years = PERIODS.get(caseType);
        if (years == null) return "不支持的案件类型: " + caseType;

        LocalDate incidentDate = LocalDate.parse(dateStr);
        LocalDate deadline = incidentDate.plusYears(years);
        LocalDate today = LocalDate.now();

        long daysRemaining = ChronoUnit.DAYS.between(today, deadline);
        String status;
        if (daysRemaining < 0) {
            status = "已超过诉讼时效";
        } else if (daysRemaining < 90) {
            status = "⚠ 即将超过诉讼时效（剩余 " + daysRemaining + " 天）";
        } else {
            status = "仍在时效内（剩余 " + daysRemaining + " 天）";
        }

        return String.format("案件类型: %s\n诉讼时效: %d 年\n事发日期: %s\n截止日期: %s\n当前状态: %s",
            caseType, years, dateStr, deadline, status);
    }

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

    private static void logError(String m) { System.err.println("[Legal-MCP-Server] " + m); }
}
