# given-audit：依赖升级时的 given / 隐式实例选择守卫

## 它解决什么问题

一个日志库的小版本升级悄悄加入了一个**优先级更高的 given 实例**，编译通过、测试也绿，
但清算金额的排序与 JSON 编码被换成了新实现，账单字段被多输出一份到外部日志。
这类问题的本质是：**调用点实际选中的 given / 隐式转换来自哪个符号，在源码层面不可见，
且会随 classpath 上依赖的版本而静默变化。**

本工具读取编译产物里的 **TASTy** 文件，还原每个调用点实际选中的隐式实体来自哪个依赖，
在依赖升级前后做差异比对，发现"同一调用点选中的隐式实体被替换"就**阻断构建**。

## 架构

```
┌──────────────────────────────────────────────────────────────┐
│ 业务 Scala 3 工程（被审计）                                      │
│   compile  →  target/.../*.tasty                               │
└──────────────────────────────────────────────────────────────┘
                │  sbt 插件 fork 一个 JVM，把全 classpath 传进去
                ▼
┌──────────────────────────────────────────────────────────────┐
│ given-audit-inspector（Scala 3，自包含 fat jar，内嵌在插件资源里） │
│   TastyInspector.inspectTastyFiles(tastyFiles)                 │
│     └─ TreeTraverser 扫描每个应用编译单元                        │
│         识别 given / 隐式实例 / 隐式转换 / 宏调用                 │
│   → current.json（调用点 → 实际选中符号的清单）                  │
└──────────────────────────────────────────────────────────────┘
                │
                ▼
┌──────────────────────────────────────────────────────────────┐
│ 插件（Scala 2.12，sbt 1.x 约束）做轻量基线比对                    │
│   current.json vs given-audit-baseline.json                    │
│   外部隐式实体发生 switched → sys.error → sbt 退出码非 0 → 阻断   │
└──────────────────────────────────────────────────────────────┘
```

为什么插件是 2.12、检查器是 3：sbt 1.x 的插件必须用 Scala 2.12 编译，而 TASTy 反射 API
（`scala.quoted.Quotes` / `TastyInspector`）只能在 Scala 3 里用。因此重活放在一个 Scala 3
fat jar 里，由插件 fork 调用；插件本身只做编排与轻量 JSON 比对，不依赖任何第三方 JSON 库。

## 关键技术点与实测结论（基于 Scala 3.3.4 TASTy）

### 1. 普通 given 参数：直接可见

`toLogLine(m)` 这样的调用，被解析的 given 是隐式参数列表里的一个 term，
其 `symbol` 带 `Flags.Given`，`term.pos` 即**用户调用点**的源码位置。

### 2. 优先级（导入范围 / 伴生对象 / 继承层级）不需要自己重算

given 的优先级解析是编译器在编译期完成的，TASTy 里记录的是**解析后的最终选择**。
因此本工具不重新实现优先级规则，而是直接读取"编译器到底选了谁"。
这正是它能抓住 LowPriority trait 模式下高优先级 given 静默换将的原因——
升级前后 `implicitSymbol` 字段从 `given_Encoder_Money` 变成 `companionEncoder`，一目了然。

### 3. 内联（inline）展开：位置可追回

`inline def` 展开后，given term 位于 `Inlined` 节点内部，但其 `term.pos` 仍映射回
**用户源码位置**（而非库文件），包围它的 `DefDef` 仍是用户方法。无需对 Inlined 做特殊配对。

### 4. 隐式转换

| 形态 | TASTy 结构 | 判据 |
|---|---|---|
| Scala 3 `given Conversion` | `Apply(Select(givenObj,"apply"), args)` | 被调用 + 符号带 `Flags.Given` |
| Scala 2 `implicit def` 转换 | `Apply(Ident)`，Apply 自身符号带 `Flags.Implicit` | 被调用 + 仅 Implicit 非 Given |

分类规则：**被调用（Apply）的隐式实体 = 转换；仅被引用（Ident/Select）= 实例。**

### 5. Scala 2 老依赖（只有 pickle 签名，没有 TASTy）

这是本方案的一个关键工程细节。Scala 2 编译产物里没有 `.tasty`，但其类在 Scala 3 调用方的
TASTy 中仍以符号形式出现，特征是：

- 带 `Flags.Implicit` 但**不带** `Flags.Given`（given 是 Scala 3 概念）；
- `symbol.pos` 外壳存在但内部是 **NoSpan**（pickle 不携带可用源码位置），对其调 `startLine`
  会直接触发编译器断言。本工具用安全位置助手统一兜底。

两个落地要点：

1. **运行 classpath 必须真正包含 Scala 2 的 jar。** `TastyInspector` 不从参数拿 classpath，
   它靠 JVM 类加载器解析依赖符号。只把 classpath 当字符串传参、不放进 `-cp`，Scala 3 库还能
   靠 TASTy 元数据解析，但 Scala 2 类会报 `undefined ... at readTasty`。
