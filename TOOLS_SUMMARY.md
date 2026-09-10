# JavaTool MCP 服务器 - 完整工具列表

本文档列出了 JavaTool MCP 服务器提供的所有 MCP 工具及其功能说明。

## 工具分类

- **连接管理工具** (4 个) - 管理数据库连接配置
- **元数据查询工具** (5 个) - 查询数据库结构信息
- **SQL 执行工具** (2 个) - 执行 SQL 查询和语句
- **事务管理工具** (3 个) - 开启/提交/回滚事务
- **开发辅助工具** (6 个) - 项目文件管理和热重载（仅开发模式）
- **通用工具** (3 个) - 动态 Java 代码执行、自定义命令注册、工具描述查询（所有模式可用）

> 🔗 **命名规范**: 数据库相关工具统一使用 `jdbc-` 前缀；文件/开发工具使用 `dev_` 前缀；通用工具使用 `java-` / `tool-` / `add-command` 前缀。

### 1. 连接管理工具 (4 个)

#### jdbc-list-conn
列出所有已配置的数据库连接

**参数**: 无

**返回**: 连接列表，包含连接 ID、名称、数据库类型、主机等信息

---

#### jdbc-add-conn
添加新的数据库连接配置

**参数**:
- `name` (必需) - 连接名称
- `databaseType` (必需) - 数据库类型 (MYSQL, POSTGRESQL, ORACLE, SQLSERVER, H2, SQLITE, MARIADB, KINGBASE, DM, CUSTOM)
- `database` (必需) - 数据库名（H2/SQLITE 为数据库/文件名）
- `host` (按类型必需) - 主机地址（MYSQL/POSTGRESQL/ORACLE/SQLSERVER/MARIADB/KINGBASE/DM 必填；H2/SQLITE 忽略）
- `port` (按类型必需) - 端口号（同上）
- `username` (可选) - 用户名
- `password` (可选) - 密码
- `driverJarPath` (可选) - JDBC 驱动 jar 路径
- `customDriverClass` (可选) - 自定义驱动类名
- `customJdbcUrl` (CUSTOM 必需) - 自定义 JDBC URL

**返回**: 新创建的连接配置，包含自动生成的连接 ID

---

#### jdbc-edit-conn
编辑现有数据库连接配置

**参数**:
- `id` (必需) - 连接 ID
- 其他参数同 `jdbc-add-conn`（可选更新）

**返回**: 更新后的连接配置

---

#### jdbc-del-conn
删除数据库连接

**参数**:
- `id` (必需) - 要删除的连接 ID

**返回**: 删除确认信息

---

### 2. 元数据查询工具 (5 个)

#### jdbc-list-db
列出指定连接的所有数据库/目录

**参数**:
- `connectionId` (必需) - 连接 ID

**返回**: 数据库/目录列表

---

#### jdbc-list-schema
列出指定连接的所有 Schema

**参数**:
- `connectionId` (必需) - 连接 ID

**返回**: Schema 列表

---

#### jdbc-list-tables
列出指定连接的所有表

**参数**:
- `connectionId` (必需) - 连接 ID
- `schemaPattern` (可选) - Schema 模式（支持通配符）
- `tableNamePattern` (可选) - 表名模式（支持通配符）

**返回**: 表列表，包含表名、类型、注释等信息

---

#### jdbc-list-views
列出指定连接的所有视图

**参数**:
- `connectionId` (必需) - 连接 ID
- `schemaPattern` (可选) - Schema 模式（支持通配符）

**返回**: 视图列表

---

#### jdbc-get-table-ddl
生成指定表的 CREATE TABLE DDL

**参数**:
- `connectionId` (必需) - 连接 ID
- `tableName` (必需) - 表名
- `owner` (可选) - Schema/所有者（默认当前 Schema）
- `timeoutSeconds` (可选) - 元数据查询超时秒数（默认 180，上限 1800）

