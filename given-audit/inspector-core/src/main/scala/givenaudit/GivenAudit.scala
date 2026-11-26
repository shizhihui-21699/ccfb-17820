package givenaudit

import scala.quoted.*
import scala.tasty.inspector.{Inspector, Tasty, TastyInspector}
import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Paths}

/**
 * 从编译产物的 TASTy 中，还原每个调用点实际选中的 given / 隐式实例 / 隐式转换来自哪个符号。
 *
 * 事实依据（3.3.4 TASTy 实测）：
 *  1. 普通 given 参数：被解析的 given 是参数列表里的 term，symbol 带 Flags.Given，term.pos 即用户调用点。
 *  2. 内联/宏展开：实例 term 位于 Inlined 内部，但 term.pos 仍映射回用户源码位置；包围 DefDef 仍是用户方法。
 *  3. Scala3 given Conversion：转换对象以 Apply(Select(givenObj,"apply"), args) 出现，对象符号带 Flags.Given。
 *  4. Scala2 老依赖（无 TASTy，仅 pickle/.class）：implicit val / implicit def 在 Scala3 视角
 *     带 Flags.Implicit 但【不】带 Flags.Given，且 sym.pos 为空（pickle 无源码位置）；
 *     implicit def 转换以 Apply(Ident) 出现。
 *  5. 隐式形参带 Flags.Param，必须排除；定义点是 Definition，使用点是 Term，遍历天然分流。
 *
 * 分类判据：
 *  - 被【调用】（Apply）的隐式实体 = 转换（conversion）；仅被引用（Ident/Select）= 实例（given / implicit-val）。
 *  - 来源语言：带 Given 标志 → scala3；仅 Implicit 且 sym.pos 为空 → scala2 老依赖。
 */
