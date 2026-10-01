# SQLite → PostgreSQL 只读查询桥

基于 Java 17 与 Netty 4.1.118.Final 实现的网络桥接服务：把一个**只读打开**的
SQLite 文件暴露为 PostgreSQL 前后端协议 v3（wire protocol 3.0）服务，
供任意标准 PostgreSQL 客户端（psql、JDBC、libpq 等）通过 TCP 执行单条
`SELECT` / `WITH` 查询。服务只监听 `127.0.0.1`，不需要密码，拒绝 SSL，
且从不会修改源数据库文件。

## 构建

本项目使用项目内独立的本地 Maven 仓库 `.m2/repository`（已由
`.mvn/maven.config` 配置，无需联网）。

```sh
mvn test       # 编译并运行 22 个测试
mvn package    # 编译、测试并打包 target/sqlite-postgres-bridge-1.0-SNAPSHOT.jar
```

## 快速开始

生成自带的示例 SQLite 库并启动服务（端口可任意指定，示例用 55432）：

```sh
mkdir -p demo
mvn -q -o compile
mvn -q exec:java -Dexec.mainClass=com.example.bridge.demo.SampleDatabase -Dexec.args=demo/demo.db
mvn exec:java -Dexec.args="demo/demo.db 55432"
```

或直接用 classpath 运行：

```sh
CP="target/classes:$(mvn -q dependency:build-classpath -Dmdep.outputFile=/dev/stdout)"
java -cp "$CP" com.example.bridge.demo.SampleDatabase demo/demo.db
java -cp "$CP" com.example.bridge.Main demo/demo.db 55432
```

启动成功会打印：

```
SQLite-PostgreSQL read-only bridge listening on 127.0.0.1:55432
Database: /abs/path/demo/demo.db
```

### 用真实客户端连接

JDBC URL（务必关闭 SSL；建议使用 simple 协议）：

```
jdbc:postgresql://127.0.0.1:55432/main?sslmode=disable&preferQueryMode=simple
```

用户名/密码任意（服务端无密码认证，直接回 `AuthenticationOk`）。

内置的真实 pgjdbc 演示客户端会依次演示：连接+聚合 JOIN 查询、
UTF-8/NULL/空串/BLOB 十六进制、写操作被拒后错误恢复、CancelRequest 中断长查询
以及取消后连接仍可继续查询：

```sh
mvn -q exec:java -Dexec.mainClass=com.example.bridge.demo.DemoClient -Dexec.args=55432
```

psql / libpq 示例：

```sh
psql "host=127.0.0.1 port=55432 dbname=main sslmode=disable user=sa"
sa=> SELECT name, COUNT(*) FROM products JOIN orders ON product_id = products.id GROUP BY name;
```

## 协议行为

- **启动握手**：识别启动包（协议号 `196608`），回 `AuthenticationOk`、
  一组必要的 `ParameterStatus`（`server_version`、`client_encoding`、
  `DateStyle`、`TimeZone`、`integer_datetimes` 等）、`BackendKeyData`
  （PID + 随机取消密钥）和 `ReadyForQuery(I)`。
- **SSL 探测**：客户端先发 `SSLRequest`（`80877103`）时回单字节 `N`，
  随后同一 TCP 连接上正常进行启动握手。
- **无密码认证**：不发送认证质询，直接 `AuthenticationOk`。
- **正常退出**：收到 `Terminate('X')` 立即关闭连接并释放该连接的全部资源。
- **半包 / 粘包**：基于 Netty `ByteToMessageDecoder` 的累积解码，
  自动处理分片与合并；启动阶段与常规阶段状态分离，
  SSL/Cancel 探测不会错误推进帧状态。
- **非法长度**：启动包长度小于 8 或超过 1 MiB、常规消息长度小于 4 或
  超过 16 MiB 时回 `ErrorResponse(SQLSTATE 08P01)` 并关闭连接。
- **扩展协议**：`Parse/Bind/Execute/Describe/Sync/Flush/Close` 等一律回
  `0A000` 错误并保持连接可用；只支持简单 Query 协议。

## 查询语义

- 仅接受**单条** `SELECT` 或 `WITH ... SELECT`，允许连接（JOIN）、
  聚合、子查询、递归 CTE、集合运算。
- 在**执行之前**完成判定（绝不“先执行再判断”）：SQLite 词法分析拒绝
  多语句（未加引号的 `;` 后还有内容）与非查询首关键字（`INSERT/UPDATE/
  DELETE/REPLACE/CREATE/DROP/ALTER/.../PRAGMA/ATTACH/DETACH/BEGIN/COMMIT/
  ROLLBACK/...`），JSqlParser 再确认整条语句（含所有 CTE）是只读 SELECT。
- 数据库以 `?open_mode=1` 只读 URI 打开，并额外执行 `PRAGMA
  query_only=ON` 纵深防御；源文件不会被创建 WAL/SHM 或改写。
