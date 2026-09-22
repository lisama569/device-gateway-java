# Device Gateway (loopback-only)

一个**只通过本机回环地址通信**的设备接入网关：用本地 TCP 连接模拟设备的上行与下行数据，
在粘包/半包、坏帧、版本不兼容、认证失败、重复/乱序、ACK 丢失、重传超时、资源超限和
并发/关闭等异常链路上，给出**确定且可解释**的结论。不依赖任何外部服务或真实凭据。

- 运行：`mvn spring-boot:run`（TCP 网关 `127.0.0.1:19090`，管理 HTTP `127.0.0.1:18080`）
- 测试：`mvn test`（全部基于回环与确定性时序，可重复复现）

## 1. 帧格式与解析语义

所有整数均为大端。

```
偏移   长度  字段
0      2    魔数 0x4A 0x57 ("JW")
2      1    协议版本
3      1    消息类型
4      1    标志位（bit0 = DUP 重传）
5      4    序号 seq（无符号 32 位）
9      4    载荷长度 n（无符号 32 位）
13     2    帧头 CRC（对偏移 0..12 做 CRC-16/CCITT-FALSE）
15     n    载荷
15+n   2    载荷 CRC（对载荷做 CRC-16/CCITT-FALSE）
```

解析器（`FrameDecoder`）是有状态的流式解析器，契约：

| 输入情况 | 判定原因（错误码） | 流位置处理 |
|---|---|---|
| 找不到魔数（垃圾前缀） | `BAD_MAGIC`(4001) | 丢弃魔数前的字节，保留至多 1 个可能是半个魔数的尾字节，继续找 |
| 帧头 CRC 不通过 | `HEADER_CRC_MISMATCH`(4002) | 帧头不可信，向后扫描下一个魔数重新同步 |
| 声明长度 > 1 MiB 硬上限 | `FRAME_LENGTH_IMPOSSIBLE`(4004) | **致命**：无法安全定位，关闭连接 |
| 声明长度 > 配置上限（默认 4096） | `FRAME_TOO_LONG`(4003) | 按声明长度整体跳过，**不缓冲载荷**，后续帧照常解析 |
| 载荷 CRC 不通过 | `BODY_CRC_MISMATCH`(4005) | 按声明长度跳过，流对齐，后续帧照常解析 |
| 载荷结构不合法 | `MALFORMED_PAYLOAD`(4008) | 拒绝该消息，连接继续 |

因此：粘包（一写多帧）、半包/跨包分片（一帧多写、逐字节到达）都能还原；
任何非法帧都不会造成解析错位或越界，也不影响同一连接上随后到来的合法帧。
非法帧以 `T_ERROR(0x09)` 帧回复，携带"类别 + 错误码 + 判定原因 + 关联序号"。

## 2. 协议协商与会话语义

握手顺序固定：`HELLO → HELLO_ACK → LOGIN → LOGIN_ACK`，之后才允许业务帧。

- **版本协商**：设备在 HELLO 中给出支持区间；无交集时明确回复 `VERSION_MISMATCH`(4007)，
  不按当前版本静默解析。
- **未知消息类型**：回复 `UNKNOWN_MESSAGE_TYPE`(4006)，连接继续存活。
- **消息类型**：HELLO(0x01)/HELLO_ACK(0x02)/LOGIN(0x03)/LOGIN_ACK(0x04)/
  UPLINK(0x05)/UPLINK_ACK(0x06)/DOWNLINK(0x07)/DOWNLINK_ACK(0x08)/
  ERROR(0x09)/LOGOUT(0x0A)/HEARTBEAT(0x0B)。

### 认证（区分四种结果）

| 情况 | 错误码 |
|---|---|
| deviceId 或 token 缺失/空 | `CREDENTIAL_MISSING`(5001) |
| 设备不存在或 token 不匹配（常量时间比较） | `CREDENTIAL_INVALID`(5002) |
| 凭证已过 `expires-at` | `CREDENTIAL_EXPIRED`(5003) |
| 同一设备已有活跃会话再次登录 | `ALREADY_LOGGED_IN`(5004) |
| 未登录即发业务帧/心跳 | `UNAUTHENTICATED`(5005) |

- 被拒绝的会话**不读写任何业务状态**（`DeviceStateStore` 只在登录成功后被触碰）。
- **凭证原文绝不进入日志或错误响应**：日志只打印 `Authenticator.redact()` 的脱敏形式
  （如 `tok_***(13)`、空值显示 `<none>`）。

