#!/bin/bash
#
# BitGuide Agent Platform (Java) 环境检查脚本
# 全面检验运行所需的所有依赖，按 Stage 维度区分
#
# 用法:
#   ./scripts/check-env.sh           # 全量检查
#   ./scripts/check-env.sh --stage 1 # 只检查 Stage 1 所需依赖
#   ./scripts/check-env.sh --stage 2 # 检查 Stage 1 + 2
#   ./scripts/check-env.sh --stage 3 # 检查全部（默认）
#   ./scripts/check-env.sh --fix     # 检查并输出修复命令
#

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"
STAGE=3
SHOW_FIX=false

# 解析参数
while [[ $# -gt 0 ]]; do
    case $1 in
        --stage) STAGE="$2"; shift 2 ;;
        --fix)   SHOW_FIX=true; shift ;;
        -h|--help)
            echo "用法: $0 [--stage 1|2|3] [--fix]"
            echo "  --stage N  只检查运行 Stage N 所需的依赖（默认 3 = 全部）"
            echo "  --fix      检查不通过时输出修复命令"
            exit 0 ;;
        *) echo "未知参数: $1"; exit 1 ;;
    esac
done

# 颜色
RED='\033[0;31m'; GREEN='\033[0;32m'; YELLOW='\033[1;33m'; BLUE='\033[0;34m'; CYAN='\033[0;36m'; BOLD='\033[1m'; NC='\033[0m'

PASS=0; FAIL=0; WARN=0
FIX_CMDS=()

pass() { echo -e "  ${GREEN}[✓]${NC} $1"; ((PASS++)); }
fail() { echo -e "  ${RED}[✗]${NC} $1"; ((FAIL++)); if [ -n "$2" ]; then echo -e "      ${YELLOW}→ $2${NC}"; FIX_CMDS+=("$2"); fi; }
warn() { echo -e "  ${YELLOW}[!]${NC} $1"; ((WARN++)); if [ -n "$2" ]; then echo -e "      ${YELLOW}→ $2${NC}"; fi; }
info() { echo -e "  ${BLUE}[i]${NC} $1"; }
section() { echo ""; echo -e "  ${BOLD}${BLUE}▶ $1${NC}"; echo -e "  ${BLUE}─────────────────────────────────────────────────${NC}"; }

# ════════════════════════════════════════
# 标题
# ════════════════════════════════════════

echo ""
echo -e "  ${BOLD}${CYAN}BitGuide Agent Platform (Java) — 环境检查${NC}"
echo -e "  ${BLUE}项目目录: ${NC}$PROJECT_ROOT"
echo -e "  ${BLUE}检查范围: ${NC}Stage 1~$STAGE"
echo -e "  ${BLUE}检查时间: ${NC}$(date '+%Y-%m-%d %H:%M:%S')"

# ════════════════════════════════════════
# Stage 1 基础: Java + Maven
# ════════════════════════════════════════

section "基础工具 (Stage 1+)"

if command -v java &>/dev/null; then
    JV=$(java -version 2>&1 | head -1 | awk -F'"' '{print $2}')
    JM=$(echo "$JV" | cut -d'.' -f1)
    if [ "$JM" -ge 17 ]; then
        pass "Java $JV"
    else
        fail "Java 版本过低 ($JV)" "需要 17+: brew install openjdk@17"
    fi
else
    fail "Java 未安装" "brew install openjdk@17"
fi

[ -n "$JAVA_HOME" ] && pass "JAVA_HOME=$JAVA_HOME" || warn "JAVA_HOME 未设置" "建议设置: export JAVA_HOME=\$(/usr/libexec/java_home)"

if command -v mvn &>/dev/null; then
    MV=$(mvn -version 2>&1 | head -1 | awk '{print $3}')
    pass "Maven $MV"
else
    fail "Maven 未安装" "brew install maven"
fi

# ════════════════════════════════════════
# Stage 1: Ollama
# ════════════════════════════════════════

