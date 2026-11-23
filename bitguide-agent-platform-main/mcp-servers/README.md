# MCP Servers

本目录包含 BitGuide Agent Platform 的 MCP (Model Context Protocol) 服务器实现。

每个 MCP Server 都是一个独立的进程，通过 stdio 与 Agent Platform 通信，提供业务领域相关的工具。

## 目录结构

```
mcp-servers/
├── README.md
├── parenting-mcp-server/          # 亲子教育工具集
│   └── ParentingMcpServer.java
└── stock-mcp-server/              # 股票分析工具集
    └── StockMcpServer.java
```

## Parenting Tools MCP Server - 亲子教育工具集

提供亲子教育场景相关的工具能力。

### 功能

| 工具名 | 描述 | 参数 |
|--------|------|------|
| `age_calculator` | 根据出生年月计算精确年龄及适龄活动建议 | `birth_year`: 出生年份（必填）, `birth_month`: 出生月份（可选） |
| `get_weather` | 查询指定城市的当前天气 | `city`: 城市名称（支持中文和英文） |
| `get_forecast` | 查询未来3天天气预报 | `city`: 城市名称 |

### 运行方式

```bash
java mcp-servers/parenting-mcp-server/ParentingMcpServer.java
```

### 配置示例

```yaml
# 内联配置（推荐，Agent 级别隔离）
mcpServers:
  - name: parenting-tools
    transport: stdio
    command: java
    args: ["mcp-servers/parenting-mcp-server/ParentingMcpServer.java"]
    timeout: 30000
```

## Stock MCP Server - 股票分析工具集

提供 A 股市场分析相关的工具能力。

### 功能

| 工具名 | 描述 | 参数 |
|--------|------|------|
| `market_overview` | 查询 A 股市场整体概览 | （无参数） |
| `stock_quote` | 查询个股实时行情 | `symbol`: 股票代码 |
| `sector_analysis` | 分析板块热点和资金流向 | `top_n`: 返回前 N 个板块（默认 5） |

### 运行方式

```bash
java mcp-servers/stock-mcp-server/StockMcpServer.java
```

### 配置示例

```yaml
mcpServers:
  - name: stock
    transport: stdio
    command: java
    args: ["mcp-servers/stock-mcp-server/StockMcpServer.java"]
    timeout: 30000
```

## MCP 协议

所有 MCP Server 基于 MCP 2024-11-05 协议版本，通过 stdio 传输：
- JSON-RPC 2.0 格式
- 支持 initialize、tools/list、tools/call 方法

## 天气数据来源

使用 [wttr.in](https://wttr.in) 免费 API：
- 无需 API Key
- 支持全球城市
- 支持中文城市名