- 结果依次发送：`RowDescription` → 若干 `DataRow` → `CommandComplete("SELECT N")`
  → `ReadyForQuery(I)`；空（全空白/纯注释）查询回 `EmptyQueryResponse`。
- 所有列统一声明为 **text（OID 25）**：数值按其自然文本编码；
  `BLOB`/`byte[]` 输出**小写十六进制**；`NULL` 与空字符串明确区分
  （NULL 长度域为 -1，空串是长度 0 的 UTF-8 文本）；列名原样保留，
  全部按 UTF-8 传输。
- SQL 错误回 `ErrorResponse`（42xxx 等），随后仍回 `ReadyForQuery`，
  连接继续可查询。
- 兼容真实客户端启动时的会话命令：白名单内的 `SET extra_float_digits`
  /`SET application_name` 等作为无害 no-op 返回 `CommandComplete(SET)`，
  这些语句不会发给 SQLite。

## 并发、流式与背压

- 每个客户端拥有独立的 SQLite JDBC 连接（见 `SessionRegistry`），
  互不影响；同一连接内查询通过每连接单线程执行器**严格串行**。
- 结果逐行读取、分批 flush，并与 Netty 的可写水位联动：
  下游发送缓冲达到高水位（256 KiB）时，该连接的查询线程在可写闸门上等待，
  而不是把整份结果堆在内存里；慢客户端只会阻塞自己，不影响其他连接
  （见 `BackpressureTest`）。等待有 30 秒超时，客户端断连立即解除并释放资源。
- 排队查询数有上限（默认 32），超过即暂停读取并回 `53300`，限制报文/内存占用。

## CancelRequest

- 取消请求走一条**独立连接**：启动包格式但协议号为 `80877102`，
  载荷为 `(PID, secret)`，即 `BackendKeyData` 下发的会话身份与随机密钥。
- 服务端在 `SessionRegistry` 中核对 PID 与密钥：
  - 匹配则对该会话正在执行的 SQLite `Statement.cancel()`（底层
    `sqlite3_interrupt`），查询被真正中断，原连接收到
    `ErrorResponse(SQLSTATE 57014)` 后照常 `ReadyForQuery`，后续查询可用。
  - PID 不存在或**密钥错误**完全无影响（不影响任何查询，也不报错）。
  - 取消通道处理完即关闭；取消与查询自然完成之间的竞态通过
    “每查询一个令牌、结束即摘除”处理，迟到取消不会落到下一条查询，
    也不会影响其他连接。

## 代码结构（跨文件协作）

```
com.example.bridge
├── Main.java                     # CLI：<sqlite 文件> <端口>
├── BridgeServer.java             # Netty 装配，仅 bind 127.0.0.1
├── protocol
│   ├── PgProtocol.java           # 各类后端消息编码
│   ├── FrontendMessage.java      # 解码后的前端消息（sealed）
│   └── PgFrameDecoder.java       # 半包/粘包、SSL/启动/Cancel、非法长度
├── session
│   ├── BackendKey.java           # PID + secret
│   ├── CancelToken.java          # 单条查询的取消令牌
│   ├── ClientSession.java        # 每连接只读 SQLite 连接 + 取消状态
│   └── SessionRegistry.java      # 会话注册表、密钥核对、独立连接
├── sql
│   ├── SqlGuard.java             # 执行前的只读判定（词法 + JSqlParser）
│   ├── QueryExecutor.java        # 只读、前向只读游标执行
│   └── QueryCursor.java          # 逐行读取与 text/BLOB/NULL 编码
├── transport
│   ├── PgSessionHandler.java     # 握手、串行执行、取消、错误恢复
│   └── QueryStreamer.java        # 逐行流式发送与背压
└── demo
    ├── SampleDatabase.java       # 生成示例库（JOIN/聚合/UTF-8/NULL/BLOB）
    └── DemoClient.java           # 真实 pgjdbc 端到端演示
```

## 测试

```sh
mvn test
```

共 22 个测试：

- `BridgeProtocolTest`：SSL 拒绝后握手、必要参数/BackendKeyData/Ready、
  半包（逐字节发送）与粘包（两条报文合并）、非法启动/常规长度、
  非法协议版本。
- `CancelRequestTest`：原始 socket 发送 CancelRequest，
  验证错误密钥无影响、正确密钥快速中断、返回 57014、原连接继续可用。
- `BridgeIntegrationTest`：真实 PostgreSQL JDBC 驱动端到端：
  JOIN/聚合/子查询/CTE、列名/UTF-8/NULL/空串/BLOB hex、
  写/PRAGMA/多语句被拒且连接恢复、SSL 拒绝、源文件 SHA-256 前后一致、
  pgjdbc `Statement.cancel()`、取消不影响其他连接、同连接串行。
- `BackpressureTest`：慢速不读取的客户端不阻塞另一连接获得完整结果。
- `sql/SqlGuardTest`：允许/拒绝矩阵、多语句、畸形输入。
