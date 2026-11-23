#!/bin/bash
# ============================================================
# BitGuide Stage 4 环境检查脚本（Java 版）
#
# 用法: ./scripts/check-stage4-env.sh
#
# Stage 4 只需要基础环境即可运行：
#   必需: JDK 17+, Maven, Ollama + qwen2.5
#   推荐: Redis (Checkpoint 持久化，断点续跑跨重启)
#   可选: etcd (动态配置)
# ============================================================

# 不使用 set -e，让检查完整执行

# 颜色定义
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[0;33m'
CYAN='\033[0;36m'
BOLD='\033[1m'
NC='\033[0m'

PASS=0
FAIL=0
WARN=0

pass() { echo -e "  ${GREEN}✓ $1${NC}"; ((PASS++)); }
fail() { echo -e "  ${RED}✗ $1${NC}"; ((FAIL++)); }
warn() { echo -e "  ${YELLOW}⚠ $1${NC}"; ((WARN++)); }
info() { echo -e "  ${CYAN}→ $1${NC}"; }

echo ""
echo -e "${BOLD}╔══════════════════════════════════════════╗${NC}"
echo -e "${BOLD}║  BitGuide Stage 4 — 环境检查 (Java)     ║${NC}"
echo -e "${BOLD}╚══════════════════════════════════════════╝${NC}"
echo ""

# ---- 必需检查 ----
echo -e "${BOLD}[1/5] Java 开发环境${NC}"

if command -v java &>/dev/null; then
    JAVA_VER_STR=$(java -version 2>&1 | head -1)
    # 提取主版本号: "17.0.x" → 17, "1.8.x" → 8
    JAVA_VER_NUM=$(echo "$JAVA_VER_STR" | sed 's/.*"\([0-9]*\)\.\([0-9]*\).*/\1/' )
    if [ "$JAVA_VER_NUM" = "1" ]; then
        # JDK 8 格式: 1.8.x
        JAVA_VER_NUM=$(echo "$JAVA_VER_STR" | sed 's/.*"\([0-9]*\)\.\([0-9]*\).*/\2/')
    fi
    if [ "$JAVA_VER_NUM" -ge 17 ] 2>/dev/null; then
        pass "Java $(echo "$JAVA_VER_STR" | sed 's/.*"\(.*\)"/\1/')"
    else
        fail "Java 版本过低 (需要 17+): $JAVA_VER_NUM"
        info "修复: brew install openjdk@17"
    fi
else
    fail "未找到 java 命令"
    info "修复: brew install openjdk@17"
fi

if command -v mvn &>/dev/null; then
    MVN_VER=$(mvn -version 2>/dev/null | head -1 | awk '{print $3}')
    pass "Maven $MVN_VER"
else
    fail "未找到 mvn 命令"
    info "修复: brew install maven"
fi

echo ""
echo -e "${BOLD}[2/5] 项目文件${NC}"

PROJ_ROOT="$(cd "$(dirname "$0")/.." && pwd)"

if [ -f "$PROJ_ROOT/pom.xml" ]; then
    pass "pom.xml 存在"
else
    fail "pom.xml 未找到，请确认在项目根目录运行"
fi

if [ -f "$PROJ_ROOT/src/stage4/java/com/bitguide/stage4/Stage4App.java" ]; then
    pass "Stage4App.java 存在"
else
    fail "Stage4App.java 未找到"
fi

if [ -f "$PROJ_ROOT/src/stage4/java/com/bitguide/stage4/config/stage4.yml" ]; then
    pass "stage4.yml 配置文件存在"
else
    fail "stage4.yml 未找到"
fi

# 检查 workflow 核心代码
WF_CORE="$PROJ_ROOT/src/main/java/com/bitguide/workflow/core"
if [ -d "$WF_CORE" ]; then
    CORE_FILES=$(ls "$WF_CORE"/*.java 2>/dev/null | wc -l | tr -d ' ')
    if [ "$CORE_FILES" -ge 5 ]; then
        pass "工作流引擎核心代码 ($CORE_FILES 个文件)"
    else
        warn "工作流核心代码不完整 ($CORE_FILES 个文件)"
    fi
else
    fail "工作流引擎核心目录不存在: $WF_CORE"
fi

echo ""
echo -e "${BOLD}[3/5] Ollama 与模型${NC}"

if curl -s --connect-timeout 3 http://localhost:11434/api/tags &>/dev/null; then
    pass "Ollama 服务正在运行 (port 11434)"

    # 检查 qwen2.5 模型
    MODELS=$(curl -s http://localhost:11434/api/tags 2>/dev/null)
    if echo "$MODELS" | grep -q "qwen2.5"; then
        pass "qwen2.5 模型已下载"
    else
        fail "qwen2.5 模型未下载"
        info "修复: ollama pull qwen2.5"
    fi
else
    fail "Ollama 未运行或端口 11434 不可达"
    info "修复: 新开终端运行 ollama serve，然后 ollama pull qwen2.5"
fi

echo ""
echo -e "${BOLD}[4/5] 编译检查${NC}"

if [ -d "$PROJ_ROOT/target/classes" ]; then
    pass "项目已编译 (target/classes 存在)"
else
    warn "项目未编译"
    info "修复: cd $PROJ_ROOT && mvn compile"
fi

echo ""
echo -e "${BOLD}[5/5] 推荐依赖${NC}"

# Redis (推荐，断点续跑需要)
if curl -s --connect-timeout 2 redis://localhost:6379 &>/dev/null 2>&1 || \
   (command -v redis-cli &>/dev/null && redis-cli -h localhost -p 6379 ping 2>/dev/null | grep -q PONG); then
    pass "Redis 可连接 (Checkpoint 持久化可用)"
else
    warn "Redis 未运行（推荐启用，断点续跑将使用内存存储不跨重启）"
    info "启用: docker compose up -d bitguide-redis"
fi

# etcd
if curl -s --connect-timeout 2 http://localhost:2379/v3/cluster/member/list &>/dev/null; then
    pass "etcd 可连接 (动态配置可用)"
else
    warn "etcd 未运行 — 动态配置功能不可用"
    info "启用: docker compose up -d bitguide-etcd"
fi

# ---- 汇总 ----
echo ""
echo -e "${BOLD}════════════════════════════════════════════${NC}"
TOTAL=$((PASS + FAIL + WARN))
echo -e "  通过: ${GREEN}$PASS${NC}  失败: ${RED}$FAIL${NC}  警告: ${YELLOW}$WARN${NC}  共 $TOTAL 项"
echo ""

if [ "$FAIL" -eq 0 ]; then
    echo -e "${GREEN}${BOLD}环境就绪！运行 Stage 4:${NC}"
    echo ""
    echo -e "  ${CYAN}cd $PROJ_ROOT${NC}"
    echo -e "  ${CYAN}mvn compile${NC}"
    echo -e "  ${CYAN}mvn exec:java -Dexec.mainClass=\"com.bitguide.stage4.Stage4App\"${NC}"
else
    echo -e "${RED}${BOLD}请先修复上述失败项再启动 Stage 4。${NC}"
fi

echo ""
