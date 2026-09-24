# 堆影取证室：内存快照、弱引用边与保留路径候选

运行时团队从线上服务导出两份堆快照（`baseline.heap.json` 与 `renamed.heap.json`），
需要在本地审查保留大小与泄漏路径候选。案例包覆盖：

- 基线 `snap-baseline`：9 个对象、5 个类、10 条边（STRONG/WEAK/INTERNAL）、2 个 root
  （JNI_GLOBAL、JAVA_FRAME）、1 个不完整块、解析器版本 `hsf-parser/1.2.0`。
  其中 o2<->o3 构成循环，o7 仅经 WEAK 边可达，o5 与 o9 共用地址 0x7f20，
  o5 有 3 条等长 root 路径（来自 2 个 root），o6 不可达。
- 对照 `snap-renamed`：同一批 classId 改了包名（cache -> legacy），新增 c5/MetricsSink，
  用于检验“类身份是 classId 而不是名字”。
- `scenarios.json`：6 个边界场景，每个只改动一个直接来源
  （循环图 / 弱引用策略 / 重复地址 / 不完整对象 / 多 root 等长路径 / 类改名），
  并记录期望状态与受影响对象范围。

所有尺寸单位为字节（B），地址为十六进制，时间基准为 epoch 毫秒（UTC）。