### 上行序号与生命周期（按设备维护高水位）

- `seq == highWater+1` → `ACCEPT`，高水位与业务键在**同一把设备锁内原子**推进；
- `seq == highWater` → `DUPLICATE`(6001)，确定性地不二次生效，回复带 DUP 标志的 ACK；
- `seq > highWater+1` → `SEQ_OUT_OF_ORDER`(6002)（有缺口），明确拒绝而非静默丢弃；
- `seq < highWater`（含重连后重放旧消息）→ `SEQ_ROLLBACK`(6003)；
- `seq <= 0` → `SEQ_OUT_OF_ORDER`。

高水位和已应用业务键保存在按设备的内存存储中，**跨重连存活**：设备断开重连后，
重放旧序号被判为 ROLLBACK/DUPLICATE，序号 1..N 的消息不会被第二次应用。
会话槽位用 `putIfAbsent` 原子认领，重复登录被明确拒绝；注销是"身份安全"的——
旧会话不会被另一条连接顶替误删。

## 3. 下行可靠投递规则

- 每条下行帧有独立的下行序号；设备回 `DOWNLINK_ACK` 携带被确认序号。
- **首次 ACK 生效**：完成并移除跟踪；**重复 ACK** 被识别（计数 `downlink.duplicate-ack`）
  且不产生任何状态变化；未知序号的 ACK 明确回复错误而不是静默。
- **ACK 丢失 → 定时重传**：重传帧带 DUP 标志；重传次数有上限
  `gateway.max-retries`（默认 3），即"1 次原始发送 + 最多 3 次重传"。
- **收敛**：达到上限后下一个定时器到期即终止会话，发送
  `SESSION_TERMINATED_RETRY_EXHAUSTED`(6005) 错误帧，**有序关闭**（错误帧确保先落线），
  并在日志记录终止原因（序号、发送次数、上限）。
- 计时器通过可注入的 `RetryScheduler`/`GatewayClock` 实现；生产用墙钟，
  测试用 `ManualRetryScheduler` 显式触发，**无需真实 sleep 即可确定性复现**。

## 4. 背压与资源上限

| 资源 | 配置项（默认） | 超限策略 |
|---|---|---|
| 连接数 | `gateway.max-connections`(64) | 立即关闭新连接并计 `TOO_MANY_CONNECTIONS`(7001)，**不分配工作线程** |
| 单帧载荷 | `gateway.max-frame-payload`(4096) | 4003 拒绝并按声明长度跳过；>1 MiB 为致命 4004 |
| 接收缓冲 | `gateway.max-buffered-bytes`(1 MiB) | 半帧积压或无魔数垃圾字节累计超限 → 7002，发错误帧后关闭 |
| 待发送队列 | `gateway.max-outbound-pending`(256) | 有界队列满 → `OUTBOUND_QUEUE_FULL`(7003)，降级为拒绝并计数，不无界增长 |
| 工作线程 | 固定池，大小=连接上限 | 线程数有上界 |

出站只经过**单写线程 + 有界队列**，同一连接的帧绝不会交错（"串帧"）。

## 5. 并发与优雅关闭

- 业务序号判定在每设备锁内原子完成，多连接并发下不会出现半更新或会话串号；
  压测验证"恰好一次"的接受数与业务键数一致。
- 同一设备并发登录只有一个赢得槽位，其余全部得到 5004。
- 关闭顺序：停止 accept → 标记活跃会话关闭 → 逐个连接置停止位并关 socket →
  等待工作池结束（最多 3s）。已提交状态（高水位/业务键）保留，客户端 socket 必然关闭。

## 6. 可观测的异常归类

错误码按四类分段，`T_ERROR` 帧与 `/gateway/status` 计数器一致：

- 协议错误 `4xxx`（4001..4008）
- 认证失败 `5xxx`（5001..5005）
- 会话错误 `6xxx`（6001 重复 / 6002 乱序 / 6003 回退 / 6004 已关闭 / 6005 重传耗尽）
- 资源耗尽 `7xxx`（7001 连接数 / 7002 接收缓冲 / 7003 发送队列）

每条日志都带连接标识（`conn-N`）、设备/会话标识、消息序号与判定依据，例如：

