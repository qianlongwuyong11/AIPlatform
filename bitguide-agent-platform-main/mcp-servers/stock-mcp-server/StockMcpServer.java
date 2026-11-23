/**
 * Stock MCP Server - 股票行情分析工具
 * 
 * 独立 MCP Server，提供股票行情查询、市场概览、板块分析功能。
 * 从平台内置工具迁移而来，实现业务工具与平台代码解耦。
 * 
 * 运行方式：java mcp-servers/stock-mcp-server/StockMcpServer.java
 */
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

public class StockMcpServer {
    
    private static final String SERVER_NAME = "stock-mcp-server";
    private static final String SERVER_VERSION = "1.0.0";
    private static final String PROTOCOL_VERSION = "2024-11-05";
    
    private static final Random random = new Random();
    
    // 模拟股票数据库
    private static final Map<String, StockInfo> STOCK_DB = new LinkedHashMap<>();
    static {
        STOCK_DB.put("600519", new StockInfo("600519", "贵州茅台", 1688.00, "白酒"));
        STOCK_DB.put("000858", new StockInfo("000858", "五粮液", 156.50, "白酒"));
        STOCK_DB.put("300750", new StockInfo("300750", "宁德时代", 198.80, "新能源"));
        STOCK_DB.put("002594", new StockInfo("002594", "比亚迪", 256.30, "新能源汽车"));
        STOCK_DB.put("601012", new StockInfo("601012", "隆基绿能", 32.15, "光伏"));
        STOCK_DB.put("000001", new StockInfo("000001", "平安银行", 11.85, "银行"));
        STOCK_DB.put("600036", new StockInfo("600036", "招商银行", 35.60, "银行"));
        STOCK_DB.put("002415", new StockInfo("002415", "海康威视", 32.80, "安防"));
        STOCK_DB.put("300059", new StockInfo("300059", "东方财富", 18.50, "金融科技"));
        STOCK_DB.put("688981", new StockInfo("688981", "中芯国际", 52.30, "半导体"));
        STOCK_DB.put("002371", new StockInfo("002371", "北方华创", 285.60, "半导体"));
        STOCK_DB.put("300014", new StockInfo("300014", "亿纬锂能", 48.90, "新能源"));
        STOCK_DB.put("603259", new StockInfo("603259", "药明康德", 72.50, "医药"));
        STOCK_DB.put("300760", new StockInfo("300760", "迈瑞医疗", 298.00, "医疗器械"));
        STOCK_DB.put("002230", new StockInfo("002230", "科大讯飞", 48.50, "人工智能"));
    }
    
    // 板块数据
    private static final String[][] SECTORS = {
        {"人工智能", "AI大模型、算力、应用"},
        {"新能源汽车", "整车、电池、充电桩"},
        {"光伏", "硅料、组件、逆变器"},
        {"半导体", "芯片设计、制造、封测"},
        {"医药", "创新药、仿制药、CXO"},
        {"白酒", "高端白酒、次高端"},
        {"银行", "国有大行、股份制银行"},
        {"证券", "券商、投行"},
        {"房地产", "开发、物业"},
        {"军工", "航空、航天、兵器"},
        {"消费电子", "手机、可穿戴设备"},
        {"机器人", "工业机器人、服务机器人"},
        {"数字经济", "信创、数据要素"},
        {"医疗美容", "医美服务、器械"},
        {"有色金属", "锂、铜、铝"}
    };
    
    private static final String[] LEADING_STOCKS = {
        "科大讯飞", "宁德时代", "隆基绿能", "中芯国际", "药明康德",
        "贵州茅台", "招商银行", "中信证券", "保利发展", "中航沈飞",
        "立讯精密", "汇川技术", "中国软件", "爱美客", "天齐锂业"
    };
    
    private static BufferedReader reader;
    private static BufferedWriter writer;
    
