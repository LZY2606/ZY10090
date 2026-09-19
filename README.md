# Pair-wise GSB 固件升级编排服务

为嵌入式团队的主控、无线模块、传感器多固件版本升级提供**服务端依赖求解、签名校验、
cohort 分阶段发布、断电恢复模拟与持久化审计**的一体化系统。浏览器只是薄展示层——
所有可行性判定、最小冲突集合计算和发布状态机都在服务端完成。

- 纯 JDK（Java 17 字节码，已在 JDK 24 上构建运行），零外部运行时依赖；测试用 JUnit 5。
- 内置 HTTP 服务（`com.sun.net.httpserver`），无前端框架，无 Node 工具链。
- 所有记录原子落盘；原始输入、派生结果、操作事件物理分目录。
- 幂等：相同 `X-Request-Id` 重放返回同一份业务结果，绝不产生第二份。

---

## 1. 快速开始

```bash
# 构建（按要求）
./gradlew --no-daemon assemble

# 演示：先跑自动化测试，再启动服务
./gradlew --no-daemon test
./gradlew --no-daemon run --args='--port 5217'
```

打开固定地址：<http://127.0.0.1:5217>

首次启动（`./data` 为空时）会自动写入一套**已用演示密钥签名**的参考数据：

| 设备族 | 硬件修订 | 说明 |
| --- | --- | --- |
| `ctrl-a` 主控族 | `rev-a / rev-b / rev-c` | 可行的多组件升级：bootloader 下限、配套窗口、不可跨越、存储格式跳变 |
| `edge-b` 边缘族 | `r1 / r2` | 故意构造的**无共同版本 / 格式降级屏障**冲突案例 |

可选参数：`--port 5217`、`--data ./data`（数据根目录）、`--no-seed`（不种入演示数据）。

### 演示走查建议

1. **兼容矩阵**：查看每个签名包在三个硬件修订上的可用性、配套窗口、格式与可回滚标记。
2. **步骤图 / 求解**：
   - 选择设备 `dev-charlie-03`（rev-c，cohort `canary`），点“计算可行步骤”，
     得到 4 步序列 `bootloader 2.1 → radio 2.0 → main 4.x → sensor 3.0`；
     radio/main 跨存储格式的步骤**标红（无安全回滚）**，每步给出三类故障点的恢复状态。
   - 选择 `edge-r2-02` 并把 main 指到 `1.0.0`，得到 `storage_format_break`
     与**最小冲突集合**（只保留真正不可约简的那条格式屏障，而不是罗列全部事实）。
3. **发布批次**：选内置的 canary 草稿 →“批准并绑定快照”→ 在阶段卡片逐条上报回执
   （重复回执会被识别为重放，不推进阶段）→“导出决策包”。
   可先“冻结该 cohort”再“重算未冻结批次”，验证被冻结设备被钉住。
4. **操作事件**：查看追加式事件流。

### 对自己的元数据签名

导入包必须带有效签名。演示使用 HMAC-SHA256 方案（密钥 id `demo-2026`，密钥 `demo-2026-key`）：

```bash
./gradlew --no-daemon signTool --args='path/to/package-meta.json demo-2026 demo-2026-key'
```

签名覆盖**去掉 `signature` 字段后、键名字典序排序的规范 JSON**，因此空白和键顺序
不影响校验。生产环境可将 `SignatureVerifier.Verifier` 替换为 Ed25519 实现，规划/存储
代码不感知具体算法。

---

## 2. HTTP API

所有业务接口前缀 `/api`，错误统一为：

```json
{ "error": { "code": "bad_signature", "message": "...", "category": "input",
             "status": 400, "request_id": "..." } }
```

错误分类（`category`）：`input`（输入格式/语义）、`state`（与当前持久化状态冲突）、
`internal`（内部故障）。HTTP 状态：400 / 404 / 409 / 500。

写操作可带 `X-Request-Id: <id>` 实现幂等：服务端在执行业务**之前**先持久化占位声明，
重放同 id 请求返回首次响应并带头 `X-Replayed-Request: true`。

| 方法 | 路径 | 说明 |
| --- | --- | --- |
| GET | `/api/overview` | 族、包、设备、批次汇总 |
| GET | `/api/matrix/{family}` | 兼容矩阵（硬件 × 包、配套窗口、格式） |
| POST | `/api/families` | 导入设备族清单（不可变，重复导入 409） |
| POST | `/api/packages` | 导入签名固件元数据（先验签，再校验引用） |
| POST | `/api/devices` | 登记/更新设备现状（幂等，按 device_id 覆盖） |
| POST | `/api/plans/evaluate` | 从现状到目标组合求可行步骤或最小冲突集合 |
| GET/POST | `/api/rollouts` | 列批次 / 建草稿 |
| POST | `/api/rollouts/{id}/approve` | 批准并绑定清单快照 |
| POST | `/api/rollouts/{id}/receipts` | 上报设备回执（按 receipt_id 幂等） |
| POST | `/api/rollouts/{id}/freeze` | 冻结/解冻 cohort（`{cohort, frozen}`） |
| POST | `/api/rollouts/{id}/recompute` | 只重算未冻结批次（已批准批次 409） |
| GET | `/api/rollouts/{id}` / `/export` | 批次详情 / 决策包下载 |
| GET | `/api/events` | 追加式操作事件流 |

