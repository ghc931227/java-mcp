# javatool-mcp

基于 Quarkus 的通用 MCP (Model Context Protocol) 服务器，通过 stdio 与任意 MCP 客户端（Claude Desktop、Trae 等）协作。能力不局限于数据库：内置数据库操作、动态 Java 执行、自定义命令注册与 AI 辅助开发工具。

[![Quarkus](https://img.shields.io/badge/Quarkus-3.17.4-blue.svg)](https://quarkus.io/)
[![Java](https://img.shields.io/badge/Java-17+-orange.svg)](https://openjdk.org/)
[![MCP](https://img.shields.io/badge/MCP-1.0-purple.svg)](https://modelcontextprotocol.io/)

## 能力分层

工具按能力前缀分组，`tools/list` 也按此顺序返回：

| 分组 | 前缀 | 工具 | 说明 |
|------|------|------|------|
| 元工具 | - | `add-command`、`tool-desc` | 注册自定义命令、查询工具描述 |
| Java 执行 | `java-` | `java-exec` | 即席执行 Java 代码片段 |
| 开发工具 | `dev_` | `dev_get_file`、`dev_ls`、`dev_edit`、`dev_add`、`dev_del`、`dev_restart` | 读写项目源码（`src/` 下）与热重载 |
| 数据库 | `jdbc-` | `jdbc-query`、`jdbc-exec`、`jdbc-add-conn` 等 17 个 | 连接管理、元数据、SQL、批量、导入导出、事务 |
| 自定义命令 | 动态 | 用户注册的命令 | 按创建时间排在最后 |

## 功能特性
- **自定义命令**：`add-command` 把常用逻辑固化为具名、可复用的 MCP 工具（Java 代码 + 参数定义，持久化到 `commands.yaml`）
- **多数据库支持**：MySQL、PostgreSQL、Oracle、SQL Server、SQLite、MariaDB、Kingbase、DM、H2——驱动全部内置（见 `pom.xml`），无需手动配置 `driverJarPath`
- **动态 Java 执行**：`java-exec` 在服务器进程中执行 Java 代码（无沙箱，注意安全）
- 基于 JDK `javax.tools.JavaCompiler` 实时编译（不依赖第三方模板引擎）
- 传 `jarPaths` 时走独立 `URLClassLoader` 加载外部 JAR，可直接 `import` 外部类（静态方法/字段/反射均可），线程级隔离，不影响主进程
- **完整的数据库操作**：连接管理（增删改查、密码 Jasypt 加密存储）、元数据查询（库/模式/表/视图/DDL）、SQL 查询与执行、事务（begin/commit/rollback）
- **AI 辅助开发**：`dev_*` 工具读写项目源码（仅限 `src/` 下，路径相对 `src/`），配合 Quarkus 热重载实现代码修复闭环
- **工具自描述**：`tools/list` 只返回工具名与调用 schema（精简）；需要描述时用 `tool-desc` 按需查询

## 快速开始

### 前置要求

- **JDK 17+**（推荐 21）
- **Maven 3.8+**
- **MCP 客户端**（可选）

### 构建

```bash
# 后端 jar（target/quarkus-app/quarkus-run.jar）
mvn package
# uber-jar（target/javatool-mcp-1.0.0-SNAPSHOT-runner.jar，包含所有依赖项）
-Dquarkus.package.jar.type=uber-jar

# mask 代理（mask/target/mcp-mask.jar，多实例接入用）
mvn -f mask/pom.xml package
```

### 启动

启动方式统一由项目根的 `start.bat`（Windows）/ `start.sh`（Linux/macOS）承载，通过环境变量 `BACKEND_LAUNCH_MODE` 选择三种子模式：

| `BACKEND_LAUNCH_MODE` | 启动方式 | 说明 |
|------|------|------|
| `source` | `mvn quarkus:dev` | 开发模式：热重载，注册全部 30 个工具（含 `dev_*`）；脚本已加 `-Ddebug=false -Dquarkus.dev.no-interactive` 保证 MCP stdio 兼容 |
| `jar`（默认） | `java -jar target/quarkus-app/quarkus-run.jar` | 生产模式：注册 24 个工具（不含 `dev_*`），MCP 客户端的标准选择 |

```bash
# 示例：以 jar 方式启动
BACKEND_LAUNCH_MODE=jar CONSOLE_TOKEN=123456 java 任意方式运行 start.sh   # Linux
set BACKEND_LAUNCH_MODE=jar & start.bat                                   # Windows
```

启动成功后同时具备两种能力：

- **MCP stdio**：作为 MCP 客户端的子进程，stdin/stdout 走 JSON-RPC
- **Web 控制台**：`http://localhost:8080/console/`（端口由 `CONSOLE_PORT` 控制），提供仪表盘、工具调用器、连接管理、SQL 工作台、日志中心、调试历史与项目文件浏览；必须在 `CONSOLE_TOKEN` 设置后启用

### 接入 MCP 客户端

#### 方式一（推荐）：mask 代理模式，多实例安全

mask（`mask/target/mcp-mask.jar`）是一个极轻量的 stdio↔HTTP 桥接进程：自身不监听端口，对 MCP 客户端表现为标准 stdio server，把请求翻译为 HTTP 调用后端。**多个客户端实例共享同一个后端**——先启动的实例经 mask 经 `start.bat` 拉起后端；后续实例的 mask 探测到后端已就绪则直连；后端进程与 mask 生命周期解耦（客户端退出不杀后端）。

```json
{
  "mcpServers": {
    "javatool-mcp": {
      "command": "D:/Soft/Java/jdk-21.0.8/bin/java.exe",
      "args": ["-Xmx64m", "-jar", "D:/path/to/java-mcp/mask/target/mcp-mask.jar"],
      "env": {
        "CONSOLE_TOKEN": "123456",
        "CONSOLE_PORT": "8080",
        "BACKEND_LAUNCH_MODE": "jar",
        "MASK_START_SCRIPT": "D:/path/to/java-mcp/start.bat"
      }
    }
  }
}
```

#### 方式二：直连后端（仅限单实例）

后端自身支持 stdio MCP，可直接作为客户端子进程（少一层代理）：

```json
{
  "mcpServers": {
    "javatool-mcp": {
      "command": "D:/Soft/Java/jdk-21.0.8/bin/java.exe",
      "args": ["-Dfile.encoding=UTF-8", "-jar", "D:/path/to/java-mcp/target/quarkus-app/quarkus-run.jar"],
      "env": {
        "CONSOLE_TOKEN": "123456",
        "JDBC_ENCRYPTION_KEY": "your-secret-key",
        "DATA_DIR": "D:/path/to/java-mcp/data"
      }
    }
  }
}
```

**限制**：第二个实例若用相同 `CONSOLE_PORT`，后启动的后端因端口绑定失败而退出（该实例 MCP 不可用）；确需多后端共存时必须同时换 `CONSOLE_PORT` 与 `DATA_DIR`（文件存储无锁，共享会互相覆盖）。多实例场景一律用 mask 模式。

macOS/Linux 配置同理：`command` 指向 `java`，`MASK_START_SCRIPT` 指向 `start.sh`。

## 运行模式说明

- **源码启动**（`BACKEND_LAUNCH_MODE=source`，`mvn quarkus:dev`）：自动检测为开发模式，注册**全部 30 个工具**（含 `dev_*` 开发工具），支持热重载。为兼容 MCP stdio，脚本已禁用交互控制台与横幅、抑制 Maven 日志（`-q`）、禁用调试端口，stdout 干净、stdin 不被抢占，可直接作为 MCP 客户端进程
- **jar 启动**（`BACKEND_LAUNCH_MODE=jar`）：自动检测为生产模式，注册 **24 个工具**（不含 `dev_*`），是 MCP 客户端的标准选择

## 配置

环境变量（均有默认值）：

| 变量 | 默认值 | 说明 |
|------|--------|------|
| `CONSOLE_PORT` | `8080` | Web 控制台端口（`http://localhost:<port>/console/`）；多实例共存时必须各自不同 |
| `CONSOLE_TOKEN` | （空） | Web 控制台与 mask 的访问令牌；**未设置时 `/api/*` 全部返回 503**（控制台与 mask 均不可用） |
| `DATA_DIR` | `./data` | 数据目录根，统一存放连接配置、命令存储、日志文件 |
| `BACKEND_LAUNCH_MODE` | `jar` | 后端启动方式：`source` / `jar` / `binary`（由 start.bat/start.sh 与 mask 解析） |
| `MASK_START_SCRIPT` | 按平台 `start.bat` / `start.sh` | mask 自动拉起后端时调用的脚本路径 |
| `JDBC_ENCRYPTION_KEY` | （空） | 连接密码加密密钥；**可为空**，为空时密码明文存储 |
| `JDBC_STORAGE_PATH` | `${DATA_DIR}/connections.yaml` | 连接配置存储（YAML） |
| `MCP_COMMANDS_PATH` | `${DATA_DIR}/commands.yaml` | 自定义命令存储（YAML） |
| `QUARKUS_LOG_LEVEL` | `INFO` | 日志级别（Quarkus 原生 relaxed mapping，如 `DEBUG`） |
| `QUARKUS_LOG_FILE_PATH` | `${DATA_DIR}/mcp.log` | 日志文件路径 |
| `JDBC_POOL_MAX_ACTIVE` | `10` | 连接池最大连接数 |
| `JDBC_POOL_MAX_WAIT` | `30000` | 借连接等待上限 (ms) |
| `JDBC_POOL_INITIAL_SIZE` / `JDBC_POOL_MIN_IDLE` | `1` / `1` | 初始连接数 / 最小空闲连接 |
| `JDBC_POOL_LOGIN_TIMEOUT` / `JDBC_POOL_CONNECT_TIMEOUT` | `5` (s) / `5000` (ms) | 建连超时 |
| `JDBC_POOL_SOCKET_TIMEOUT` | `1800000` (ms) | Socket 读超时 |
| `JDBC_QUERY_DEFAULT_TIMEOUT` / `JDBC_QUERY_MAX_TIMEOUT` | `180` / `1800` (s) | SQL 语句默认超时 / 上限（工具参数 `timeoutSeconds` 非必填） |
| `JAVA_HOME` | PATH 中的 java | JDK 路径（启动脚本优先使用） |

程序数据全部为 YAML 文件存储：连接配置、命令代码，均可直接编辑文件。

## 工具列表

完整参考见 [TOOLS_SUMMARY.md](TOOLS_SUMMARY.md)。

### 数据库（17 个，`jdbc-` 前缀）

- 连接管理：`jdbc-list-conn`、`jdbc-add-conn`、`jdbc-edit-conn`、`jdbc-del-conn`
- 元数据：`jdbc-list-db`、`jdbc-list-schema`、`jdbc-list-tables`、`jdbc-list-views`、`jdbc-get-table-ddl`
- SQL：`jdbc-query`、`jdbc-exec`、`jdbc-exec-batch`
- 导入导出：`jdbc-export`、`jdbc-import`（csv / json / inserts 三种格式）
- 事务：`jdbc-tx-begin`、`jdbc-tx-commit`、`jdbc-tx-rollback`

### 通用（7 个）

- `java-exec`：动态执行 Java 代码（即席）
- `add-command`：注册自定义命令为 MCP 工具（命名复用）
- `edit-command` / `delete-command` / `get-command` / `list-commands`：命令管理
- `tool-desc`：查询工具描述与使用说明

### 开发（6 个，`dev_` 前缀）

`dev_get_file`、`dev_ls`、`dev_edit`、`dev_add`、`dev_del`、`dev_restart`

## 常见问题

### Q1: dev 模式能用于 MCP 客户端吗？

能。`start.bat` / `start.sh` 已做 MCP stdio 兼容处理（禁用交互控制台/横幅、抑制 Maven 日志、禁用调试端口），stdout 干净、stdin 不被抢占，支持热重载。

### Q2: 数据库驱动需要自己下载吗？

不需要。MySQL/PostgreSQL/Oracle/SQL Server/SQLite/MariaDB/Kingbase/DM/H2 驱动均已内置打包；仅在需要自选驱动版本时用 `driverJarPath` 指定外部 jar。

### Q3: 密码如何存储？

连接密码使用 Jasypt 加密后写入 `connections.yaml`；密钥通过 `JDBC_ENCRYPTION_KEY` 指定，丢失后已保存的密码无法解密。未设置密钥时密码明文存储，通过工具添加的连接也能正常工作。

### Q4: 自定义命令如何用？

`add-command` 注册（名称、Java 代码、参数定义），保存到 `commands.yaml`，重启后自动注册为工具，出现在 `tools/list` 末尾，可用 `tool-desc` 查询其描述。

### Q5: 多个客户端实例同时启动会怎样？

直接连后端时，第二个实例的后端进程会因 8080 端口冲突而退出（先启动的不受影响）。使用 mask 模式可彻底规避：每个实例启动各自的 mask（不占端口），共享第一个成功绑定的后端。确需多后端共存时，为每个后端同时设置不同的 `CONSOLE_PORT` 与 `DATA_DIR`。

### Q6: `MCP_STDIN_KEEPALIVE` 是干什么的？

mask 自动拉起后端时会给后端注入该变量：后端检测到 stdin 关闭（无 MCP 客户端）时不退出、继续保留 Web 控制台能力。手动运行后端时若只想做 Web 控制台，也可设置它。

## 文档

- [TOOLS_SUMMARY.md](TOOLS_SUMMARY.md) - 工具参考手册（参数、示例、启动脚本）