**返回**: 建表 DDL 语句

---

### 3. SQL 执行工具 (3 个)

#### jdbc-query
执行 SQL 查询（SELECT 语句）

**参数**:
- `connectionId` (必需，使用事务时可省略) - 连接 ID
- `sql` (必需) - SQL 查询语句
- `params` (可选) - 参数值数组，与 SQL 中的 `?` 占位符一一对应（参数化查询）
- `transactionId` (可选) - 活动事务 ID，在事务内执行查询
- `maxRows` (可选) - 最大返回行数（默认 100，上限 10000）
- `timeoutSeconds` (可选) - 查询超时秒数（默认 180，上限 1800）

**返回**: 查询结果集，包含列信息和数据行

**示例**:
```json
{
  "name": "jdbc-query",
  "arguments": {
    "connectionId": "your-connection-id",
    "sql": "SELECT * FROM users WHERE age > ? LIMIT ?",
    "params": [18, 100]
  }
}
```

```json
// 返回
{
  "columns": ["id", "name", "age"],
  "rows": [
    {"id": 1, "name": "Alice", "age": 25},
    {"id": 2, "name": "Bob", "age": 30}
  ],
  "rowCount": 2
}
```

---

#### jdbc-exec
执行 SQL 语句（INSERT、UPDATE、DELETE、DDL 等）

**参数**:
- `connectionId` (必需，使用事务时可省略) - 连接 ID
- `sql` (必需) - SQL 语句
- `params` (可选) - 参数值数组，与 SQL 中的 `?` 占位符一一对应
- `transactionId` (可选) - 活动事务 ID，在事务内执行语句
- `timeoutSeconds` (可选) - 语句超时秒数（默认 180，上限 1800）

**返回**: 影响的行数

**示例**:
```json
{
  "name": "jdbc-exec",
  "arguments": {
    "connectionId": "your-connection-id",
    "sql": "UPDATE users SET status = ? WHERE id = ?",
    "params": ["active", 1]
  }
}
```

```json
// 返回
{
  "affectedRows": 3,
  "message": "SQL executed successfully"
}
```

---

#### jdbc-exec-batch
批量执行多条 SQL 语句（INSERT/UPDATE/DELETE/DDL）

**说明**:
- 所有语句**默认在同一事务**中执行：全部成功后统一提交，任一失败则回滚
- 设置 `continueOnError=true` 可忽略失败语句继续执行（此时失败不会触发回滚）
- 传入 `transactionId` 时语句在外部事务中执行且不自动提交

**参数**:
- `connectionId` (可选) - 连接 ID（未传 `transactionId` 时必填）
- `statements` (必需) - SQL 语句字符串数组（数组格式，按顺序执行；空语句自动跳过）
- `transactionId` (可选) - 已有事务 ID，语句在该事务内执行且不提交
- `continueOnError` (可选) - 某条语句失败时是否继续执行后续（默认 `false`：失败即回滚并终止）
- `timeoutSeconds` (可选) - 单条语句超时秒数（默认 180，上限 1800）

**返回**: 每条语句的执行结果列表（`index`、`success`、`affectedRows`/`error`）与执行统计

**示例**:
```json
{
  "name": "jdbc-exec-batch",
  "arguments": {
    "connectionId": "your-connection-id",
    "statements": [
      "INSERT INTO t(id, name) VALUES (1, 'a')",
      "UPDATE t SET name = 'b' WHERE id = 1"
    ],
    "continueOnError": false
  }
}
```

---

### 3.5 事务管理工具 (3 个)

#### jdbc-tx-begin
开启一个事务

**参数**:
- `connectionId` (必需) - 连接 ID

**返回**: 事务 ID

**示例**:
```json
{
  "name": "jdbc-tx-begin",
  "arguments": {
    "connectionId": "your-connection-id"
  }
}
```

```json
// 返回
{
  "success": true,
  "transactionId": "a3f2e1c0-..."
}
```