object GivenAudit:

  /** 一条"调用点 -> 实际选中的隐式实例"记录。 */
  case class GivenUsage(
      callSite: String,        // 包围该调用点的用户方法 fullName
      sourceFile: String,
      line: Int,
      col: Int,
      implicitSymbol: String,  // 实际选中的完整符号名（v1/v2 比对的核心字段）
      implicitType: String,    // 该 term 的类型显示
      kind: String,            // given | given-conversion | implicit-val | implicit-conversion
      sourceLang: String,      // scala3 | scala2
      origin: String,          // owned（项目自有前缀）| external（外部依赖）
      definedAt: String        // 实例定义处源码位置（佐证），不可得时为 ""
  )

  def run(classpath: String, tastyFiles: List[String], outJson: String, ownPrefixes: List[String]): Unit =
    val collected = List.newBuilder[GivenUsage]

    val inspector = new Inspector:
      def inspect(using q: Quotes)(tasties: List[Tasty[quotes.type]]): Unit =
        import quotes.reflect.*

        tasties.foreach { tasty =>
          val rootFullName = tasty.ast.symbol.fullName
          val isAppUnit = ownPrefixes.exists(p => rootFullName.startsWith(p))
          if isAppUnit then
            // 键 = (方法, 文件, 行, 列, 符号)。Apply 与其内层 Ident 解析的是同一个隐式实体，
            // 用该键去重；conversion 优先级高于 instance，避免同一实体被记成两条/错分类型。
            val seen = scala.collection.mutable.LinkedHashMap.empty[
              (String, String, Int, Int, String), GivenUsage]

            def put(usage: GivenUsage): Unit =
              val key = (usage.callSite, usage.sourceFile, usage.line, usage.col, usage.implicitSymbol)
              seen.get(key) match
                case Some(existing) if existing.kind.contains("conversion") => // 已记为转换，保留
                case _ => seen(key) = usage

            val traverser = new TreeTraverser:
              private var methodStack: List[String] = Nil
              private def currentMethod: String = methodStack.headOption.getOrElse("<top-level>")

              private def isImplicitEntity(sym: Symbol): Boolean =
                sym != Symbol.noSymbol
                  && (sym.flags.is(Flags.Given) || sym.flags.is(Flags.Implicit))
                  && !sym.flags.is(Flags.Param)

              // 登记宏调用点：标记 kind=macro-call，提示该点位的展开体内隐式选择需用编译器插件覆盖
              private def recordMacroCall(app: Apply): Unit =
                val sym = app.symbol
                val (srcName, line, col) = safePos(app)
                val definedAt =
                  sym.pos.map(safePosition).filter(_._1.nonEmpty)
                    .map(t => s"${t._1}:${t._2}:${t._3}").getOrElse("")
                put(GivenUsage(
                  callSite = currentMethod,
                  sourceFile = srcName,
                  line = line,
                  col = col,
                  implicitSymbol = sym.fullName,
                  implicitType = app.tpe.show,
                  kind = "macro-call",
                  sourceLang = "scala3",
                  // 宏来自库则记 external，便于升级时至少能发现"宏本身被替换"
                  origin =
                    if ownPrefixes.exists(p => sym.fullName.startsWith(p)) then "owned" else "external",
                  definedAt = definedAt
                ))

              override def traverseTree(tree: Tree)(owner: Symbol): Unit =
                tree match
                  case dd: DefDef =>
                    methodStack = dd.symbol.fullName :: methodStack
                    try super.traverseTree(tree)(owner)
                    finally methodStack = methodStack.tail

                  // 显式下钻内联/宏展开节点：默认遍历对 ${} 宏的展开体不可见，
                  // 手动展开 Inlined.call 与 body 以触达宏生成代码里的隐式选择
                  case inl: Inlined =>
                    inl.call.foreach(c => traverseTree(c)(owner))
                    inl.bindings.foreach(b => traverseTree(b)(owner))
                    traverseTree(inl.body)(owner)

                  // 隐式转换被【调用】：Scala2 implicit def 的 Apply 自身符号即 implicit
                  case app: Apply if isImplicitEntity(app.symbol) =>
                    record(app, app.symbol, conversion = true)
                    super.traverseTree(tree)(owner)
                  // Scala3 given Conversion 对象：Apply(Select(givenObj, "apply"), args)
                  case app @ Apply(Select(conv, "apply"), _) if isImplicitEntity(conv.symbol) =>
                    record(app, conv.symbol, conversion = true)
                    super.traverseTree(tree)(owner)

                  // 宏调用：TASTy 检查器视角下 ${} 宏的展开体是不透明叶节点，
                  // 无法在此看到展开代码里的隐式选择；显式登记外部库宏点位，避免静默盲区。
                  // 过滤 scala./java. 等标准库内建宏（如 s 插值器），只保留业务/第三方宏。
                  case app: Apply
                      if app.symbol.flags.is(Flags.Macro)
                        && !app.symbol.fullName.startsWith("scala.")
                        && !app.symbol.fullName.startsWith("java.") =>
                    recordMacroCall(app)
                    super.traverseTree(tree)(owner)

                  // 普通隐式/given 实例引用（位于隐式参数列表或 summon 的 Inlined 内）
                  case t: Term if isImplicitEntity(t.symbol) =>
                    record(t, t.symbol, conversion = false)
                    super.traverseTree(tree)(owner)

                  case t: Term =>
                    super.traverseTree(t)(owner)
                  case other =>
                    super.traverseTree(other)(owner)

              private def record(term: Term, sym: Symbol, conversion: Boolean): Unit =
                val full = sym.fullName
                val isOwned = ownPrefixes.exists(p => full.startsWith(p))
                val isGiven = sym.flags.is(Flags.Given)
                // NoSpan（Scala2 pickle 符号 / 合成节点）上调 startLine 会断言，统一安全读取
                val (srcName, line, col) = safePos(term)
                val (defName, defLine, defCol) =
                  sym.pos.map(safePosition).getOrElse(("", 0, 0))
                val definedAt = if defName.nonEmpty then s"$defName:$defLine:$defCol" else ""
                // 来源语言：带 Given 标志必为 Scala3；仅 Implicit 且无【可用】源码位置
                // （Scala2 pickle 符号有 Position 外壳但内部是 NoSpan）→ Scala2 老依赖
                val fromScala2 = !isGiven && defName.isEmpty
                val kind =
                  if conversion then
                    if isGiven then "given-conversion" else "implicit-conversion"
                  else if isGiven then "given"
                  else "implicit-val"
                put(GivenUsage(
                  callSite = currentMethod,
                  sourceFile = srcName,
                  line = line,
                  col = col,
                  implicitSymbol = full,
                  implicitType = term.tpe.show,
                  kind = kind,
                  sourceLang = if fromScala2 then "scala2" else "scala3",
                  origin = if isOwned then "owned" else "external",
                  definedAt = definedAt
                ))

              private def safePos(term: Term): (String, Int, Int) =
                try safePosition(term.pos)
                catch case _: Throwable => ("", 0, 0)

              private def safePosition(p: Position): (String, Int, Int) =
                try
                  val name = p.sourceFile.name
                  (name, p.startLine + 1, p.startColumn + 1)
                catch case _: Throwable => ("", 0, 0)

            traverser.traverseTree(tasty.ast)(Symbol.spliceOwner)
            seen.values.foreach(collected += _)
        }

    val ok = TastyInspector.inspectTastyFiles(tastyFiles)(inspector)
    if !ok then
      System.err.println("[given-audit] TASTy 检查未成功完成（见编译器输出）")
      sys.exit(3)

    val usages = collected.result().distinct
    writeJson(outJson, usages)
    val external = usages.count(_.origin == "external")
    val owned = usages.count(_.origin == "owned")
    val s2 = usages.count(_.sourceLang == "scala2")
    println(s"[given-audit] 提取 ${usages.size} 条隐式使用点（external=$external, owned=$owned, 来自Scala2=$s2）-> $outJson")

  private def writeJson(path: String, usages: List[GivenUsage]): Unit =
    val names = List("callSite", "sourceFile", "line", "col", "implicitSymbol",
      "implicitType", "kind", "sourceLang", "origin", "definedAt")
    val sb = StringBuilder("[\n")
    usages.zipWithIndex.foreach { (u, i) =>
      sb.append("  {")
      val parts = List(
        s""""callSite": ${jsonStr(u.callSite)}""",
        s""""sourceFile": ${jsonStr(u.sourceFile)}""",
        s""""line": ${u.line}""",
        s""""col": ${u.col}""",
        s""""implicitSymbol": ${jsonStr(u.implicitSymbol)}""",
        s""""implicitType": ${jsonStr(u.implicitType)}""",
        s""""kind": ${jsonStr(u.kind)}""",
        s""""sourceLang": ${jsonStr(u.sourceLang)}""",
        s""""origin": ${jsonStr(u.origin)}""",
        s""""definedAt": ${jsonStr(u.definedAt)}"""
      )
      sb.append(parts.mkString(", "))
      sb.append("}")
      if i < usages.size - 1 then sb.append(",")
      sb.append("\n")
    }
    sb.append("]\n")
    Files.write(Paths.get(path), sb.toString.getBytes(StandardCharsets.UTF_8))

  private def jsonStr(s: String): String =
    val esc = s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n").replace("\r", "")
    s"\"$esc\""
