# 设备接入网关（device-gateway-java）

一个**只通过本机回环地址 127.0.0.1** 通信的设备接入网关。用本地 TCP 连接模拟设备的上行与下行数据，
在异常链路（粘包/半包/跨包分片/坏帧/重复/乱序/ACK 丢失/资源超限/并发/关闭）下，
帧解析、会话状态、可靠投递与资源限制仍能得到**一致且可解释**的结论。

无外部服务、无真实凭据依赖；全部行为由本地回环上的确定性测试覆盖。

---

## 1. 帧语义

### 1.1 二进制帧格式（v1）

```
偏移  长度  字段        说明
0     2    MAGIC       固定 0xA5 0x5A（大端 0xA55A），用于字节流上的帧定位与重同步
2     1    VERSION     协议版本，当前 0x01
3     1    TYPE        消息类型，见下表
4     1    FLAGS       bit0 = FLAG_RELIABLE（下行需要设备 ACK）；bit1 = FLAG_PRIORITY（需已协商 priority 能力）
5     1    RESERVED    扩展字段，非零即视为使用扩展（需已协商 header-extension 能力）
6     4    SEQ         序号（uint32，大端）
10    4    LENGTH      PAYLOAD 字节数（uint32，大端）
14    N    PAYLOAD     N = LENGTH 字节负载
14+N  2    CRC16       CRC-16/CCITT-FALSE（poly=0x1021, init=0xFFFF），覆盖此前全部字节
```

最小帧长 16 字节（无负载）。

| TYPE | 值 | 方向 | 含义 |
|---|---|---|---|
| LOGIN | 0x01 | 上行 | 登录，PAYLOAD 见 1.3 |
| LOGOUT | 0x02 | 上行 | 登出，服务端 ACK 后关闭连接 |
| HELLO | 0x03 | 上行 | 能力协商，PAYLOAD 见 1.4（仅允许在会话开头） |
| HELLO_ACK | 0x04 | 下行 | 协商成功确认，PAYLOAD = 选定版本 + 授予能力集 |
| DATA | 0x10 | 上行 | 业务数据，严格递增序号 |
| DOWNLINK | 0x20 | 下行 | 服务端下发，FLAG_RELIABLE=1 时需 ACK |
| ACK | 0x21 | 双向 | 确认。上行 ACK 确认 DOWNLINK；下行 ACK 确认 LOGIN/DATA |
| ERROR | 0x7F | 下行 | 错误帧，PAYLOAD = code(4,BE) + refSeq(4,BE) + UTF-8 reason |

### 1.2 字节流解析规则（粘包/半包/分片/坏帧）

解码器（`FrameDecoder`）维护**有界**残留缓冲，按以下不变量工作：

1. **缓冲只保留疑似帧头起的数据**。任何不在 MAGIC 上的字节被计数丢弃后，从流中重新扫描 MAGIC（重同步），
   产生 `MAGIC_MISMATCH(1001)`；找不到时保留最后 1 字节（可能是 MAGIC 高字节）。
2. **半包/跨包分片**：头没收齐等头，声明的体没收齐等体，绝不提前判定。
3. **超长声明**：`LENGTH > gateway.max-frame-length` 时立即拒绝 `FRAME_TOO_LONG(1004)`，
   且**不信任该长度**——只跳过 MAGIC 两字节后重新扫描，避免被伪造长度牵着走，内存不越界。
4. **CRC 不通过**：`CRC_MISMATCH(1005)`，同样不信任边界，按 MAGIC 重同步。
5. **版本不匹配**：`VERSION_MISMATCH(1002)`；**未知 TYPE**：`UNKNOWN_MESSAGE_TYPE(1003)`。
   两者是在 CRC 校验通过、帧边界可信后判定的，错误码可区分。
6. **非法帧不影响后续合法帧**：同一条连接上，坏帧之后的好帧照常解析（测试覆盖）。

### 1.3 LOGIN 负载

```
deviceIdLen(2,BE) UTF8(deviceId) secretLen(2,BE) UTF8(secret)
```

`secret` 只在内存中短暂存在。**日志与 ERROR 帧中绝不出现凭证原文**（有专门断言）。

### 1.4 HELLO / HELLO_ACK 负载（能力协商）

```
HELLO（上行）:      minVersion(1) maxVersion(1) capCount(1) [capLen(1) capBytes(UTF-8)]*
HELLO_ACK（下行）:  selectedVersion(1) capCount(1) [capLen(1) capBytes(UTF-8)]*
```