#### jdbc-tx-commit
提交事务并释放连接

**参数**:
- `transactionId` (必需) - 事务 ID

#### jdbc-tx-rollback
回滚事务并释放连接

**参数**:
- `transactionId` (必需) - 事务 ID

---

### 3.6 数据导入导出工具 (2 个)

#### jdbc-export
数据导出：执行 SELECT 查询并将结果写入文件

**支持的格式**（默认按文件扩展名识别：`.sql`→inserts、`.json`→json、其他→csv）:
- `csv` - CSV 文件
- `json` - JSON 对象数组
- `inserts` - INSERT 语句文件（需提供 `tableName`，日期导出为 TIMESTAMP/DATE 字面量）

**参数**:
- `connectionId` (必需) - 连接 ID
- `sql` (必需) - SELECT 查询语句
- `filePath` (必需) - 导出文件绝对路径（父目录不存在会自动创建）
- `format` (可选) - 格式：`csv` / `json` / `inserts`，默认按扩展名识别
- `tableName` (可选) - inserts 格式必填：INSERT 语句中的目标表名
- `maxRows` (可选) - 最大导出行数（默认 100000，最大 2000000）
- `timeoutSeconds` (可选) - 查询超时秒数（默认 180，上限 1800）

**说明**: 二进制列（BLOB/BINARY 等）在 csv/json 中导出为 Base64 字符串，inserts 格式不支持二进制列。

---

#### jdbc-import
数据导入：将文件中的数据进行批量导入，整体一个事务（全部成功才提交）

**支持的格式**（默认按扩展名与内容自动识别）:
- `inserts` - 导出的 INSERT 语句文件（自动解析 VALUES 元组并重写目标表名）
- `csv` - 首列为列名的 CSV 文件
- `json` - JSON 对象数组（表头取所有对象键的并集，缺失键填 NULL）

**参数**:
- `connectionId` (必需) - 连接 ID
- `filePath` (必需) - 待导入文件绝对路径
- `tableName` (可选) - 目标表名（inserts 格式可选，覆盖原语句中的表名；csv/json 格式必填）
- `format` (可选) - 格式：`inserts` / `csv` / `json`，默认自动识别
- `encoding` (可选) - 文件编码，默认 `UTF-8`
- `delimiter` (可选) - csv 分隔符，默认 `,`
- `hasHeader` (可选) - csv 首行是否为列名（默认 `true`）
- `columnNames` (可选) - csv/json 列名覆盖（csv 无表头时必填）
- `batchRows` (可选) - 批量提交大小（默认 500）
- `truncateFirst` (可选) - 导入前是否先清空目标表（默认 `false`；在事务内用 DELETE，可随整体回滚）
- `timeoutSeconds` (可选) - 单条语句超时秒数（默认 180，上限 1800）

**说明**: 整体一个事务，全部成功后提交，任一失败回滚。

---

### 4. 通用工具 (7 个)

#### java-exec
动态执行 Java 代码（在所有模式下可用）

⚠️ **注意**: 此工具不是开发工具，在生产模式和开发模式下都可用

**参数**:
- `code` (必需) - Java 代码，可以是代码片段或完整类
- `params` (可选) - 传递给代码的参数 Map，代码中形参名为 `params`
- `jarPaths` (可选) - 外部 JAR 文件绝对路径列表；编译时加入 `-classpath`，运行时通过独立 `URLClassLoader` 加载（线程级隔离，不影响主进程）

**返回**: `success`、`result`（修改后的 params Map）、`returnValue`（execute 方法返回值，非空时出现）

**示例请求 1 - 简单计算**:
```json
{
  "code": "int sum = 0;\nfor (int i = 1; i <= 100; i++) {\n    sum += i;\n}\nreturn sum;"
}
```