section "Ollama LLM 服务 (Stage 1+)"

if command -v ollama &>/dev/null; then
    pass "Ollama 已安装"
else
    fail "Ollama 未安装" "brew install ollama  或访问 https://ollama.ai/download"
fi

if curl -sf http://localhost:11434/api/tags >/dev/null 2>&1; then
    pass "Ollama 服务运行中 (端口 11434)"

    # 检查 qwen2.5 模型
    if ollama list 2>/dev/null | grep -q "qwen2.5"; then
        pass "LLM 模型 qwen2.5 已下载"
    else
        fail "qwen2.5 模型未下载" "ollama pull qwen2.5"
    fi

    # 检查 embedding 模型
    if ollama list 2>/dev/null | grep -q "nomic-embed-text"; then
        pass "Embedding 模型 nomic-embed-text 已下载"
    else
        warn "nomic-embed-text 模型未下载 (RAG 功能需要)" "ollama pull nomic-embed-text"
    fi
else
    fail "Ollama 服务未运行" "新开终端运行: ollama serve"
fi

# ════════════════════════════════════════
# Stage 1+: Docker + Milvus（RAG 需要）
# ════════════════════════════════════════

section "Docker (Stage 1+)"

if command -v docker &>/dev/null; then
    pass "Docker 已安装"
    if docker info &>/dev/null 2>&1; then
        pass "Docker 正在运行"
    else
        fail "Docker 未运行" "启动 Docker Desktop 或运行: open -a Docker"
    fi
else
    fail "Docker 未安装" "https://docs.docker.com/get-docker/"
fi

if docker compose version &>/dev/null 2>&1; then
    pass "Docker Compose 可用"
elif command -v docker-compose &>/dev/null; then
    pass "docker-compose 可用"
else
    warn "Docker Compose 不可用" "安装 Docker Desktop 会自带 Compose"
fi

section "Milvus 向量库 (Stage 1+ RAG)"

if docker ps --format '{{.Names}}' 2>/dev/null | grep -q "bitguide-milvus"; then
    pass "Milvus 容器运行中"
    if nc -z localhost 19530 2>/dev/null; then
        pass "Milvus 端口 19530 可访问"
    else
        warn "Milvus 端口 19530 未就绪" "Milvus 启动较慢，等 30 秒重试"
    fi
else
    fail "Milvus 未运行 (Stage 1 RAG 依赖 Milvus)" "cd docker && docker compose up -d"
fi

# ════════════════════════════════════════
# Stage 2+: etcd
# ════════════════════════════════════════

if [ "$STAGE" -ge 2 ]; then

section "etcd 配置中心 (Stage 2+)"

if docker ps --format '{{.Names}}' 2>/dev/null | grep -q "bitguide-config-etcd"; then
    pass "etcd 容器运行中"
    if curl -sf http://localhost:2379/health 2>/dev/null | grep -q "true\|healthy"; then
        pass "etcd 健康检查通过 (端口 2379)"
    else
        warn "etcd 健康检查未通过" "可能还在启动中，等几秒重试"
    fi
else
    fail "etcd 容器未运行" "cd docker && docker compose up -d bitguide-etcd"
fi

# etcdctl
if command -v etcdctl &>/dev/null; then
    pass "etcdctl 已安装"
else
    warn "etcdctl 未安装 (练习 4 需要)" "brew install etcd"
fi

fi # STAGE >= 2

# ════════════════════════════════════════
# Stage 3: Redis
# ════════════════════════════════════════

if [ "$STAGE" -ge 3 ]; then

section "Redis (Stage 3)"

if nc -z localhost 6379 2>/dev/null; then
    pass "Redis 端口 6379 可访问"
else
    if docker ps --format '{{.Names}}' 2>/dev/null | grep -q "bitguide-redis"; then
        warn "Redis 容器运行中但端口未就绪" "可能还在启动中"
    else
        fail "Redis 未运行" "cd docker && docker compose up -d redis"
    fi
fi

fi # STAGE >= 3