---

## 2. 连接建立阶段的能力协商

### 2.1 协商流程

设备连上后、进入业务之前，可以（且建议）先发一帧 `HELLO`，声明自己支持的**协议版本区间**
`[minVersion, maxVersion]` 与**可选能力项**列表。网关在双方区间内求交并校验能力项，
三种结论明确区分：

| 结论 | 表现 | 连接去向 |
|---|---|---|
| 谈成 | 回 `HELLO_ACK`：选定版本（双方区间内最高）+ 授予能力集（即设备请求集，均已确认支持） | 继续，可 LOGIN |
| 版本区间完全无交集 | `NEGOTIATION_VERSION_INCOMPATIBLE(1007)`，reason 带双方区间 | 错误帧排空后**结束连接** |
| 存在不识别/不支持的能力项 | `NEGOTIATION_CAPABILITY_UNSUPPORTED(1008)`，reason 列出全部问题能力项 | 错误帧排空后**结束连接** |

没谈成的连接**不会被当成正常设备继续走，也不会悄悄按默认版本硬套**：错误帧送达后连接即终止。
设备也可以选择不协商直接 LOGIN（兼容旧行为），此时会话按**默认约束**运行：版本 1、空能力集。

### 2.2 协商结果是会话期硬约束

- 后续帧的 VERSION 必须等于选定版本；帧上使用的能力（`FLAG_PRIORITY`）或扩展字段
  （RESERVED 非零）必须已在授予集合内，否则该帧被 `CAPABILITY_NOT_NEGOTIATED(1009)` 明确拒绝
  ——错误归类上属于"版本/能力对不上"。**只拒绝当前帧，连接保持**，后续合法帧照常处理。
- **协商只能发生在会话开头**：已协商过再发 HELLO、或已认证后再发 HELLO，都稳定得到
  `NEGOTIATION_NOT_ALLOWED(2008)`；已锁定的版本与能力集合中途不可更改，会话规则不变。

### 2.3 版本与能力兼容范围

- 网关版本区间：`gateway.protocol-min-version` .. `gateway.protocol-max-version`（默认 1..1）。
- 网关可授予能力：`gateway.supported-capabilities`（默认 `priority,header-extension`）：
  - `priority`：允许帧使用 `FLAG_PRIORITY` 标志位；
  - `header-extension`：允许帧头 RESERVED 字节非零（扩展字段）。
- 设备请求的能力若不在上表（不识别或已下线），整个协商按 1008 失败，不会部分授予后放行。

---

## 3. 会话与序号语义

### 3.1 会话生命周期

```
TCP 连接 → CONNECTED（未认证，禁止读写业务状态；此阶段可发一次 HELLO 协商）
        → [NEGOTIATED]（可选；协商失败则连接终止）
        → AUTHENTICATED（LOGIN 成功；此后禁止再协商）
        → CLOSED（LOGOUT / 被踢 / 重传耗尽 / 接收残留超限 / 服务关闭 / TCP 断开）
```

- 未认证连接发 DATA/ACK/LOGOUT → `NOT_AUTHENTICATED(2001)`，**触达不到任何 DeviceState**。
- **同连接重复登录** → `ALREADY_AUTHENTICATED(3004)`。
- **跨连接重复登录**：新连接取代旧连接，旧连接以 `SESSION_CONFLICT(2002)` 被踢；
  若旧连接其实已死（TCP 断了但尚未清理），判为“重连替换陈旧映射”，不算冲突。
- 设备状态（`DeviceState`）按 `deviceId` 存储，**重连后复用**，序号结论跨连接一致。

### 3.2 上行序号（确定性，绝不静默）

序号必须 **严格 +1 递增**，判定与状态更新在同一把锁内原子完成：

| 情形 | 结论 | 错误码 | 是否生效 |
|---|---|---|---|
| `seq == lastApplied+1` | APPLIED，回 ACK | — | 是（唯一生效路径） |
| 回退，且序号在去重窗口内且确实生效过 | DUPLICATE（幂等重放） | 2004 | 否，不再次生效 |
| 回退，但已滑出窗口 | EXPIRED，明确拒绝 | 2005 | 否 |
| 跳号（`seq > lastApplied+1`） | OUT_OF_ORDER，明确拒绝 | 2005 | 否 |

窗口大小 `gateway.seq-window`（默认 32，测试用 8）。被拒绝的消息有明确错误帧，**不是静默丢弃**。

### 3.3 下行可靠投递规则