**示例请求 2 - 使用参数**:
```json
{
  "code": "int x = (int) params.get(\"x\");\nint y = (int) params.get(\"y\");\nparams.put(\"result\", x * y);\nreturn x * y;",
  "params": {
    "x": 10,
    "y": 20
  }
}
```

**示例返回（成功）**:
```json
{
  "success": true,
  "result": {"x": 10, "y": 20, "result": 200},
  "returnValue": 200
}
```

**示例返回（失败）**:
```json
{
  "success": false,
  "error": "Compilation failed: ...",
  "errorType": "Exception"
}
```

**特性**:
- 真实 Java 语法（lambda/泛型/try-with-resources/var/反射/注解等）
- 统一基于 JDK `javax.tools.JavaCompiler` 实时编译, 不依赖任何模板引擎
- 传 `jarPaths` 时新建独立 URLClassLoader 加载外部 JAR, 严格线程级隔离
- 编译结果按 hash 缓存, 高频调用性能良好
- 支持传递参数; Map 修改与 execute 返回值分别返回

**常见用途**:
- 快速验证算法逻辑
- 数据转换和处理
- 原型开发和测试
- 临时调试辅助
- 调用外部类库完成一次性计算
- JDBC 能力补充: 在 MCP 工具边界外做临时数据加工

**安全说明**:
- ⚠️ 代码在服务器进程中执行, 无沙箱
- ⚠️ 外部 JAR 加载与主进程类加载器隔离, 但执行体仍在 JVM 内运行
- ⚠️ 不要执行不可信的代码
- ⚠️ 在生产环境中使用时需要额外小心
- ⚠️ 需要 JDK 运行环境 (JRE 不支持 `javax.tools.JavaCompiler`)

---

#### add-command
注册用户自定义命令为新的 MCP 工具

**参数**:
- `name` (必需) - 命令名，将成为 MCP 工具名（不能与现有工具冲突）
- `javaCode` (必需) - 命令执行的 Java 代码（代码片段或完整类）
- `description` (可选) - 命令描述
- `jarPaths` (可选) - 外部 JAR 文件绝对路径列表；编译与执行该命令时加载外部依赖（同 `java-exec` 的 `jarPaths` 行为）
- `parameters` (可选) - 参数定义：`{ 参数名: { type, description, required, defaultValue } }`

**返回**: 注册确认信息，含命令 ID 与名称

**说明**:
- 命令是**通用能力**，不依赖特定数据库连接；需要连接 ID 时由调用方作为普通参数传入
- 命令保存到 `commands.yaml`，注册后**立即生效**（热重载），可通过 `tool-desc` 查询其描述与使用说明
- **与 `java-exec` 的分工**: `java-exec` 用于即席执行一段代码（每次传 `code`）；`add-command` 用于把常用逻辑固化为具名、可复用的工具（只需传自定义参数）。两者均基于 JDK 实时编译，**完整支持外部 JAR 加载**（传 `jarPaths` 时）。

---

#### edit-command
编辑已注册的自定义命令（仅更新传入的字段），保存后立即刷新对应的 MCP 工具

**参数**:
- `name` (必需) - 命令名（必须已存在，不可重命名）
- `description` (可选) - 新的命令描述
- `javaCode` (可选) - 新的命令 Java 代码（代码片段或完整类）
- `jarPaths` (可选) - 新的外部 JAR 路径列表（整体替换现有列表）
- `parameters` (可选) - 新的参数定义（整体替换现有定义）：`{ 参数名: { type, description, required, defaultValue } }`

**返回**: 更新确认信息，含命令 ID 与名称

---

#### delete-command
删除自定义命令，删除后立即移除对应的 MCP 工具

**参数**:
- `name` (必需) - 要删除的命令名

**返回**: 删除确认信息

---

#### get-command
查看自定义命令的完整配置，包括 Java 代码、jarPaths 和参数定义

**参数**:
- `name` (必需) - 命令名

**返回**: 命令完整配置（id、name、description、javaCode、jarPaths、parameters、createdAt、updatedAt）