# ════════════════════════════════════════
# 项目文件检查
# ════════════════════════════════════════

section "项目文件"

[ -f "$PROJECT_ROOT/pom.xml" ] && pass "pom.xml 存在" || fail "pom.xml 不存在"
[ -f "$PROJECT_ROOT/docker/docker-compose.yml" ] && pass "docker/docker-compose.yml 存在" || warn "docker-compose.yml 不存在"

# 配置文件
for S in 1 2 3; do
    CFG="src/stage${S}/java/com/bitguide/stage${S}/config/stage${S}.yml"
    if [ -f "$PROJECT_ROOT/$CFG" ]; then
        pass "Stage $S 配置: $CFG"
    else
        fail "Stage $S 配置缺失: $CFG"
    fi
done

# MCP Server
for MCP in parenting-mcp-server/ParentingMcpServer.java stock-mcp-server/StockMcpServer.java; do
    if [ -f "$PROJECT_ROOT/mcp-servers/$MCP" ]; then
        pass "MCP Server: $MCP"
    else
        warn "MCP Server 缺失: mcp-servers/$MCP"
    fi
done

# 知识库
for KB in parenting_docs; do
    DIR="$PROJECT_ROOT/knowledge/$KB"
    if [ -d "$DIR" ]; then
        CNT=$(find "$DIR" -type f -name "*.md" 2>/dev/null | wc -l | tr -d ' ')
        [ "$CNT" -gt 0 ] && pass "知识库 $KB: $CNT 个文档" || warn "知识库 $KB 目录为空"
    else
        warn "知识库目录缺失: knowledge/$KB"
    fi
done

# ════════════════════════════════════════
# 端口总览
# ════════════════════════════════════════

section "端口状态"

for P in "11434:Ollama" "2379:etcd(Stage2+)" "6379:Redis(Stage3)" "19530:Milvus(RAG)" "8080:应用(Stage3)"; do
    PORT=${P%%:*}; SVC=${P#*:}
    if nc -z localhost "$PORT" 2>/dev/null; then
        pass "端口 $PORT ($SVC) 已监听"
    else
        info "端口 $PORT ($SVC) 未监听"
    fi
done

# ════════════════════════════════════════
# 摘要
# ════════════════════════════════════════

echo ""
echo -e "  ${BOLD}─────────────────────────────────────────────────${NC}"
echo -e "  ${GREEN}✓ 通过: $PASS${NC}  ${RED}✗ 失败: $FAIL${NC}  ${YELLOW}! 警告: $WARN${NC}"
echo -e "  ${BOLD}─────────────────────────────────────────────────${NC}"

if [ $FAIL -eq 0 ]; then
    echo ""
    echo -e "  ${GREEN}${BOLD}环境就绪！启动命令:${NC}"
    echo ""
    echo "    Stage 1:  IDEA 中找到 stage1 main 入口启动运行"
    echo "    Stage 2:  IDEA 中找到 stage2 main 入口启动运行"
    echo "    Stage 3:  IDEA 中找到 stage3 main 入口启动运行"
    echo ""
else
    echo ""
    echo -e "  ${RED}${BOLD}存在 $FAIL 个问题，请按以下步骤修复:${NC}"
    echo ""

    if $SHOW_FIX && [ ${#FIX_CMDS[@]} -gt 0 ]; then
        echo -e "  ${YELLOW}修复命令:${NC}"
        for cmd in "${FIX_CMDS[@]}"; do
            echo -e "    ${BLUE}$cmd${NC}"
        done
        echo ""
    fi

    echo "  快速修复步骤:"
    echo ""
    echo "    1) 启动 Docker Desktop"
    echo "    2) cd docker && docker compose up -d"
    echo "    3) 新终端: ollama serve"
    echo "    4) 新终端: ollama pull qwen2.5 && ollama pull nomic-embed-text"
    echo "    5) 重新运行: ./scripts/check-env.sh --stage $STAGE"
    echo ""
fi