- 服务端下发 `DOWNLINK`（FLAG_RELIABLE=1）后登记待确认项并启动 ACK 超时定时器。
- 设备回 `ACK seq`：
  - 首次确认 → `ACKED`，取消定时器；序号进入**有界已确认窗口**（64）。
  - 再次确认同一序号 → `DUPLICATE_ACK` → `DUPLICATE_MESSAGE(2004)`，确定性忽略。
  - 确认从未发出/已滑出窗口的序号 → `UNKNOWN_SEQ` → `SEQ_OUT_OF_ORDER(2005)`。
- **ACK 丢失**：超时后重传；**总发送次数 = 1 + gateway.max-retries**（默认 4 次），次数有硬上限。
- 达到上限仍未确认 → 明确**终止会话** `RETRY_EXHAUSTED(2007)`，记录原因（device/conn/seq/attempts），
  指标 `sessionsTerminatedByRetry` +1。
- 定时器抽象 `TaskScheduler`：生产用守护线程池，测试用 `ManualTaskScheduler`（时间只在 `advance()` 时前进，
  无真实 sleep，时序确定可复现）。

---

## 4. 资源上限与背压

| 资源 | 上限配置 | 超限策略 |
|---|---|---|
| 并发连接数 | `gateway.max-connections`（默认 64） | Semaphore；超限连接收到 `TOO_MANY_CONNECTIONS(4001)` 后关闭，服务不中断 |
| 每连接待发送帧 | `gateway.max-outbound-per-session`（默认 256） | 有界队列满即拒绝入队，回 `OUTBOUND_BUFFER_FULL(4002)` 降级，不阻塞、不无界排队 |
| 单帧长度 | `gateway.max-frame-length`（默认 8192） | 超长声明立即拒绝重同步；单次 read chunk ≤ 上限 |
| 每连接接收残留 | `gateway.max-receive-buffer-bytes`（默认 16384） | 未拼成完整帧的残留字节硬上限（与单帧上限独立）；超限回 `RECEIVE_BUFFER_FULL(4003)` 并结束该连接，其他连接不受影响 |
| 线程 | 每连接 1 读 + 1 写（守护线程），accept 1 | 随连接释放，总数 ≤ 2×max-connections |

帧长受上限约束，因此“帧数 × 最大帧长”即待发送字节的硬上界；接收方向每连接残留另有
`max-receive-buffer-bytes` 硬上限，设备只发半包长期占连接时内存也不会无限上涨——超限即按
资源耗尽结束该连接。上限值与触发次数可见于启动日志、WARN 日志（带 conn/device、残留字节数
与上限值）与 `GET /admin/limits`、`GET /admin/metrics`（`receiveBufferOverflows` 计数）。
不存在无界内存占用。
同一连接只有一个写线程，帧严格串行，**不会串帧**；共享状态（会话表、设备状态、待确认表）
均在锁/原子结构内更新，**不会半更新或串号**。

### 优雅关闭

`stop()`：停止 accept → 静默期让在途处理完成 → 对每条活动连接**两阶段关闭**
（`SERVER_SHUTTING_DOWN(4004)` 通知帧先入待发队列，再半关闭读端唤醒读循环）→
读循环清理时排空待发帧、取消全部重传定时器、注销会话 → 最后关 socket；超过
`gateway.shutdown-timeout-ms` 才强制关闭。已提交的业务状态不随关闭丢失。

---

## 5. 错误码归类（可观测）

| 类别 | 区间 | 代表码 |
|---|---|---|
| 协议错误 | 1xxx | 1001 MAGIC 不匹配（重同步）/ 1002 版本不匹配 / 1003 未知类型 / 1004 帧超长 / 1005 CRC 失败 / 1006 负载畸形 / 1007 协商版本无交集 / 1008 能力不识别或不支持 / 1009 版本或能力对不上（用了没谈拢的能力/扩展字段） |
| 会话/序号错误 | 2xxx | 2001 未认证 / 2002 会话冲突 / 2003 会话已关闭 / 2004 重复消息或重复 ACK / 2005 乱序或过期 / 2006 序号溢出 / 2007 重传耗尽 / 2008 当前状态不允许协商 |
| 认证失败 | 3xxx | 3001 凭证缺失 / 3002 凭证无效（含设备不存在，不泄露注册情况）/ 3003 已失效 / 3004 重复登录 |
| 资源耗尽 | 4xxx | 4001 连接数超限 / 4002 待发缓冲满 / 4003 接收缓冲满 / 4004 服务关闭中 |