---

#### list-commands
列出所有已注册的自定义命令（按创建时间排序）

**参数**: 无

**返回**: 命令列表，包含 name、description、jarPaths、参数名列表、createdAt、updatedAt

---

#### tool-desc
查询指定工具的描述与使用说明（inputSchema）

**参数**:
- `toolName` (必需) - 工具名称

**返回**: 工具的 `name`、`description`、完整 `inputSchema`

**说明**: `tools/list` 只返回工具名与 inputSchema，需要工具描述时通过本工具按需获取。

---

### 5. 开发模式工具 (6 个)

#### dev_get_file
读取项目源码文件内容

**参数**:
- `path` (必需) - 源码相对路径（相对 `src/`，如 `main/java/org/acme/Foo.java`）

**返回**: 文件内容、大小和行数

**示例返回**:
```json
{
  "success": true,
  "path": "main/java/org/acme/jdbc/pool/ConnectionPoolManager.java",
  "content": "package org.acme.jdbc.pool;\n\nimport...",
  "size": 3421,
  "lines": 127
}
```

**安全限制**:
- 仅允许读取 `src/` 下的源码文件（拒绝绝对路径与路径穿越）
- 只能读取普通文件

---

#### dev_ls
显示项目源码文件树结构

**参数**:
- `path` (可选) - 源码相对路径，默认为 `src/`
- `maxDepth` (可选) - 最大遍历深度，默认为 6

**返回**: 文件树字符串表示

**示例输出**:
```
main/
├── java/
│   ├── org/acme/jdbc/
│   │   ├── loader/
│   │   ├── model/
│   │   ├── pool/
│   │   ├── security/
│   │   └── storage/
│   └── org/acme/mcp/
│       ├── handler/
│       ├── model/
│       └── service/
└── resources/
```

---

#### dev_edit
在线编辑项目源码文件

**参数**:
- `path` (必需) - 源码相对路径（相对 `src/`）
- `content` (必需) - 新的文件内容

**返回**: 编辑结果，包含文件大小变化信息

**特性**:
- 修改后 Quarkus 自动热重载
- 只能编辑已存在的文件
- 仅允许 `src/` 下源码（含路径安全检查）

---

#### dev_add
添加新文件到项目源码

**参数**:
- `path` (必需) - 新文件的源码相对路径（相对 `src/`）
- `content` (可选) - 文件内容，默认为空

**返回**: 创建结果信息

**特性**:
- 自动创建父目录
- 不能覆盖已存在的文件
- 创建后 Quarkus 自动重载

---

#### dev_del
删除项目源码文件

**参数**:
- `path` (必需) - 要删除的文件源码相对路径（相对 `src/`）

**返回**: 删除确认信息

**安全限制**:
- 仅允许删除 `src/` 下的源码文件
- 只能删除普通文件，不能删除目录

---

#### dev_restart
触发 Quarkus 热重启

**参数**: 无

**返回**: 重启触发确认信息

**工作原理**:
- 通过更新文件修改时间触发 Quarkus 文件监控
- 优先使用 `application.properties`
- 通常不需要手动调用，文件修改会自动触发

---

## 工具总数

- **连接管理**: 4 个工具（所有模式可用）
- **元数据查询**: 5 个工具（所有模式可用）
- **SQL 执行**: 3 个工具（所有模式可用）
- **数据导入导出**: 2 个工具（所有模式可用）
- **事务管理**: 3 个工具（所有模式可用）
- **通用工具**: 7 个工具 `java-exec`、`add-command`、`edit-command`、`delete-command`、`get-command`、`list-commands`、`tool-desc`（所有模式可用）
- **开发辅助**: 6 个工具（仅开发模式可用）
- **总计**: 30 个工具

> 💡 **说明**: 
> - **源码启动**（`quarkus:dev` / `start.*`）自动检测为开发模式，注册全部 30 个工具（含 `dev_*`）
> - **jar 启动**（`java -jar` / `start.*` 生产模式）自动检测为生产模式，注册 24 个基础工具（不含 `dev_*`）