`/api/plans/evaluate` 请求：

```json
{
  "family": "ctrl-a",
  "revision": "rev-c",
  "current":  { "bootloader": "<packageId>__<version>__<sha256前12位>", "...": "..." },
  "targets":  { "main": "...", "radio": "..." }
}
```

包引用也接受 `packageId#version`（同 id+版本只有一个哈希时）。目标里未提及但被目标包
窗口约束的组件（如 bootloader），求解器会**选满足所有窗口的最低版本自动补全目标**，
并在响应的 `derived_targets` 中说明——系统从不按“最新版优先”硬选。

---

## 3. 数据模型

### 3.1 设备族清单（原始输入，`raw/families/`）

- `family`、`name`
- `revisions[]`：`{id, label, bootloader_min}` —— 每个硬件修订的 **bootloader 下限**。
- `components[]`：按数组顺序声明**默认刷写顺序**，每项 `{id, label, probe, health_check}`。

### 3.2 固件包元数据（原始输入，`raw/packages/`）

| 字段 | 含义 |
| --- | --- |
| `package_id` / `component` / `version` | 名称维度（描述性，非身份） |
| `family` | 所属设备族 |
| `format_version` | 存储格式号；**向下降级即破坏存储** |
| `sha256` + `signer` | **二进制身份**：同名同版本但哈希不同 = 不同二进制，拒绝互相替换 |
| `signature` | `<keyId>:<base64url(HMAC-SHA256)>`，覆盖规范载荷 |
| `safe_rollback` | 是否保留可回滚槽位 |
| `hw_compatibility` | `{修订id: "*"}` 白名单，空表表示不限制 |
| `requires` | 配套组件闭区间，如 `{"radio": "2.0.0..2.9.9"}` |
| `bootloader_min` | 刷入该载荷前要求的最低 bootloader |
| `max_from_version` | **不可跨越**：允许的最高“当前版本”（升级跳跃上限） |
| `probe` / `health_check` | 前置探针、健康判据（缺省取族级默认） |

区间字符串支持 `1.0..2.3`、`1.4`（精确）、`>=1.0` / `<=2.3`、`*`，均为闭区间。

### 3.3 设备现状与目标

设备：`{device_id, family, revision, cohort, frozen, components:{组件: 包键}}`。
求解请求见 §2。规划结果含：

- `feasible / kind`：`feasible` 或 `dependency_cycle` / `no_common_version` /
  `storage_format_break` / `bootloader_below_floor` / `no_skip_violation`。
- `steps[]`：每步含 `pre_flash_probe / update_action / health_check /
  rollback_target / safe_rollback`，以及 `simulation[]` 的三类故障点结果。
- `conflict_set[]`：**不可再约简的最小冲突集合**（含门控类型与判定证据）；
  `all_blocking_facts[]` 是完整阻塞事实供深入排查。

### 3.4 发布（rollout）、阶段、快照

- 草稿由每台设备的规划结果聚合；同一 `(组件, 目标版本)` 的步骤跨设备合并为一个**阶段**，
  阶段在所有预期设备回执后完成并自动推进。
- `approve` 时把族清单和涉及的签名包**复制进 `derived/snapshots/`** 并在批次上记录
  `snapshot_id`。之后新上传的元数据**不改变**已批准方案；已批准批次也禁止重算。
- 冻结：`frozen=true` 的设备或被冻结 cohort 中的设备不参与重算，返回 `pinned_by_freeze`。
- 导出包 `derived/exports/{id}.json` 包含：决定依据（选择策略、cohort、快照 id）、
  签名校验结果、全部阶段、回执、**所有失败分支**（每步断电/探针/健康三分支）、
  不可行设备的冲突集合和绑定快照本体。

---

## 4. 求解器与模拟器的关键判定（都在服务端）

`fw.core.Planner` 对“组件→已装包”组合状态做 **BFS**（最短路，所以步骤最少），
每条单组件刷写边依次过六道门控：

1. `hardware_revision`：硬件白名单；
2. `bootloader_floor`：载荷声明的 bootloader 下限；
   刷 bootloader 时还**反向**检查当前运行组件对 bootloader 的窗口，保证“先抬底”的顺序正确；
3. `package_requires`：所有配套组件版本窗；
4. `max_from_version`：不可跨越版本；
5. `format_downgrade`：存储格式号不得倒退；
6. `target_floor`：目标 bootloader 不得低于硬件修订下限。

