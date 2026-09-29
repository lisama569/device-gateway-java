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
4     1    FLAGS       bit0 = FLAG_RELIABLE（下行需要设备 ACK）
5     1    RESERVED    保留，必须为 0
6     4    SEQ         序号（uint32，大端）
10    4    LENGTH      PAYLOAD 字节数（uint32，大端）
14    N    PAYLOAD     N = LENGTH 字节负载
14+N  2    CRC16       CRC-16/CCITT-FALSE（poly=0x1021, init=0xFFFF），覆盖此前全部字节
```

最小帧长 16 字节（无负载）。

| TYPE | 值 | 方向 | 含义 |
|---|---|---|---|
| NEGOTIATE | 0x03 | 上行 | 能力协商请求，连接建立后的**唯一合法首帧**（先于 LOGIN） |
| NEGOTIATE_ACK | 0x04 | 下行 | 网关选出共同版本与能力后的协商确认 |
| LOGIN | 0x01 | 上行 | 登录，PAYLOAD 见 1.4（必须在协商定稿后） |
| LOGOUT | 0x02 | 上行 | 登出，服务端 ACK 后关闭连接 |
| DATA | 0x10 | 上行 | 业务数据，严格递增序号；FLAGS 低位能力位受协商约束（见 1.4） |
| DOWNLINK | 0x20 | 下行 | 服务端下发，FLAG_RELIABLE=1 时需 ACK |
| ACK | 0x21 | 双向 | 确认。上行 ACK 确认 DOWNLINK；下行 ACK 确认 LOGIN/DATA |
| ERROR | 0x7F | 下行 | 错误帧，PAYLOAD = code(4,BE) + refSeq(4,BE) + UTF-8 reason |

> 说明：帧头 VERSION 恒为 1（线格式版本）；NEGOTIATE/NEGOTIATE_ACK 协商的是**会话协议版本**，
> 当前网关实现的会话版本范围也只有 1，但协商流程按“区间交集 + 最高共同版本”完整实现。

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
6. **RESERVED 非 0**：保留字节是“未协商扩展字段”的载体，CRC 通过后若非 0 即 `MALFORMED_PAYLOAD(1006)`
   单帧拒绝并重同步（连接保留）。
7. **非法帧不影响后续合法帧**：同一条连接上，坏帧之后的好帧照常解析（测试覆盖）。

接收侧有**两道相互独立**的容量防线（均可配置）：

| 防线 | 配置 | 判定对象 | 超限行为 |
|---|---|---|---|
| 单帧声明长度 | `gateway.max-frame-length` | 一帧头部声明的 LENGTH | `FRAME_TOO_LONG(1004)`，不信任长度、跳 MAGIC 重同步，**连接保留** |
| 按连接残留硬上限 | `gateway.max-receive-residual-bytes` | 本连接“未拼成完整帧”的累计残留字节 | `RECEIVE_BUFFER_FULL(4003)`，错误帧送达后**仅结束本连接** |

半包长挂场景（设备一直不把一帧发完整）由第二道防线兜住：解码器缓冲**精确扩容、绝不翻倍**，
每条连接接收侧内存的确定硬上界为 `max-receive-residual-bytes + 单次 read chunk`
（read chunk ≤ `max-frame-length`）。超限结论 `BufferOverflow` 与协议错误严格区分，
日志带会话标识与判定依据（残留字节数 / 上限），只终止该连接，其他连接与进程不受影响。

### 1.3 能力协商（连接建立阶段，LOGIN 之前）

每条连接必须先协商、后登录。设备连接建立后先发 **NEGOTIATE**，网关在双方范围内选出版本与能力，
回 **NEGOTIATE_ACK**；只有谈成后会话才带着定稿的版本/能力进入认证与业务。

NEGOTIATE payload：

```
minVer(1,uint) maxVer(1,uint) count(1,uint) capabilityCode(1) * count
```

- `minVer/maxVer`：设备声称支持的会话协议版本**闭区间**（要求 1 ≤ minVer ≤ maxVer）。
- `capabilityCode`：设备希望启用的可选能力，重复项去重、保持上报顺序；count 可为 0。

NEGOTIATE_ACK payload：

```
status(1) selectedVersion(1) count(1) capabilityCode(1) * count
```

`status`：`0=AGREED`、`1=VERSION_NO_INTERSECTION`、`2=UNSUPPORTED_CAPABILITY`。
网关选择规则（纯函数 `Negotiator`，可单测）：

1. **能力识别优先**：设备提供的任一能力 code 不在网关枚举内 → `UNSUPPORTED_CAPABILITY`，
   ACK 与后续 ERROR(1007) 都列出未知 code；即使版本同时不沾边，也先报能力问题，结论不暧昧。
2. **版本交集其次**：双方闭区间无交集 → `VERSION_NO_INTERSECTION`，ERROR `VERSION_MISMATCH(1002)`。
3. **谈成**：交集中选双方都支持的**最高版本**；能力取交集（按设备上报顺序）。

三种协商失败（版本无交集 / 能力不识别 / 协商负载畸形 `MALFORMED_PAYLOAD(1006)`）的处理完全一致：
**先发 NEGOTIATE_ACK（机器可判状态）+ ERROR（可读原因），两帧排空后关闭连接**。
失败连接不会被当成正常设备继续，也**绝不会悄悄按默认版本硬套**。

协商定稿即本会话**硬约束**：

- 定稿后再发 NEGOTIATE（无论是否已登录、哪怕内容相同）→ `NEGOTIATION_SETTLED(2010)`，
  稳定拒绝，会话版本与能力**不允许中途更换**；连接与既定规则继续有效。
- 未协商就发 LOGIN/DATA/ACK/LOGOUT → `NEGOTIATION_REQUIRED(2008)`，**连接保留**，
  设备仍可回头补一次合法协商。
- 已协商会话中，DATA 帧 FLAGS 出现未谈定的能力位 → `CAPABILITY_VIOLATION(2009)`，**单帧拒绝、
  该帧不生效**（其序号不被消费，可原样重发），连接与后续合法报文继续正常。
- 帧头 RESERVED 非 0（带了协议里不存在的扩展字段）→ 解码层 `MALFORMED_PAYLOAD(1006)`，连接保留。

当前已识别的可选能力：

| 能力 | code | DATA FLAGS 位 | 含义（语义占位，用于协商与越权判定） |
|---|---|---|---|
| COMPRESSION | 1 | 0x10 | 压缩 DATA |
| BATCH | 2 | 0x20 | 批量 DATA |

> 上行 DATA 的 FLAGS 只允许出现“本会话谈定能力对应的位”；`FLAG_RELIABLE(0x01)` 仅下行使用，
> 上行出现同样算越权。

### 1.4 LOGIN 负载

```
deviceIdLen(2,BE) UTF8(deviceId) secretLen(2,BE) UTF8(secret)
```

`secret` 只在内存中短暂存在。**日志与 ERROR 帧中绝不出现凭证原文**（有专门断言）。

---

## 2. 会话与序号语义

### 2.1 会话生命周期

```
TCP 连接 → NEW（未协商：只接受 NEGOTIATE）
        → SETTLED（协商定稿：版本+能力成为硬约束，尚未认证）
        → AUTHENTICATED（LOGIN 成功）
        → CLOSED（协商失败 / LOGOUT / 被踢 / 重传耗尽 / 接收残留超限 / 服务关闭 / TCP 断开）