## 工具注册机制

所有工具通过 CDI 自动注册：

1. 每个工具实现 `McpToolHandler` 接口
2. 使用 `@ApplicationScoped` 注解标记
3. McpService 通过 CDI `Instance<McpToolHandler>` 自动发现
4. 启动时自动注册到工具映射表

## 使用流程

### 典型工作流程

```
1. 添加连接
   jdbc-add-conn → 返回连接 ID

2. 浏览数据库
   jdbc-list-db → 选择数据库
   jdbc-list-schema → 选择 Schema
   jdbc-list-tables → 查看表列表

3. 执行 SQL
   jdbc-query → 查询数据
   jdbc-exec → 修改数据

4. 发现问题时（可选）
   dev_ls → 定位代码文件
   dev_edit → 修复代码
   自动重载 → 问题修复
```

### AI 自主修复流程

```
问题发生 → dev_ls 定位 → dev_edit 修复 → 自动重载 → 验证修复
```

## 安全特性

### 数据安全
- 密码使用 Jasypt 加密存储
- 连接配置持久化到 YAML 文件
- 支持自定义加密密钥

### 代码安全
- dev 工具仅允许读写 `src/` 下的源码文件
- 拒绝绝对路径与路径穿越（`..`）
- 文件类型限制（只能删除普通文件）

### 连接安全
- 基于 Alibaba Druid 的连接池管理
- 连接健康检查
- 自动连接回收

## 技术栈

- **框架**: Quarkus 3.17.4
- **连接池**: Alibaba Druid 1.2.24
- **加密**: Jasypt 1.9.3
- **JSON**: Jackson
- **协议**: JSON-RPC 2.0
- **通信**: 标准输入/输出流

## 扩展开发

### 添加新工具

1. 创建新的 Handler 类：
```java
@ApplicationScoped
public class MyToolHandler implements McpToolHandler {
    @Override
    public String getToolName() {
        return "my-tool";
    }
    
    @Override
    public String getDescription() {
        return "My custom tool";
    }
    
    @Override
    public Map<String, Object> getInputSchema() {
        // 定义参数 schema
    }
    
    @Override
    public Object execute(Map<String, Object> params) {
        // 实现工具逻辑
    }
}
```

2. 放置在 `org.acme.mcp.handler` 包
3. Quarkus 自动扫描并注册
4. 无需修改 McpService

### 工具分类建议

- **数据库相关**: `jdbc-` 前缀（如 `jdbc-query`, `jdbc-add-conn`, `jdbc-tx-begin`）
- **开发相关**: `dev_*` 前缀（如 `dev_edit`, `dev_ls`）
- **通用能力**: `java-` / `tool-` 前缀与 `add-command`（如 `java-exec`, `tool-desc`）

## 相关文档