不可达时：先按“与目标相关的组件”过滤阻塞事实，再做**贪心删除式极小化**
（逐条尝试放松，保留那些一旦放松就会让目标重新可达的事实），得到不可再约简集合；
依赖环通过 requires 图上的环检测单独判定。**没有任何“版本最新就选它”的捷径。**

`fw.core.Simulator` 对每步派生三个故障点：

- **写入提交后断电**：双槽 bootloader→`previous_bootloader_slot`；普通 A/B 可回滚包→
  `previous_app_slot`；格式升级（无回滚）且格式≥2→`recovery_partition`（需人工）；
  无槽位单 bank→`brick` / `torn_single_bank`。
- **前置探针失败**：`unchanged`，什么都没刷。
- **健康判据失败**：有回滚目标则 `rolled_back`，否则 `stuck_on_bad_image` 并标红。

---

## 5. 持久化与恢复方式

数据根目录（默认 `./data`）：

```
data/
├── raw/                 # 原始输入（只写一次，绝不原地改）
│   ├── families/        # 导入的设备族清单
│   └── packages/        # 验签通过的固件元数据（一个包一个文件）
├── derived/             # 派生结果
│   ├── devices/         # 当前设备现状
│   ├── plans/           # 每次评估的规划结果
│   ├── rollouts/        # 发布批次状态机
│   ├── snapshots/       # 批准时绑定的清单+签名包快照
│   └── exports/         # 决策导出包
└── events/
    ├── events.log       # 长度分帧、只追加的操作日志
    └── ledger-*.json    # request_id 幂等账本
```

- **原子提交**：所有 JSON 先写同目录临时文件并 force，再 `ATOMIC_MOVE` 改名。
  进程在落盘中途退出，重启后只能看到上一份完整文件或新一份完整文件，不会暴露半成品；
  临时文件残留也不会被读取。
- **事件日志**：每帧 `<字节长度>:<JSON>\n`；尾部残缺帧在恢复时被识别并丢弃，
  不会把半行解析成事件。
- **请求账本**：业务执行前先原子写入 `processing` 占位并以该文件为锁，完成后用完整响应
  原子覆盖；并发/重试的同 `X-Request-Id` 只会读到占位（409 `request_in_progress`）或
  最终响应（带 `X-Replayed-Request`），不会产生第二份业务结果。
- **重启恢复**：启动时从 `raw/` 重建只读目录索引，设备/批次/快照/导出本来就是磁盘事实，
  直接继续服务。

---

## 6. 代码结构

```
src/main/java/fw/
├── Main.java                 # CLI 入口、演示密钥、首次播种
├── json/Json.java            # 确定性 JSON：解析/写入/规范（签名）形式
├── crypto/                   # SHA-256、HMAC 签名校验、签名 CLI（SignTool）
├── model/                    # Version / VersionRange / FirmwarePackage / DeviceFamily / DeviceState
├── core/
│   ├── Catalog.java          # 族与签名包的只读索引（同名异哈希检测）
│   ├── Planner.java          # 门控、BFS、目标闭包、最小冲突集合、兼容矩阵
│   ├── Simulator.java        # 断电/探针/健康三分支恢复模型
│   ├── RolloutManager.java   # cohort/阶段/快照/回执幂等/冻结重算/导出
│   ├── AppService.java       # 唯一业务边界（前端不含判定）
│   └── DemoData.java         # 演示用已签名参考数据
├── store/Store.java          # 三区分离、原子写、分帧日志、请求账本
└── api/
    ├── HttpServer.java       # 路由、错误分类、幂等重放、静态资源
    └── Errors.java           # input/state/internal 异常体系
src/main/resources/web/       # index.html / app.js / styles.css（薄展示层）
src/test/java/fw/             # 29 个 JUnit 5 测试（含 HTTP 端到端）
```

---

## 7. 自动化测试

```bash
./gradlew --no-daemon test
```

覆盖：版本/区间语义、规范 JSON、HMAC 签名（篡改/未知密钥/键序无关）、
BFS 顺序与三道硬屏障、最小冲突集合的极小性、断电恢复三分支、原子写与半成品不可见、
分帧日志残尾丢弃、请求账本重放、批次批准/回执幂等/冻结重算/导出、同名异哈希拒绝、
HTTP 层错误分类与重放响应头。

---

## 8. 设计边界与说明

- 签名方案是演示用 HMAC；接口抽象 `SignatureVerifier.Verifier` 已为 Ed25519/证书钉扎预留。
- 设备的包引用使用 `packageId__version__sha256前12位`，哈希碰撞不在演示威胁模型内；
  生产环境直接用完整 sha256 作为键即可（模型已携带完整哈希）。
- BFS 设 5 万状态预算兜底：域内窗口很小，正常规划远低于此；超限按不可达处理而非 OOM。