每条 WARN/拒绝日志都带 **会话标识（conn/device）、消息标识（seq/type）与判定依据**，
并按错误码计入 `GatewayMetrics`（`GET /admin/metrics` 可看分类计数快照）。

---

## 6. 可配置项（`src/main/resources/application.properties`，前缀 `gateway.`）

| 键 | 默认值 | 含义 |
|---|---|---|
| `port` | 19000 | 监听端口（仅绑定 127.0.0.1；测试用 0 取随机端口） |
| `max-connections` | 64 | 最大并发连接数 |
| `max-frame-length` | 8192 | 单帧 payload 上限（字节） |
| `max-receive-buffer-bytes` | 16384 | 每连接接收残留硬上限（字节），与单帧上限独立 |
| `protocol-min-version` / `protocol-max-version` | 1 / 1 | 网关支持的协议版本区间（协商求交） |
| `supported-capabilities` | priority,header-extension | 网关可授予的能力项 |
| `max-outbound-per-session` | 256 | 每连接待发送帧队列上限 |
| `ack-timeout-ms` | 3000 | 下行 ACK 超时 |
| `max-retries` | 3 | 最大重传次数（总发送 = 1 + 该值） |
| `seq-window` | 32 | 上行序号去重窗口 |
| `shutdown-quiet-period-ms` | 100 | 优雅关闭静默期 |
| `shutdown-timeout-ms` | 5000 | 优雅关闭总超时 |
| `io-worker-threads` | 4 | 定时器线程数 |

内置演示凭证：`device-001/secret-001`、`device-002/secret-002`、`device-expired/old-secret`（已过期）。

### 本机管理接口（HTTP，亦仅本机使用）

- `GET  /admin/metrics`  指标与分类错误计数快照（含 `receiveBufferOverflows`、`negotiationsSucceeded/Failed`）
- `GET  /admin/limits`   资源上限与协商参数当前值 + 接收超限触发次数
- `GET  /admin/sessions` 活动已认证会话数
- `POST /admin/downlink?deviceId=...`（body 为负载字节）投递一条可靠下行；202 接受 / 503 拒绝

---

## 7. 验证方法

```bash
mvn test
```

34 个用例全部在 **127.0.0.1 回环**与**确定性时序**下运行（重传用手动时钟，无真实等待），
不依赖外部服务或真实凭据。每个用例都用日志打印**输入字节形态与判定依据**：

| 测试类 | 覆盖重点 |
|---|---|
| `FrameDecoderTest` | 粘包、半包、逐字节跨包分片、CRC 失败重同步、超长声明、垃圾字节重同步、版本/未知类型区分 |
| `NegotiationTest` | 协商成功选定版本与能力、版本区间无交集终止连接、能力不识别终止连接、协商后越权使用能力/扩展字段被拒且连接保持、重复协商与认证后协商被拒、未协商会话默认约束 |
| `ReceiveBufferLimitTest` | 半包残留超限收到 4003 且仅该连接被结束（旁观连接不受影响）、限内残留正常拼帧 |
| `AuthTest` | 凭证缺失/无效/过期可区分、同连接与跨连接重复登录、拒绝会话不触达业务状态、错误响应不含凭证 |
| `SequenceTest` | 重复不再生效、跳号/窗口内回退/窗口外过期明确拒绝、重连跨连接状态延续 |
| `ReliabilityTest` | ACK 正常收敛、ACK 丢失超时重传至上限后终止会话、重复 ACK、未知 ACK（手动时钟） |
| `BackpressureTest` | 连接超限收到可区分错误且服务存活、待发队列满降级不无界增长 |
| `ConcurrencyTest` | 12 设备 × 20 消息并发：各自连续无串号；31 帧随机 1~7 字节分片灌入不串帧 |
| `EndToEndProtocolTest` | 真实回环 TCP 上粘包/逐字节半包、坏帧后同连接好帧继续、未知类型区分拒绝 |
| `ShutdownTest` | 已提交状态跨关闭保持、在途连接收到关闭通知、关闭后端口不再监听 |
| `ApplicationTests` | Spring 上下文装配（随机端口） |

手动跑一条回环链路的思路（亦体现在测试中）：连接 127.0.0.1:19000 →（可选）发 HELLO 收
HELLO_ACK 完成协商 → 发 LOGIN 帧收 ACK → 发严格递增 DATA 帧收 ACK →
`POST /admin/downlink` 触发可靠下行并回 ACK。