```

- 未协商连接发任何业务帧（含 LOGIN）→ `NEGOTIATION_REQUIRED(2008)`，连接保留，允许回头协商。
- **协商失败**（版本无交集 1002 / 能力不识别 1007 / 负载畸形 1006）：ACK+ERROR 排空后关连接，
  不触达认证与任何 DeviceState。
- 定稿后再协商 → `NEGOTIATION_SETTLED(2010)`；已协商会话越权使用能力/扩展 →
  `CAPABILITY_VIOLATION(2009)`/`MALFORMED_PAYLOAD(1006)`，均为单帧拒绝、连接保留。
- SETTLED 但未认证连接发 DATA/ACK/LOGOUT → `NOT_AUTHENTICATED(2001)`，**触达不到任何 DeviceState**。
- **同连接重复登录** → `ALREADY_AUTHENTICATED(3004)`。
- **跨连接重复登录**：新连接取代旧连接，旧连接以 `SESSION_CONFLICT(2002)` 被踢；
  若旧连接其实已死（TCP 断了但尚未清理），判为“重连替换陈旧映射”，不算冲突。
- 设备状态（`DeviceState`）按 `deviceId` 存储，**重连后复用**，序号结论跨连接一致。

### 2.2 上行序号（确定性，绝不静默）

序号必须 **严格 +1 递增**，判定与状态更新在同一把锁内原子完成：

| 情形 | 结论 | 错误码 | 是否生效 |
|---|---|---|---|
| `seq == lastApplied+1` | APPLIED，回 ACK | — | 是（唯一生效路径） |
| 回退，且序号在去重窗口内且确实生效过 | DUPLICATE（幂等重放） | 2004 | 否，不再次生效 |
| 回退，但已滑出窗口 | EXPIRED，明确拒绝 | 2005 | 否 |
| 跳号（`seq > lastApplied+1`） | OUT_OF_ORDER，明确拒绝 | 2005 | 否 |

窗口大小 `gateway.seq-window`（默认 32，测试用 8）。被拒绝的消息有明确错误帧，**不是静默丢弃**。

### 2.3 下行可靠投递规则

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

## 3. 资源上限与背压

| 资源 | 上限配置 | 超限策略 |
|---|---|---|
| 并发连接数 | `gateway.max-connections`（默认 64） | Semaphore；超限连接收到 `TOO_MANY_CONNECTIONS(4001)` 后关闭，服务不中断 |
| 每连接待发送帧 | `gateway.max-outbound-per-session`（默认 256） | 有界队列满即拒绝入队，回 `OUTBOUND_BUFFER_FULL(4002)` 降级，不阻塞、不无界排队 |
| 单帧声明长度 | `gateway.max-frame-length`（默认 8192） | 超长声明立即拒绝 `FRAME_TOO_LONG(1004)` 重同步；单次 read chunk ≤ 该值 |
| 每连接接收残留 | `gateway.max-receive-residual-bytes`（默认 8192） | 未拼成完整帧的残留累计超限 → `RECEIVE_BUFFER_FULL(4003)`，错误帧排空后只关本连接；缓冲精确扩容 |
| 线程 | 每连接 1 读 + 1 写（守护线程），accept 1 | 随连接释放，总数 ≤ 2×max-connections |

接收侧每连接字节占用的确定硬上界 = `max-receive-residual-bytes + max-frame-length`
（残留缓冲 + 至多一次尚未判定的 read chunk）；乘上 max-connections 即进程接收方向的总硬上界。

帧长受上限约束，因此“帧数 × 最大帧长”即待发送/接收字节的硬上界，不存在无界内存占用。
同一连接只有一个写线程，帧严格串行，**不会串帧**；共享状态（会话表、设备状态、待确认表）
均在锁/原子结构内更新，**不会半更新或串号**。

### 优雅关闭

`stop()`：停止 accept → 静默期让在途处理完成 → 对每条活动连接**两阶段关闭**
（`SERVER_SHUTTING_DOWN(4004)` 通知帧先入待发队列，再半关闭读端唤醒读循环）→
读循环清理时排空待发帧、取消全部重传定时器、注销会话 → 最后关 socket；超过
`gateway.shutdown-timeout-ms` 才强制关闭。已提交的业务状态不随关闭丢失。

---

## 4. 错误码归类（可观测）

| 类别 | 区间 | 代表码 |
|---|---|---|
| 协议错误 | 1xxx | 1001 MAGIC 不匹配（重同步）/ 1002 版本不匹配（含协商版本无交集）/ 1003 未知类型 / 1004 帧超长 / 1005 CRC 失败 / 1006 负载畸形（含 RESERVED 非 0、协商负载畸形）/ 1007 协商能力不识别 |
| 会话/序号错误 | 2xxx | 2001 未认证 / 2002 会话冲突 / 2003 会话已关闭 / 2004 重复消息或重复 ACK / 2005 乱序或过期 / 2006 序号溢出 / 2007 重传耗尽 / 2008 协商未完成 / 2009 越权使用能力或扩展（单帧拒绝）/ 2010 协商已定稿后再协商 |
| 认证失败 | 3xxx | 3001 凭证缺失 / 3002 凭证无效（含设备不存在，不泄露注册情况）/ 3003 已失效 / 3004 重复登录 |
| 资源耗尽 | 4xxx | 4001 连接数超限 / 4002 待发缓冲满 / 4003 按连接接收残留超限（仅结束本连接）/ 4004 服务关闭中 |

每条 WARN/拒绝日志都带 **会话标识（conn/device）、消息标识（seq/type）与判定依据**，
并按错误码计入 `GatewayMetrics`（`GET /admin/metrics` 可看分类计数快照）。
快照中与本层相关的键：

- `negotiationsSucceeded` / `negotiationsVersionFailed` / `negotiationsCapabilityFailed`：
  协商三分类累计次数（成功 / 版本无交集 / 能力不识别）；
- `receiveBufferTerminations`：按连接接收残留超限（4003）被结束的连接次数；
- `receiveResidualLimitBytes`：当前生效的接收残留硬上限配置；
- `error[1002]`/`error[1007]`/`error[2008]`/`error[2009]`/`error[2010]`/`error[4003]` 等分类错误计数。

---

## 5. 可配置项（`src/main/resources/application.properties`，前缀 `gateway.`）

| 键 | 默认值 | 含义 |
|---|---|---|
| `port` | 19000 | 监听端口（仅绑定 127.0.0.1；测试用 0 取随机端口） |
| `max-connections` | 64 | 最大并发连接数 |
| `max-frame-length` | 8192 | 单帧 payload 上限（字节）；也是单次 read chunk 上限 |
| `max-receive-residual-bytes` | 8192 | 按连接接收残留硬上限（字节），独立于单帧长度；超限发 4003 后只关本连接。须 ≥ 合法最长帧（14+max-frame-length+2），否则正常长帧也会被保护拦下 |
| `max-outbound-per-session` | 256 | 每连接待发送帧队列上限 |
| `ack-timeout-ms` | 3000 | 下行 ACK 超时 |
| `max-retries` | 3 | 最大重传次数（总发送 = 1 + 该值） |
| `seq-window` | 32 | 上行序号去重窗口 |
| `shutdown-quiet-period-ms` | 100 | 优雅关闭静默期 |
| `shutdown-timeout-ms` | 5000 | 优雅关闭总超时 |
| `io-worker-threads` | 4 | 定时器线程数 |

内置演示凭证：`device-001/secret-001`、`device-002/secret-002`、`device-expired/old-secret`（已过期）。

### 本机管理接口（HTTP，亦仅本机使用）

- `GET  /admin/metrics`  指标与分类错误计数快照
- `GET  /admin/sessions` 活动已认证会话数
- `POST /admin/downlink?deviceId=...`（body 为负载字节）投递一条可靠下行；202 接受 / 503 拒绝

---

## 6. 验证方法

```bash
mvn test
```

47 个用例全部在 **127.0.0.1 回环**与**确定性时序**下运行（重传用手动时钟，无真实等待），
不依赖外部服务或真实凭据。每个用例都用日志打印**输入字节形态与判定依据**：

| 测试类 | 覆盖重点 |
|---|---|
| `FrameDecoderTest` | 粘包、半包、逐字节跨包分片、CRC 失败重同步、超长声明、垃圾字节重同步、版本/未知类型区分 |
| `NegotiatorTest` | 纯策略：谈成选最高共同版本与能力交集、版本无交集、能力不识别优先于版本、负载畸形、ACK 负载往返 |
| `NegotiationProtocolTest` | 回环全流程：协商成功后登录/数据/谈定能力可用；版本无交集与能力不识别分别关连接；越权能力单帧拒绝后连接存活、序号不消费可重发；RESERVED 扩展拒绝；定稿后两次重协商稳定被拒；先业务后协商可回头补；畸形协商关连接 |
| `ReceiveBufferCapTest` | 解码器：残留超独立上限给唯一 BufferOverflow、完整帧不触碰上限、单帧上限与残留上限相互独立 |
| `ReceiveBufferOverrunIntegrationTest` | 回环：半包长挂残留超限收 4003 后本连接关闭（原因含残留数/上限）、指标与管理面可见、既存与新建连接均不受影响 |
| `AdminObservabilityTest` | 管理面快照含协商三分类、残留上限与触发次数 |
| `AuthTest` | 凭证缺失/无效/过期可区分、同连接与跨连接重复登录、未协商业务帧 2008、拒绝会话不触达业务状态、错误响应不含凭证 |
| `SequenceTest` | 重复不再生效、跳号/窗口内回退/窗口外过期明确拒绝、重连跨连接状态延续 |
| `ReliabilityTest` | ACK 正常收敛、ACK 丢失超时重传至上限后终止会话、重复 ACK、未知 ACK（手动时钟） |
| `BackpressureTest` | 连接超限收到可区分错误且服务存活、待发队列满降级不无界增长 |
| `ConcurrencyTest` | 12 设备 × 20 消息并发：各自连续无串号；32 帧随机 1~7 字节分片灌入不串帧 |
| `EndToEndProtocolTest` | 真实回环 TCP 上粘包/逐字节半包（NEGOTIATE+LOGIN+DATA）、坏帧后同连接好帧继续、未知类型区分拒绝 |
| `ShutdownTest` | 已提交状态跨关闭保持、在途连接收到关闭通知、关闭后端口不再监听 |
| `ApplicationTests` | Spring 上下文装配（随机端口） |

手动跑一条回环链路的思路（亦体现在测试中）：连接 127.0.0.1:19000 → 发 NEGOTIATE 收
NEGOTIATE_ACK → 发 LOGIN 帧收 ACK → 发严格递增 DATA 帧收 ACK（FLAGS 只能用协商确认的能力位）→
`POST /admin/downlink` 触发可靠下行并回 ACK。观察协商与容量指标：`GET /admin/metrics`。
