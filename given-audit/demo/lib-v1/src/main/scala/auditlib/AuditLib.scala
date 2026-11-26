// 模拟“清算系统”依赖的日志/JSON 库 —— 升级前的版本
package auditlib

/** 金额，清算系统的核心域对象 */
case class Money(amount: BigDecimal, currency: String)

/** 类型类：把值编码成日志里的 JSON 字段 */
trait Encoder[A]:
  def encode(a: A): String

object Encoder:
  // v1 只有一个低优先级（包级位置之外、trait 同文件）的 given
  given Encoder[Money] with
    def encode(m: Money): String =
      s"""{"amount":${m.amount},"currency":"${m.currency}"}"""

/** 清算系统真正调用的入口：内部 summon 一个 Encoder */
def toLogLine[A](a: A)(using enc: Encoder[A]): String =
  enc.encode(a)

/** 内联入口：用于验证内联展开后能否追回用户源码位置 */
transparent inline def inlineEncode[A](a: A)(using inline enc: Encoder[A]): String =
  enc.encode(a)

/** Scala 3 风格 given 隐式转换：String -> Money（用于验证 Conversion 捕获） */
given stringToMoney: Conversion[String, Money] with
  def apply(s: String): Money = Money(BigDecimal(s), "CNY")

/** 给 TaggedMoney 准备的 Encoder，配合 Scala2 隐式转换链路 */
case class TaggedMoney(amount: BigDecimal, currency: String, tagged: Boolean)
given taggedMoneyEncoder: Encoder[TaggedMoney] with
  def encode(t: TaggedMoney): String =
    s"""{"amount":${t.amount},"currency":"${t.currency}","tagged":${t.tagged}}"""