- [README.md](README.md) - 项目概述和快速开始
- [MCP 协议规范](https://modelcontextprotocol.io/) - Model Context Protocol 官方文档

---

# 开发工具指南

开发工具允许 AI 模型在运行时发现问题并直接修改代码，利用 Quarkus 的热重载功能实现无缝的代码修复流程（详细参数见上文第 5 节）。

## 使用场景

### 场景 1: 发现并修复 Bug
1. AI 模型通过 MCP 工具执行查询时发现异常
2. 使用 `dev_ls` 浏览项目结构
3. 使用 `dev_get_file` 读取相关代码文件
4. 使用 `dev_edit` 修复 Bug
5. Quarkus 自动重载（1-3 秒）
6. 重新执行查询验证修复

### 场景 2: 添加新功能
1. AI 模型识别需要新的工具类或处理器
2. 使用 `dev_add` 创建新文件
3. 使用 `dev_edit` 完善代码
4. Quarkus 自动重载，新功能立即可用

### 场景 3: 代码重构
1. 使用 `dev_ls` 查看项目结构
2. 使用 `dev_edit` 重构多个文件
3. 使用 `dev_del` 删除废弃代码
4. 使用 `dev_restart` 确保所有变更生效

### 场景 4: 快速验证逻辑
1. 需要验证某个算法或数据处理逻辑
2. 使用 `java-exec` 直接执行代码片段，立即获得结果
3. 验证通过后再写入正式代码

## Quarkus 热重载机制

Quarkus Dev 模式会监控以下变化：
- Java 源代码 (`src/main/java`)
- 资源文件 (`src/main/resources`)
- 配置文件 (`application.properties`)

**重载时机**：文件保存后自动触发，通常 1-3 秒，只重新编译变更的类。

**不会触发重载**：`target` 目录、`.git` 等忽略目录、非源代码文件。

**dev_restart 工作原理**：通过 "touch" 文件（更新修改时间）触发 Quarkus 文件监控，优先使用 `application.properties`，其次使用任意 `.java` 文件。

## 注意事项与最佳实践

- **路径限制**: dev 工具仅允许操作 `src/` 下的源码文件（路径相对 `src/`），拒绝绝对路径与 `..` 路径穿越
- **文件保护**: 源码区之外的文件（pom.xml、.git、配置等）不受 dev 工具影响
- **小步修改**: 每次修改一个文件，观察热重载结果
- **验证修复**: 修改后立即测试相关功能
- **保持备份**: 重要修改前考虑代码版本管理
- **清理临时**: 使用 `dev_del` 清理测试文件

## 与标准 MCP 工具集成

```
数据库工具 → 发现问题 → 开发工具修复 → 数据库工具验证
    ↓                        ↓
  jdbc-query             dev_edit
  jdbc-exec              dev_add
  jdbc-list-tables       dev_ls
```

这种集成使 AI 模型能够自主诊断和修复运行时问题、优化 SQL 执行逻辑、添加缺失的错误处理、改进连接池配置。

## 当前限制与未来改进

**当前限制**：只支持单文件编辑；不提供撤销/回滚；不支持重命名/移动；不集成版本控制。

**未来可能的增强**：文件差异预览、代码格式化、编译错误反馈、事务性修改、文件历史记录。

---

# 启动脚本指南

## 脚本列表

启动方式统一由项目根的 `start.sh`（Linux/Mac）/ `start.bat`（Windows）承载，通过环境变量 `BACKEND_LAUNCH_MODE` 选择两种子模式：

| 脚本 | 平台 | 说明 |
|------|------|------|
| `start.sh` | Linux/Mac | 源码 / jar 两种启动模式，用 `BACKEND_LAUNCH_MODE` 切换 |
| `start.bat` | Windows | 同上 |

| `BACKEND_LAUNCH_MODE` | 启动方式 | 说明 |
|------|------|------|
| `source` | `mvn quarkus:dev` | 开发模式：热重载，注册全部 30 个工具（含 `dev_*`）；仅限本地终端调试 |
| `jar`（默认） | `java -jar target/quarkus-app/quarkus-run.jar` | 生产模式：注册 24 个工具（不含 `dev_*`），MCP 客户端的标准选择 |

## 快速开始

**开发模式**（本地终端调试，支持热重载；已自动禁用 Quarkus 遥测问卷）
```bash
# Linux/Mac
chmod +x start.sh
BACKEND_LAUNCH_MODE=source ./start.sh

# Windows
set BACKEND_LAUNCH_MODE=source && start.bat
```

**生产模式**（MCP 客户端推荐，需要先打包）
```bash
# 1. 打包（使用系统 Maven，本项目不依赖 Maven Wrapper）
mvn package

# 2. 启动
# Linux/Mac
chmod +x start.sh
BACKEND_LAUNCH_MODE=jar ./start.sh

# Windows
set BACKEND_LAUNCH_MODE=jar && start.bat
```

## 脚本自动检查

1. **Java 版本检查**：要求 JDK 17+，版本不符合时给出明确提示
2. **JAR 文件检查**（仅生产模式）：检查 `target/quarkus-app/quarkus-run.jar` 是否存在，未找到时提示先打包
3. **环境变量设置**：未设置时自动使用默认值

| 环境变量 | 默认值 | 说明 |
|---------|--------|------|
| `JAVA_HOME` | 未设置时使用 PATH 中的 java | JDK 安装目录，设置后脚本优先使用 `%JAVA_HOME%\bin\java` |
| `JDBC_ENCRYPTION_KEY` |  | 密码加密密钥 |
| `JDBC_STORAGE_PATH` | `./data/connections.yaml` | 连接配置文件路径 |

自定义环境变量：
```bash
# Linux/Mac
export JDBC_ENCRYPTION_KEY="your-secret-key-here"
export JDBC_STORAGE_PATH="/path/to/connections.yaml"
./start.sh

# Windows
set JDBC_ENCRYPTION_KEY=your-secret-key-here
set JDBC_STORAGE_PATH=C:\path\to\connections.yaml
start.bat
```

## 常见问题

### Q1: Java 版本错误
```
❌ 错误: Java 版本过低
当前版本: 8
需要版本: 17 或更高
```
解决方案：安装 JDK 17 或更高版本，更新 PATH 环境变量。

### Q2: JAR 文件未找到（生产模式）
```
❌ 错误: 未找到已打包的 JAR 文件
路径: target/quarkus-app/quarkus-run.jar
```
解决方案：先执行 `mvn package` 再启动。

### Q3: 如何确认当前工具集合
**方法 1**：查看启动日志，会输出 `Dev tools enabled: true/false`（基于启动方式自动检测）。
**方法 2**：调用 `tools/list`，源码启动返回 27 个工具，jar 启动返回 21 个。

## 高级用法

**自定义 JVM 参数**
```bash
# 开发模式
export MAVEN_OPTS="-Xmx2g -Xms512m"
./start.sh

# 生产模式
java -Xmx1g -Xms256m -jar target/quarkus-app/quarkus-run.jar
```

**后台运行（Linux/Mac）**
```bash
nohup ./start.sh > logs/dev.log 2>&1 &
nohup ./start-prod.sh > logs/prod.log 2>&1 &
```

## Docker 部署

```dockerfile
FROM eclipse-temurin:17-jre-alpine

WORKDIR /app

COPY target/quarkus-app/ /app/

ENV JDBC_ENCRYPTION_KEY="change-me-in-production"
ENV JDBC_STORAGE_PATH="/app/data/connections.yaml"

VOLUME /app/data

CMD ["java", "-jar", "quarkus-run.jar"]
```

```bash
# 1. 打包（使用系统 Maven，本项目不依赖 Maven Wrapper）
mvn package

# 2. 构建镜像
docker build -t javatool-mcp-server .

# 3. 运行容器
docker run -d \
  -v $(pwd)/data:/app/data \
  -e JDBC_ENCRYPTION_KEY=your-secret-key \
  --name javatool-mcp \
  javatool-mcp-server
```

## 最佳实践与安全建议

1. **本地调试**：使用 `BACKEND_LAUNCH_MODE=source` 启动 `start.sh` / `start.bat` 在终端中调试，支持热重载
2. **接入 MCP 客户端**：使用 `BACKEND_LAUNCH_MODE=jar` 的生产模式
3. **安全配置**：生产环境必须设置自定义的 `JDBC_ENCRYPTION_KEY`
4. **数据持久化**：确保 `JDBC_STORAGE_PATH` 指向持久化存储
5. **日志管理**：日志输出到 stderr，生产环境建议重定向到文件
6. **文件权限**：限制 `connections.yaml` 的访问权限
7. **网络隔离**：MCP 服务器应运行在受信任的网络环境
