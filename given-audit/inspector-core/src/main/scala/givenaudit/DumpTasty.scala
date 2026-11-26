package givenaudit

import scala.quoted.*
import scala.tasty.inspector.{Inspector, Tasty, TastyInspector}
import java.io.File
import java.nio.file.{Files, Path, Paths}
import scala.jdk.CollectionConverters.*

/** 第一阶段：把 classpath 上所有 .tasty 的树结构 dump 出来，确认 API 形状。 */
object DumpTasty:

  /**
   * 递归收集 classpath 上所有【目录中】的 .tasty 文件。
   * 注意：jar 不作为 inspect 目标传入——inspectTastyFiles 只接受 .tasty 文件；
   * jar 里依赖类的符号（fullName/flags/定义位置）由编译器经运行时 classpath 解析。
   */
  def collectTasty(classpath: String): List[String] =
    val tastyPaths = List.newBuilder[String]
    classpath.split(File.pathSeparator).filter(_.nonEmpty).foreach { entry =>
      val p = Paths.get(entry)
      if Files.isDirectory(p) then
        Files.walk(p).iterator().asScala
          .filter(f => Files.isRegularFile(f) && f.toString.endsWith(".tasty"))
          .foreach(f => tastyPaths += f.toAbsolutePath.toString)
      // jar 显式跳过：符号解析依赖 JVM classpath，而非 inspect 目标列表
    }
    tastyPaths.result().distinct

  /** 只保留应用自身包前缀下的 tasty，过滤掉 scala 库噪声。 */
  def run(classpath: String, tastyFiles: List[String], includePrefix: Option[String]): Unit =
    val inspector = new Inspector:
      def inspect(using q: Quotes)(tasties: List[Tasty[quotes.type]]): Unit =
        import quotes.reflect.*
        tasties.foreach { tasty =>
          val path = tasty.path
          if includePrefix.forall(path.contains) then
            println(s"==== TASTY: $path ====")
            val root = tasty.ast
            var depth = 0
            def pad() = "  " * depth
            val traverser = new TreeTraverser:
              override def traverseTree(tree: Tree)(owner: Symbol): Unit =
                val sym = tree.symbol
                val symInfo =
                  if sym == Symbol.noSymbol then "NoSym"
                  else s"${sym.fullName} [${sym.getClass.getSimpleName}] flags=${flagsOf(sym)}"
                val tpe =
                  try tpeString(tree)
                  catch case _: Throwable => "?"
                val p =
                  try
                    val pp = tree.pos
                    s"${pp.sourceFile.name}:${pp.startLine + 1}:${pp.startColumn + 1}"
                  catch case _: Throwable => "NoSpan"
                println(s"${pad()}${tree.getClass.getSimpleName} | sym=$symInfo | tpe=$tpe | pos=$p")
                depth += 1
                try super.traverseTree(tree)(owner)
                finally depth -= 1
            traverser.traverseTree(root)(Symbol.spliceOwner)
        }
    TastyInspector.inspectTastyFiles(tastyFiles)(inspector)

  private def flagsOf(using Quotes)(s: quotes.reflect.Symbol): String =
    import quotes.reflect.*
    val want = List(
      Flags.Given -> "given",
      Flags.Implicit -> "implicit",
      Flags.Module -> "object",
      Flags.Inline -> "inline",
      Flags.Macro -> "macro",
      Flags.Param -> "param",
      Flags.Synthetic -> "synthetic"
    )
    want.collect { case (f, name) if s.flags.is(f) => name }.mkString(",") match
      case "" => "-"
      case s  => s

  private def tpeString(using Quotes)(tree: quotes.reflect.Tree): String =
    import quotes.reflect.*
    tree match
      case t: Term => t.tpe.show
      case t: Definition =>
        t.symbol.tree match
          case dd: DefDef => dd.returnTpt.tpe.show
          case vd: ValDef => vd.tpt.tpe.show
          case other      => other.symbol.fullName
      case other => other.getClass.getSimpleName
