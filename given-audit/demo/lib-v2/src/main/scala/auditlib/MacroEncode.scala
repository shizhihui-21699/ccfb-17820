package auditlib

import scala.quoted.*

/**
 * 真正的 Scala 3 宏：在编译期用 Expr.summon 搜索 Encoder，并切片进生成的表达式。
 * 用来验证：宏【生成】代码里的隐式选择，在 TASTy 中能否追回用户调用点的源码位置。
 *
 * 用 Expr.summon + '$enc 切片（而非手工 reflect 拼树），展开结果会带标准 Inlined 节点，
 * 其中 given term 的 pos 映射回用户调用点。
 */
object MacroEncode:
  // 宏方法必须是 inline def：展开体才会以 Inlined 节点内联进调用方 TASTy
  inline def macroEncode[A](inline a: A): String =
    ${ macroEncodeImpl[A]('a) }

  private def macroEncodeImpl[A: Type](a: Expr[A])(using Quotes): Expr[String] =
    Expr.summon[Encoder[A]] match
      case Some(enc) =>
        // 生成 enc.encode(a)；enc 是宏展开期 summon 到的 given
        '{ $enc.encode($a) }
      case None =>
        quotes.reflect.report.errorAndAbort(s"宏内找不到 Encoder[${Type.show[A]}]")
