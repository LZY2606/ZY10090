# fwplan — 固件依赖规划与发布服务

面向嵌入式团队（主控 / 无线模块 / 传感器多固件版本）的**服务端**依赖求解、断电模拟与
cohort 发布编排系统。所有关键判定（依赖区间、可行步骤、最小冲突集、签名校验、阶段推进）
都在服务端完成，浏览器只负责展示。零第三方运行依赖，仅需 JDK 17+。

## 构建与演示

```bash
./gradlew --no-daemon assemble                     # 构建（产出 build/distributions 分发包）

./gradlew --no-daemon test                         # 自动化测试（版本/规划/模拟/加密/存储/端到端）
./gradlew --no-daemon run --args='--port 5217'     # 启动服务
```

打开固定地址：<http://127.0.0.1:5217>

首次启动（`data/` 为空）会自动生成一个演示 RSA 签名者 `embedded-demo` 与一套
真实签名的种子数据；之后启动不会重复播种。其它参数：

- `--data /path/to/dir`：数据目录（默认 `./data`）
- `--no-seed`：不写入演示数据

## 屏幕上能看到什么

- **兼容矩阵**：每个组件×版本在选定硬件修订上的 bootloader 下限、存储 epoch、
  配套区间及“离散版本上是否真有共同版本”（无共同版本红色显示）。
- **批次与步骤图**：
  - `cohort-stable`：常规升级，可行；含 bootloader 步骤，该节点**红色（无安全回滚）**。
  - `cohort-cycle`：`mcu 2.1.0 ↔ radio 2.0.0` 相互要求，单组件无解，规划器给出显式
    标注的 **BUNDLE** 同刷步骤。
  - `cohort-downgrade`：sensor 存储 epoch 1→0 不可降级，返回最小冲突集，**不会**
    因“最新版优先”硬选。
  - `cohort-frozen`：已冻结；点击“重算未冻结批次”时原样保留。
- **故障点结果**：每个步骤展开后，对“任意组件写入后立即断电”逐一给出重启恢复状态；
  撤不回合法状态即判砖化，该阶段标红。
- **发布与回执**：创建 draft → 批准（绑定清单快照）→ 设备逐阶段推进（带 `requestId`
  幂等）→ 下载导出包。

## 判定规则（核心语义）

状态为“各组件当前安装版本”，一个状态合法当且仅当：

1. 每个组件的版本在库中存在**已签名**工件且支持该硬件修订；
2. 每个非 bootloader 工件满足自身 `minBootloader`（bootloader 下限）；
3. 每个 `companions` 区间在**离散的已签名版本集合**上被对端满足；区间数学相交但没有
   任何实际版本落入，报 `NO_COMMON_VERSION`。

转移（刷写）规则：

- 先用 BFS 搜索**单组件**刷写序列；不可达时放宽到 2、3 组件同时刷写，产生带
  `bundle=true` 的显式步骤（用于打破配套依赖环）。
- 不得跨越清单声明的 **gate**（不可跨越版本，如存储格式不可逆升级）。
- `storageEpoch` 不允许下降；bootloader 不允许降级。
- 不可达时输出最小冲突集合：`GATE_CROSSED` / `STORAGE_FORMAT_BREAK` /
  `BOOTLOADER_DOWNGRADE` / `BOOTLOADER_TOO_OLD` / `NO_COMMON_VERSION` /
  `DEPENDENCY_CYCLE`（Tarjan SCC）/ `NO_VALID_ORDERING` / `HARDWARE_UNSUPPORTED` 等。
- BFS 状态访问上限 200000，超限追加 `SEARCH_CAPPED`。

固件身份只由 **sha256 + 签名者 + RSA 签名**决定。`family/component/version` 相同但
sha256 不同的“同名包”导入直接返回 **409 状态冲突**，不会替换既有工件。

## 发布与阶段

- 方案按 cohort 生成阶段，每阶段携带：更新次序、前置探针、健康判据、回滚目标；
  模拟判定 `safeRollback=false` 的阶段在界面与导出里**标红**（默认策略：含
  bootloader 刷写、回滚会跨 gate/降 epoch、或存在不可恢复故障点）。
- 只有全部 cohort 可行才能创建方案；draft 可批准，**批准时冻结快照**（清单 + 每个
  cohort 的分析结果）。批准后再上传任何元数据都不改变该方案及其导出。
- 设备推进必须 `requestId` 幂等：同一 `(releaseId, deviceId, requestId)` 重放返回
  首次回执且 `replay=true`，绝不推进第二个阶段；已到 cohort 末阶段再推进返回 409。