2. **来源语言判定**：带 `Given` 标志 → scala3；仅 `Implicit` 且无可用源码位置 → scala2 老依赖。
   （实测 `Flags.Scala2x` 在 pickle 符号上并不可靠地亮起，因此用"无可用位置"这个更稳的信号。）

### 6. 项目自有意覆盖的 given：不误报

每条记录带 `origin` 字段：符号 fullName 命中 `givenAuditOwnPrefix`（默认取 `organization`）
的标记为 `owned`，其余为 `external`。**只有 `external` 记录参与 switched 阻断判定。**
项目自己有意提供的 given 覆盖，即使与外部同名也不会触发告警。

### 7. 宏（`${}` 宏）的能力边界 —— 已知限制

实测确认（3.3.4，默认遍历与显式 `Inlined.call/body` 下钻两种方式都验证过）：

- **普通 `inline def` 的展开体可见**；
- **`${}` 宏（`inline def m = ${ impl }`）的展开体，在 TastyInspector 视角下是不透明叶节点**——
  调用点只保留一个带 `inline,macro` 标志的 `Apply`，宏生成代码里的 summon 不在可读树中。

因此对宏，本工具采取"**让盲区可见**"而非假装覆盖：登记每个外部库宏调用点
（`kind = "macro-call"`，含用户位置与宏定义位置），但不把它计入隐式切换、也不谎称看到了
展开体。若要覆盖宏内部的隐式选择，需要改用**编译期编译器插件**（在 macro 展开之后、pickle
之前的阶段遍历树），这是本工具明确标注的下一步扩展方向，而非 TASTy 事后检查能做到的。

## 目录结构

```
given-audit/
├── build.sbt                     # 多工程构建（库 v1/v2、Scala2 老库、应用、检查器、插件）
├── inspector-core/               # Scala 3 检查器：TASTy 提取 + 差异比对
│   └── src/main/scala/givenaudit/
│       ├── GivenAudit.scala      # 核心提取器（given/隐式/转换/宏调用）
│       ├── GivenDiff.scala       # 基线比对 + 阻断决策（含内置极简 JSON 解析）
│       ├── DumpTasty.scala       # TASTy 树转储（调试/理解树结构用）
│       └── Main.scala            # dump / audit / diff 三个子命令
├── plugin/                       # Scala 2.12 sbt 插件（fork 调用检查器）
├── demo/                         # 端到端复现工程
│   ├── lib-v1, lib-v2/           # 同一库的两个版本（v2 加入高优先级 given）
│   ├── lib-scala2/               # Scala 2.13 老依赖（0 个 .tasty）
│   └── app/                      # 一字不改、仅切换依赖版本的清算应用
└── run-audit.sh                  # 命令行方式跑完整 audit+diff 的驱动脚本
```

## 用法

### 方式一：sbt 插件（推荐，用于 CI 守卫）

`project/plugins.sbt`：

```scala
addSbtPlugin("givenaudit" % "given-audit-plugin" % "0.1.0")
```

`build.sbt`：

```scala
givenAuditOwnPrefix  := Seq("com.yourcompany")   // 自有 given 的包前缀
givenAuditBaseline   := Some(baseDirectory.value / "given-audit-baseline.json")
givenAuditFailOnDiff := true                      // 外部隐式切换即失败
```

依赖升级流程：

```bash
# 升级前：固化基线并提交
sbt givenAuditExtract
cp target/given-audit/current.json given-audit-baseline.json

# 升级依赖后：CI 里运行，发生隐式换将则退出码非 0
sbt givenAuditCheck
```

### 方式二：独立检查器（命令行 / 接入其它构建系统）

```bash
# classpath 必须同时包含检查器与被审计工程的全运行 classpath（含 Scala2 jar）
java -cp "<inspector-fatjar>:<app-classpath>" givenaudit.Main \
     audit <app-classpath> current.json com.yourcompany
java -cp "<inspector-fatjar>" givenaudit.Main \
     diff baseline.json current.json report.json   # 退出码 = switched 点数
```

## 输出记录字段

```json
{
  "callSite": "demo.Clearing$.settle",          // 包围调用点的用户方法
  "sourceFile": "Clearing.scala", "line": 13, "col": 17,  // 用户源码位置
  "implicitSymbol": "auditlib.Encoder$.companionEncoder", // 实际选中的符号（比对核心）
  "kind": "given",            // given | given-conversion | implicit-val | implicit-conversion | macro-call
  "sourceLang": "scala3",     // scala3 | scala2（老依赖）
  "origin": "external",       // external | owned（自有，不参与阻断）
  "definedAt": "AuditLib.scala:20:3"            // 实例定义处（scala2 老依赖为空）
}
```

## 阻断语义

- 配对键：`(callSite, sourceFile, line, col)` —— 同一调用点的稳定身份；
- 比对值：`implicitSymbol` —— 变了就是 switched；
- 仅 `origin=external` 且 `kind != macro-call` 的 switched 计入硬阻断；
- `added` / `removed` 只提示不阻断（通常意味着源码本身改了，应交由代码评审）。