```
[conn-4] uplink ROLLBACK device='dev-001' session=4 seq=1 highWater=2
[conn-4] downlink retransmit device='dev-001' downSeq=102 retryCount=3 lastRetry=true queued=true
[conn-4] session TERMINATED ... code=SESSION_TERMINATED_RETRY_EXHAUSTED reason='downlink seq 102 unacked after 4 send(s) ...'
```

观测端点（仅回环）：`GET http://127.0.0.1:18080/gateway/status`。
触发下行（仅回环）：`POST /gateway/devices/{deviceId}/downlink`，体 `{"seq":..,"command":..,"arg":..}`。

## 7. 可配置项

| 属性 | 默认 | 说明 |
|---|---|---|
| `gateway.bind-host` | `127.0.0.1` | **必须是回环地址**，否则启动失败 |
| `gateway.port` | `19090` | TCP 端口（0=随机，测试用） |
| `gateway.protocol-version` / `gateway.min-protocol-version` | `1` / `1` | 服务端支持版本 |
| `gateway.max-connections` | `64` | 连接数上限 |
| `gateway.max-frame-payload` | `4096` | 单帧载荷软上限 |
| `gateway.max-buffered-bytes` | `1048576` | 接收缓冲预算（含垃圾字节累计） |
| `gateway.max-outbound-pending` | `256` | 每连接待发送帧数 |
| `gateway.max-retries` | `3` | 下行最大重传次数 |
| `gateway.retry-interval-millis` | `200` | 重传/超时判定间隔 |
| `gateway.downlink-timeout-millis` | `1000` | 端到端超时参考值 |
| `gateway.enable-management` | `true` | 是否开放回环管理端点 |
| `gateway.tcp-enabled` | `true` | 置 `false` 可不启动 TCP 服务 |
| `gateway.credentials.<id>.token` | — | 本地测试凭证（非真实密钥） |
| `gateway.credentials.<id>.expires-at` | — | epoch 毫秒；`0`=永不过期 |

## 8. 验证方法

```bash
mvn test
```

52 个测试，全部在回环与确定性时序下复现，覆盖：

- **帧层** `FrameDecoderTest`：粘包、半包（1/2/3/7/15/16/100 字节切片）、跨包分片、
  垃圾前缀、帧头/载荷 CRC 失败、超长声明、越界长度、缓冲上限；`Crc16Test` 校验 CRC 标准向量。
- **消息层** `MessagesTest`：各类型载荷往返、UTF-8 长度、截断/尾随字节判为 MALFORMED。
- **认证** `AuthenticatorTest` + `AuthIT`：缺失/无效/过期/重复登录、未登录拒绝、
  脱敏后不含凭证原文、被拒会话不写业务状态。
- **会话** `DeviceStateStoreTest` / `SessionRegistryTest` / `SessionSeqIT`：
  重复/乱序/回退、重连续传与重放、并发提交恰好一次、重复登录竞争。
- **可靠投递** `RetryTrackerTest` + `DownlinkReliabilityIT`：ACK 完成、重复 ACK、
  ACK 丢失后有界重传、达到上限终止会话（错误码 6005 且 socket 关闭）、中途 ACK 收敛。
- **资源与并发** `ResourceAndConcurrencyIT`：连接数 7001、接收缓冲 7002、
  多设备并发接入、同设备并发登录唯一胜出、优雅关闭一致。
- **协商** `FrameAndNegotiationIT`：版本不匹配 4007、未知类型 4006 且连接存活、
  坏 CRC 不影响后续合法帧。

单测日志会打印输入字节（十六进制）与判定依据，例如：

```
[conn-1] received 3 byte(s): 00 11 22
judgement: garbage rejected BAD_MAGIC consumed=2, following frame seq=5 parsed OK
judgement: downlink seq 102 unacked after 4 send(s) (1 original + 3 retry) -> SESSION_TERMINATED_RETRY_EXHAUSTED
```

## 9. 代码结构

```
protocol/   Crc16, Frame, FrameEncoder, FrameDecoder, DecodeResult, RejectReason,
            ErrorCode, Messages
auth/       CredentialStore, ConfigCredentialStore, Authenticator, AuthResult
session/    SessionState, SessionRegistry, DeviceStateStore, SeqDecision
delivery/   GatewayClock, SystemGatewayClock, RetryScheduler, PendingDownlink,
            RetryTracker, OutboundQueue
gateway/    GatewayServer(回环 accept/连接上限/优雅关闭), ConnectionHandler(协议状态机),
            GatewayMetrics
api/        StatusController, ManagementController（仅回环）
```