- 导出包是单个 JSON，含 `decisionBasis`（决定依据/快照）、`signatureVerification`
  （逐工件签名+哈希校验）与 `failureBranches`（所有断电故障点与冲突）。

## 断电恢复模型

每步内部按固定次序写入：非 bootloader 组件按清单顺序，**bootloader 永远最后**。
模拟器在每次组件写入后注入“立即断电”：重启时把本步骤已写组件逆序回滚，落到第一个
合法（bootloader 下限与全部配套区间满足）的可定义恢复状态；任何撤法都不合法即砖化。
状态文件采用“同目录临时文件 + fsync + 原子 rename”，崩溃后重启清理 `.tmp` 残留并
从事件日志重放关键业务结果（回执），不会暴露半成品状态。

## 数据模型与三类存储

```
data/
  raw/                         原始输入（永不因重算而改写）
    trusted_signers.json       signer -> RSA X.509 公钥(base64)
    manifests/<familyId>.json  设备族清单
    firmware/<sha256>.json     签名固件元数据
    cohorts/<cohortId>.json    批次定义（含 frozen 标记）
  derived/                     派生结果
    plans/ analyses/           规划与“规划+模拟”结果
    releases/<releaseId>.json  发布方案（含批准快照）
    receipts/<requestId>.json  设备推进回执（幂等索引）
    exports/<releaseId>.json   导出包
  events/events-YYYYMMDD.log   追加写操作事件（JSONL）
  tmp/                         历史临时目录（.tmp 现在落在目标同目录）
```

写入顺序：**先追加事件并 fsync，再原子替换状态文件**。若进程在两步之间退出：
事件已落而状态缺失时，启动重放重建；状态不可能先于事件出现，因此不会产生无事件的
“幽灵结果”。同一 `requestId` 的回执文件天然唯一，重放只返回旧值。

## 主要 HTTP API

| 方法 路径 | 说明 |
| --- | --- |
| `GET /api/overview` | 族/固件/cohort/方案总览 |
| `POST /api/import/manifest` | 导入设备族清单（原始输入） |
| `POST /api/import/firmware` | 导入并校验签名固件元数据 |
| `GET /api/matrix/{family}/{hw}` | 兼容矩阵 |
| `POST /api/analyze` | 任意现状→目标分析（不落业务库） |
| `POST /api/cohorts` / `GET /api/cohorts/{id}` | 建改批次 / 取分析 |
| `POST /api/cohorts/{id}/freeze|unfreeze` | 冻结/解冻 |
| `POST /api/recompute` | 冻结批次保留，其余用最新元数据重算 |
| `POST /api/releases/create` / `.../{id}/approve` | 创建 / 批准并绑定快照 |
| `POST /api/device/advance` | 设备推进（requestId 幂等） |
| `GET /api/releases/{id}/export` | 下载导出包 |

错误响应统一为 `{"error","status","message"}`，三类可区分：

- `400 INPUT_FORMAT`：JSON 非法、字段缺失、版本/区间格式错、签名不受信或校验失败；
- `409 STATE_CONFLICT`：同名包不同哈希、cohort 已冻结、方案状态不允许的操作、
  设备重复推进、不可行 cohort 试图发布；
- `500 INTERNAL`：服务端未预期故障（持久化 IO 等）。

## 自签固件（可选）

```bash
./gradlew --no-daemon -q signtool -PcliArgs="keygen --out /tmp/demo-key.json"
# 编辑一份含 sha256 的固件元数据 /tmp/fw.json，然后：
./gradlew --no-daemon -q signtool -PcliArgs="sign --key /tmp/demo-key.json --signer my-team --meta /tmp/fw.json"
```

生产部署应把公钥写入 `data/raw/trusted_signers.json`（签名者名 → X.509 base64），
私钥离线保管；种子数据的 `embedded-demo` 仅用于演示。

## 测试

`fwplan.AllTests` 是自研断言入口（不依赖 JUnit，保证离线构建），覆盖：

- 版本/区间解析与比较；
- 常规路径、依赖环 bundle、存储 epoch/gate 冲突、无共同版本、硬件不支持、
  bootloader 下限、同名包拒绝；
- 模拟器对 bootloader 阶段标红与逐组件故障点；
- RSA 签名通过/篡改失败；
- 原子写、事件重放重建、三类目录分离；
- 端到端：播种→分析→冻结/重算→建方案→批准快照→幂等回执→导出→重启。
