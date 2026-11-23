#!/bin/bash

# BitGuide Etcd 统一配置管理脚本
# 集成配置推送、查询、删除、批量操作于一体

set -e

ETCD_ENDPOINT=${ETCD_ENDPOINT:-"http://localhost:2379"}
CONFIG_PREFIX="/bitguide/config"
CONFIG_DIR="$(cd "$(dirname "$0")/.." && pwd)/config/etcd-examples"

# 颜色输出
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
BLUE='\033[0;34m'
NC='\033[0m' # No Color

print_info() {
    echo -e "${GREEN}[INFO]${NC} $1"
}

print_warn() {
    echo -e "${YELLOW}[WARN]${NC} $1"
}

print_error() {
    echo -e "${RED}[ERROR]${NC} $1"
}

print_header() {
    echo -e "${BLUE}========================================${NC}"
    echo -e "${BLUE}  $1${NC}"
    echo -e "${BLUE}========================================${NC}"
}

print_usage() {
    echo "BitGuide Etcd 统一配置管理脚本"
    echo ""
    echo "用法: $0 <命令> [参数]"
    echo ""
    echo "=== 基础操作 ==="
    echo "  put-agent <id> <json_file>    推送 Agent 配置"
    echo "  put-model <id> <json_file>    推送模型配置"
    echo "  put-prompt <id> <json_file>   推送提示词配置"
    echo "  get <type> <id>               获取配置"
    echo "  delete <type> <id>            删除单个配置"
    echo "  list [type]                   列出配置"
    echo "  watch                         监听配置变更"
    echo ""
    echo "=== 批量操作 ==="
    echo "  push-demo <preset>            推送预定义的示例配置"
    echo "                                presets: parenting, stock, all"
    echo "  clear [type]                  清空配置 (不指定类型则清空所有)"
    echo ""
    echo "类型: agent, model, prompt, knowledgebase"
    echo ""
    echo "示例:"
    echo "  # 基础操作"
    echo "  $0 put-agent parenting_advisor ./agent-config.json"
    echo "  $0 get agent parenting_advisor"
    echo "  $0 delete agent old_agent"
    echo "  $0 list agent"
    echo "  $0 watch"
    echo ""
    echo "  # 批量操作"
    echo "  $0 push-demo parenting        # 推送亲子助手示例配置"
    echo "  $0 push-demo stock            # 推送股票助手示例配置"
    echo "  $0 push-demo all              # 推送所有示例配置"
    echo "  $0 clear                      # 清空所有配置 (需确认)"
    echo "  $0 clear agent                # 清空所有 agent 配置 (需确认)"
    echo ""
    echo "环境变量:"
    echo "  ETCD_ENDPOINT  Etcd 地址 (默认: http://localhost:2379)"
}

# 检查 etcdctl 是否安装
check_etcdctl() {
    if ! command -v etcdctl &> /dev/null; then
        print_error "etcdctl 未安装，请先安装 etcd 客户端"
        exit 1
    fi
}

# 获取配置类型路径
get_type_path() {
    local type=$1
    case $type in
        agent|agents)
            echo "agents"
            ;;
        model|models)
            echo "models"
            ;;
        prompt|prompts)
            echo "prompts"
            ;;
        knowledgebase|knowledgebases|kb)
            echo "knowledgebases"
            ;;
        *)
            print_error "未知的配置类型: $type"
            exit 1
            ;;
    esac
}

# 推送配置
put_config() {
    local type=$1
    local id=$2
    local json_file=$3
    
    if [ ! -f "$json_file" ]; then
        print_error "配置文件不存在: $json_file"
        exit 1
    fi
    
    local type_path=$(get_type_path "$type")
    local key="${CONFIG_PREFIX}/${type_path}/${id}"
    local value=$(cat "$json_file")
    
    print_info "推送配置: $key"
    
    # 使用 etcdctl put
    echo "$value" | etcdctl --endpoints="$ETCD_ENDPOINT" put "$key"
    
    if [ $? -eq 0 ]; then
        print_info "✓ 配置推送成功"
    else
        print_error "✗ 配置推送失败"
        exit 1
    fi
}

# 推送预定义配置文件
put_demo_config() {
    local type=$1
    local id=$2
    local file=$CONFIG_DIR/$3
    
    if [ ! -f "$file" ]; then
        print_error "配置文件不存在: $file"
        return 1
    fi
    
    local key="${CONFIG_PREFIX}/${type}/${id}"
    local value=$(cat "$file")
    
    print_info "推送配置: $key"
    echo "$value" | etcdctl --endpoints="$ETCD_ENDPOINT" put "$key"
    
    if [ $? -eq 0 ]; then
        print_info "✓ 配置推送成功"
    else
        print_error "✗ 配置推送失败"
        return 1
    fi
}

# 获取配置
get_config() {
    local type=$1
    local id=$2
    
    local type_path=$(get_type_path "$type")
    local key="${CONFIG_PREFIX}/${type_path}/${id}"
    
    print_info "获取配置: $key"
    etcdctl --endpoints="$ETCD_ENDPOINT" get "$key" --print-value-only
}

