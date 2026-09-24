# 堆影取证室：内存快照、弱引用边与保留路径候选（离线案例包）

本目录是领域材料，不是通用表格。运行时团队把堆快照交给审阅者，审查保留大小与泄漏路径候选。

## 文件

- `baseline.json` — 初始版本快照 `snap-baseline`（解析器 `hprof-mini/1.4.2`）。原始对象 id、地址、类元数据、
  STRONG/WEAK/INTERNAL 边、root 分类、不完整块与解析器版本全部保留。
- `snapshot-v2.json` — 第二个快照 `snap-v2`：`cls-cache` 改名为 `com.acme.LruCache`（`renamedFrom`），
  移除 `obj-0016`（疑似泄漏修复），新增 `cls-conf/obj-0017`。用于类群差异。
- `mutations/patch-local-source.json` — 只改动一个直接来源：`obj-0004` shallow size 128 B → 256 B，
  派生出 `snap-baseline-p1`，影响应只波及它自己与支配祖先的保留大小。
- `judgments.json` — 机器可读的判定依据（支配树只在可达图上构建；弱引用保活由显式策略决定；
  多个等长 root 路径保留；地址不是稳定身份；改名不是删除+新增；三种不同失败结果）。

## 内置情形（重放记录必须实际经过）

| 情形 | 材料 |
| --- | --- |
| 循环图 | `obj-0003 <-> obj-0004` |
| 弱引用策略 | `obj-0002 -WEAK-> obj-0005`，参数 v1 忽略 / v2 保活 |
| 重复地址 | `obj-0011`、`obj-0012` 地址同为 `0xCAFEBABE` |
| 不完整对象 | 悬空边 `obj-0003->obj-9999` + 不完整块 `chunk:7@offset:4096` |
| 多 root 等长路径 | `obj-0006`：`obj-0001→obj-0008→obj-0006` 与 `obj-0007→obj-0009→obj-0006` |
| 类改名 / 快照身份 | `snap-v2` 的 `cls-lru` renamedFrom `cls-cache`；对象 id 跨快照稳定 |
| 不可转换单位 | `obj-0014` 大小 `3 blocks` 留在原位 |

## 首启重放顺序（在事件流中可逐事件回溯）

1. IMPORT `snap-baseline`；2. IMPORT `snap-v2`；3. 参数 v1 `WEAK_IGNORED`（显式弱引用策略）；
4. 参数修订 v2 `WEAK_KEEP_ALIVE`；5. 源补丁 `patch-entryb-size` → `snap-baseline-p1`；
6. 人工决定：接受候选 `leak-cand:snap-baseline:obj-0016`；7. 互相冲突的人工决定：拒绝同一候选。

两条决定都被保留（采纳与未采纳结果并存），复核状态变为冲突（语义无解），需要撤回其中一条才能结案。
回退到事件 3 看到的是 v1 策略下的可达集与保留大小，不会被事件 4 之后的计算覆盖。

## 量与基准

大小保留原始 `{raw, unit}`；派生层才转换为字节（B/KB/MB/GB/KiB/MiB/GiB）。
SVG 坐标基准：`px;origin=top-left;x=depth*180;y=slot*72`；时间基准：ISO-8601 UTC（`capturedAt`）。