    public static void main(String[] args) {
        reader = new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8));
        writer = new BufferedWriter(new OutputStreamWriter(System.out, StandardCharsets.UTF_8));
        logError("Stock MCP Server 启动 (股票行情分析)");
        try {
            String line;
            while ((line = reader.readLine()) != null) {
                processRequest(line);
            }
        } catch (Exception e) {
            logError("Server 错误: " + e.getMessage());
        }
        logError("Stock MCP Server 退出");
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
        
        // stock_quote 工具
        Map<String, Object> quoteSchema = new LinkedHashMap<>();
        quoteSchema.put("type", "object");
        Map<String, Object> quoteProps = new LinkedHashMap<>();
        Map<String, Object> symbolProp = new LinkedHashMap<>();
        symbolProp.put("type", "string");
        symbolProp.put("description", "股票代码，如 '600519'（贵州茅台）、'000858'（五粮液）");
        quoteProps.put("symbol", symbolProp);
        quoteSchema.put("properties", quoteProps);
        quoteSchema.put("required", List.of("symbol"));
        tools.add(createToolDefinition("stock_quote", "查询股票实时行情。输入股票代码，返回股票名称、当前价格、涨跌幅等信息。", quoteSchema));
        
        // market_overview 工具
        Map<String, Object> overviewSchema = new LinkedHashMap<>();
        overviewSchema.put("type", "object");
        Map<String, Object> overviewProps = new LinkedHashMap<>();
        Map<String, Object> marketProp = new LinkedHashMap<>();
        marketProp.put("type", "string");
        marketProp.put("description", "市场类型：'sh'（上海）、'sz'（深圳）、'all'（全部，默认）");
        overviewProps.put("market", marketProp);
        overviewSchema.put("properties", overviewProps);
        tools.add(createToolDefinition("market_overview", "获取A股市场整体概览。返回主要指数行情、成交额、涨跌家数等数据。", overviewSchema));
        
        // sector_analysis 工具
        Map<String, Object> sectorSchema = new LinkedHashMap<>();
        sectorSchema.put("type", "object");
        Map<String, Object> sectorProps = new LinkedHashMap<>();
        Map<String, Object> topNProp = new LinkedHashMap<>();
        topNProp.put("type", "integer");
        topNProp.put("description", "返回涨幅前N名的板块，默认5");
        sectorProps.put("topN", topNProp);
        Map<String, Object> sortByProp = new LinkedHashMap<>();
        sortByProp.put("type", "string");
        sortByProp.put("description", "排序方式：'change'（涨跌幅）、'volume'（成交额）、'fund'（资金流入）");
        sectorProps.put("sortBy", sortByProp);
        sectorSchema.put("properties", sectorProps);
        tools.add(createToolDefinition("sector_analysis", "分析A股行业板块涨跌情况。返回各板块涨跌幅、领涨股、资金流向等信息。", sectorSchema));
        
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
    private static Map<String, Object> handleToolsCall(Map<String, Object> params) {
        String toolName = (String) params.get("name");
        Map<String, Object> arguments = (Map<String, Object>) params.get("arguments");
        if (arguments == null) arguments = Collections.emptyMap();
        logError("调用工具: " + toolName + ", 参数: " + arguments);
        String output;
        boolean isError = false;
        try {
            switch (toolName) {
                case "stock_quote": output = doStockQuote(arguments); break;
                case "market_overview": output = doMarketOverview(arguments); break;
                case "sector_analysis": output = doSectorAnalysis(arguments); break;
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
    
    // ==================== 工具实现 ====================
    
    private static String doStockQuote(Map<String, Object> args) {
        String symbol = (String) args.get("symbol");
        if (symbol == null || symbol.trim().isEmpty()) return "错误：股票代码不能为空";
        symbol = symbol.replaceAll("^(SH|SZ|sh|sz)", "");
        
        StockInfo info = STOCK_DB.get(symbol);
        if (info == null) return "未找到股票代码: " + symbol + "。可用代码: " + String.join(", ", STOCK_DB.keySet());
        
        double changePercent = (random.nextDouble() - 0.5) * 6;
        double currentPrice = info.basePrice * (1 + changePercent / 100);
        double changeAmount = currentPrice - info.basePrice;
        long volume = 100000 + random.nextInt(900000);
        
        return String.format(
            "股票代码: %s\n股票名称: %s\n所属板块: %s\n当前价格: %.2f 元\n涨跌幅: %+.2f%%\n涨跌额: %+.2f 元\n成交量: %d 手\n今开: %.2f 元\n最高: %.2f 元\n最低: %.2f 元",
            info.symbol, info.name, info.sector, currentPrice, changePercent, changeAmount,
            volume,
            currentPrice * (1 + (random.nextDouble() - 0.5) * 0.02),
            currentPrice * (1 + random.nextDouble() * 0.02),
            currentPrice * (1 - random.nextDouble() * 0.02)
        );
    }
    
    private static String doMarketOverview(Map<String, Object> args) {
        String market = (String) args.getOrDefault("market", "all");
        StringBuilder sb = new StringBuilder();
        sb.append("═══════════════════════════════════\n");
        sb.append("           A股市场概览\n");
        sb.append("═══════════════════════════════════\n\n");
        
        if ("all".equals(market) || "sh".equals(market)) {
            double shIndex = 3250 + (random.nextDouble() - 0.5) * 100;
            double star50 = 980 + (random.nextDouble() - 0.5) * 30;
            sb.append("【上海证券交易所】\n");
            sb.append(String.format("上证指数: %.2f  %+.2f%%\n科创50:   %.2f  %+.2f%%\n成交额:   %.0f 亿元\n\n",
                shIndex, (random.nextDouble() - 0.5) * 2, star50, (random.nextDouble() - 0.5) * 3, 3500 + random.nextDouble() * 1000));
        }
        if ("all".equals(market) || "sz".equals(market)) {
            double szIndex = 10600 + (random.nextDouble() - 0.5) * 200;
            double gemIndex = 2150 + (random.nextDouble() - 0.5) * 50;
            sb.append("【深圳证券交易所】\n");
            sb.append(String.format("深证成指: %.2f  %+.2f%%\n创业板指: %.2f  %+.2f%%\n成交额:   %.0f 亿元\n\n",
                szIndex, (random.nextDouble() - 0.5) * 2.5, gemIndex, (random.nextDouble() - 0.5) * 3, 4500 + random.nextDouble() * 1500));
        }
        if ("all".equals(market)) {
            int up = 2000 + random.nextInt(1000), down = 2000 + random.nextInt(1000);
            sb.append("【市场统计】\n");
            sb.append(String.format("上涨家数: %d  下跌家数: %d\n涨停: %d  跌停: %d\n北向资金: %+.2f 亿元\n",
                up, down, 30 + random.nextInt(50), 10 + random.nextInt(30), (random.nextDouble() - 0.3) * 100));
        }
        sb.append("═══════════════════════════════════");
        return sb.toString();
    }
    
    private static String doSectorAnalysis(Map<String, Object> args) {
        int topN = args.containsKey("topN") ? ((Number) args.get("topN")).intValue() : 5;
        
        // 生成板块数据并按涨跌幅排序
        List<double[]> dataList = new ArrayList<>();
        for (int i = 0; i < SECTORS.length; i++) {
            dataList.add(new double[]{i, (random.nextDouble() - 0.4) * 8, 100 + random.nextDouble() * 900, (random.nextDouble() - 0.3) * 50});
        }
        dataList.sort((a, b) -> Double.compare(b[1], a[1]));
        
        StringBuilder sb = new StringBuilder();
        sb.append("═══════════════════════════════════\n");
        sb.append("     行业板块涨跌分析 (Top ").append(topN).append(")\n");
        sb.append("═══════════════════════════════════\n\n");
        
        for (int i = 0; i < Math.min(topN, dataList.size()); i++) {
            int idx = (int) dataList.get(i)[0];
            double change = dataList.get(i)[1];
            double turnover = dataList.get(i)[2];
            double fund = dataList.get(i)[3];
            sb.append(String.format("【%d】%s\n    涨跌幅: %+.2f%%  成交额: %.0f亿  资金: %+.2f亿\n    领涨股: %s  板块: %s\n\n",
                i + 1, SECTORS[idx][0], change, turnover, fund, LEADING_STOCKS[idx], SECTORS[idx][1]));
        }
        sb.append("═══════════════════════════════════");
        return sb.toString();
    }
    
    // ==================== 数据类 ====================
    
    private static class StockInfo {
        String symbol, name, sector;
        double basePrice;
        StockInfo(String symbol, String name, double basePrice, String sector) {
            this.symbol = symbol; this.name = name; this.basePrice = basePrice; this.sector = sector;
        }
    }
    
    // ==================== JSON-RPC 通信 ====================
    
    private static void sendResponse(Object id, Object result, Map<String, Object> error) {
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("jsonrpc", "2.0");
        response.put("id", id);
        response.put(error != null ? "error" : "result", error != null ? error : result);
        try {
            writer.write(toJson(response));
            writer.newLine();
            writer.flush();
        } catch (Exception e) {
            logError("发送响应失败: " + e.getMessage());
        }
    }
    
    private static Map<String, Object> createError(int code, String message) {
        Map<String, Object> error = new LinkedHashMap<>();
        error.put("code", code);
        error.put("message", message);
        return error;
    }
    
    // ==================== 简易 JSON 解析器 ====================
    
    private static Map<String, Object> parseJson(String json) { return new SimpleJsonParser(json).parseObject(); }
    
    private static String toJson(Object obj) {
        if (obj == null) return "null";
        if (obj instanceof String) return "\"" + escapeString((String) obj) + "\"";
        if (obj instanceof Boolean) return obj.toString();
        if (obj instanceof Number) {
            Number num = (Number) obj;
            if (num instanceof Double || num instanceof Float) {
                double d = num.doubleValue();
                if (d == Math.floor(d) && !Double.isInfinite(d)) return String.valueOf((long) d);
                return num.toString();
            }
            return String.valueOf(num.longValue());
        }
        if (obj instanceof Map) {
            StringBuilder sb = new StringBuilder("{");
            boolean first = true;
            for (Map.Entry<?, ?> e : ((Map<?, ?>) obj).entrySet()) {
                if (!first) sb.append(",");
                sb.append("\"").append(e.getKey()).append("\":").append(toJson(e.getValue()));
                first = false;
            }
            return sb.append("}").toString();
        }
        if (obj instanceof List) {
            StringBuilder sb = new StringBuilder("[");
            boolean first = true;
            for (Object item : (List<?>) obj) { if (!first) sb.append(","); sb.append(toJson(item)); first = false; }
            return sb.append("]").toString();
        }
        return "\"" + escapeString(obj.toString()) + "\"";
    }
    
    private static String escapeString(String s) {
        return s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n").replace("\r", "\\r").replace("\t", "\\t");
    }
    
    static class SimpleJsonParser {
        private final String json; private int pos = 0;
        SimpleJsonParser(String json) { this.json = json.trim(); }
        Map<String, Object> parseObject() {
            Map<String, Object> map = new LinkedHashMap<>(); skip();
            if (pos >= json.length() || json.charAt(pos) != '{') return map; pos++;
            while (pos < json.length()) { skip(); if (pos >= json.length()) break; if (json.charAt(pos) == '}') { pos++; break; } if (json.charAt(pos) == ',') { pos++; continue; } String key = parseString(); skip(); if (pos < json.length() && json.charAt(pos) == ':') pos++; skip(); map.put(key, parseValue()); }
            return map;
        }
        Object parseValue() { skip(); if (pos >= json.length()) return null; char c = json.charAt(pos); if (c == '"') return parseString(); if (c == '{') return parseObject(); if (c == '[') return parseArray(); if (c == 't' || c == 'f') return parseBoolean(); if (c == 'n') { pos += 4; return null; } if (c == '-' || Character.isDigit(c)) return parseNumber(); return null; }
        String parseString() { skip(); if (pos >= json.length() || json.charAt(pos) != '"') return ""; pos++; StringBuilder sb = new StringBuilder(); while (pos < json.length()) { char c = json.charAt(pos++); if (c == '"') break; if (c == '\\' && pos < json.length()) { char n = json.charAt(pos++); if (n == 'n') sb.append('\n'); else if (n == 't') sb.append('\t'); else if (n == 'r') sb.append('\r'); else if (n == 'u' && pos + 3 < json.length()) { String hex = json.substring(pos, pos + 4); try { sb.append((char) Integer.parseInt(hex, 16)); } catch (Exception ignored) {} pos += 4; } else sb.append(n); } else sb.append(c); } return sb.toString(); }
        List<Object> parseArray() { List<Object> list = new ArrayList<>(); pos++; while (pos < json.length()) { skip(); if (pos >= json.length()) break; if (json.charAt(pos) == ']') { pos++; break; } if (json.charAt(pos) == ',') { pos++; continue; } list.add(parseValue()); } return list; }
        Number parseNumber() { int start = pos; if (json.charAt(pos) == '-') pos++; while (pos < json.length() && "0123456789.eE+-".indexOf(json.charAt(pos)) >= 0) pos++; String ns = json.substring(start, pos); try { return ns.contains(".") || ns.contains("e") || ns.contains("E") ? Double.parseDouble(ns) : Integer.parseInt(ns); } catch (Exception e) { return 0; } }
        Boolean parseBoolean() { if (json.substring(pos).startsWith("true")) { pos += 4; return true; } if (json.substring(pos).startsWith("false")) { pos += 5; return false; } return false; }
        void skip() { while (pos < json.length() && Character.isWhitespace(json.charAt(pos))) pos++; }
    }
    
    private static void logError(String message) { System.err.println("[Stock-MCP-Server] " + message); }
}