# 删除配置
delete_config() {
    local type=$1
    local id=$2
    
    local type_path=$(get_type_path "$type")
    local key="${CONFIG_PREFIX}/${type_path}/${id}"
    
    print_warn "删除配置: $key"
    etcdctl --endpoints="$ETCD_ENDPOINT" del "$key"
    
    if [ $? -eq 0 ]; then
        print_info "✓ 配置删除成功"
    else
        print_error "✗ 配置删除失败"
        exit 1
    fi
}

# 列出配置
list_config() {
    local type=$1
    
    if [ -z "$type" ]; then
        # 列出所有配置
        print_header "当前 Etcd 配置列表"
        etcdctl --endpoints="$ETCD_ENDPOINT" get "$CONFIG_PREFIX" --prefix --keys-only
    else
        local type_path=$(get_type_path "$type")
        local key="${CONFIG_PREFIX}/${type_path}/"
        print_header "${type} 配置列表"
        etcdctl --endpoints="$ETCD_ENDPOINT" get "$key" --prefix --keys-only
    fi
}

# 监听配置变更
watch_config() {
    print_info "开始监听配置变更 (按 Ctrl+C 退出)..."
    etcdctl --endpoints="$ETCD_ENDPOINT" watch "$CONFIG_PREFIX" --prefix
}

# 推送亲子助手配置
push_demo_parenting() {
    print_header "推送亲子教育顾问配置"
    put_demo_config "models" "ollama" "model-ollama.json"
    put_demo_config "agents" "parenting_advisor" "agent-parenting_advisor.json"
    echo ""
    print_info "亲子教育顾问配置推送完成！"
}

# 推送股票助手配置
push_demo_stock() {
    print_header "推送股票助手配置"
    put_demo_config "models" "ollama" "model-ollama.json"
    put_demo_config "agents" "stock_assistant" "agent-stock_assistant.json"
    echo ""
    print_info "股票助手配置推送完成！"
}

# 推送所有配置
push_demo_all() {
    print_header "推送所有示例配置"
    put_demo_config "models" "ollama" "model-ollama.json"
    put_demo_config "agents" "parenting_advisor" "agent-parenting_advisor.json"
    put_demo_config "agents" "stock_assistant" "agent-stock_assistant.json"
    echo ""
    print_info "所有示例配置推送完成！"
}

# 清空配置
clear_config() {
    local type=$1
    
    if [ -z "$type" ]; then
        # 清空所有配置
        print_header "清空所有 Etcd 配置"
        print_warn "即将删除前缀 $CONFIG_PREFIX 下的所有配置"
        read -p "确认删除? (y/N): " confirm
        
        if [ "$confirm" != "y" ] && [ "$confirm" != "Y" ]; then
            print_info "已取消"
            exit 0
        fi
        
        etcdctl --endpoints="$ETCD_ENDPOINT" del "$CONFIG_PREFIX" --prefix
        print_info "✓ 所有配置已清空"
    else
        # 清空指定类型的配置
        local type_path=$(get_type_path "$type")
        local key="${CONFIG_PREFIX}/${type_path}/"
        
        print_header "清空 ${type} 配置"
        print_warn "即将删除前缀 $key 下的所有配置"
        read -p "确认删除? (y/N): " confirm
        
        if [ "$confirm" != "y" ] && [ "$confirm" != "Y" ]; then
            print_info "已取消"
            exit 0
        fi
        
        etcdctl --endpoints="$ETCD_ENDPOINT" del "$key" --prefix
        print_info "✓ ${type} 配置已清空"
    fi
}

# 主程序
check_etcdctl

case "${1:-}" in
    put-agent)
        if [ $# -lt 3 ]; then
            print_usage
            exit 1
        fi
        put_config "agent" "$2" "$3"
        ;;
    put-model)
        if [ $# -lt 3 ]; then
            print_usage
            exit 1
        fi
        put_config "model" "$2" "$3"
        ;;
    put-prompt)
        if [ $# -lt 3 ]; then
            print_usage
            exit 1
        fi
        put_config "prompt" "$2" "$3"
        ;;
    get)
        if [ $# -lt 3 ]; then
            print_usage
            exit 1
        fi
        get_config "$2" "$3"
        ;;
    delete)
        if [ $# -lt 3 ]; then
            print_usage
            exit 1
        fi
        delete_config "$2" "$3"
        ;;
    list)
        list_config "$2"
        ;;
    watch)
        watch_config
        ;;
    push-demo)
        if [ -z "${2:-}" ]; then
            print_error "请指定预设名称: parenting, stock, all"
            print_usage
            exit 1
        fi
        case "$2" in
            parenting)
                push_demo_parenting
                ;;
            stock)
                push_demo_stock
                ;;
            all)
                push_demo_all
                ;;
            *)
                print_error "未知的预设: $2"
                print_info "可用预设: parenting, stock, all"
                exit 1
                ;;
        esac
        ;;
    clear)
        clear_config "$2"
        ;;
    help|--help|-h)
        print_usage
        ;;
    *)
        print_usage
        exit 1
        ;;
esac