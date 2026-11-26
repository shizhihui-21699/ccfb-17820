// 模拟“清算系统”依赖的日志/JSON 库 —— 小版本升级后的版本
// 对调用方源码完全兼容，只是通过经典的 LowPriority trait 模式
// 悄悄加入了一个优先级更高的 given。
package auditlib

case class Money(amount: BigDecimal, currency: String)

trait Encoder[A]:
  def encode(a: A): String

trait LowPriorityEncoders:
  // v1 就存在的旧实现，被放到继承来的低优先级区
  given legacyEncoder: Encoder[Money] with
    def encode(m: Money): String =
      s"""{"amount":${m.amount},"currency":"${m.currency}"}"""

object Encoder extends LowPriorityEncoders:
  // v2 新增：直接定义在伴生对象里的 given，优先级高于继承来的 legacyEncoder。
  // 实现里把金额字段额外输出了一份（模拟账单字段泄漏到外部日志）。
  given companionEncoder: Encoder[Money] with
    def encode(m: Money): String =
      s"""{"amount":${m.amount},"amount_dup":${m.amount},"currency":"${m.currency}"}"""

def toLogLine[A](a: A)(using enc: Encoder[A]): String =
  enc.encode(a)

transparent inline def inlineEncode[A](a: A)(using inline enc: Encoder[A]): String =
  enc.encode(a)

/** Scala 3 风格 given 隐式转换：String -> Money */
given stringToMoney: Conversion[String, Money] with
  def apply(s: String): Money = Money(BigDecimal(s), "CNY")

/** 给 TaggedMoney 准备的 Encoder，配合 Scala2 隐式转换链路 */
case class TaggedMoney(amount: BigDecimal, currency: String, tagged: Boolean)
given taggedMoneyEncoder: Encoder[TaggedMoney] with
  def encode(t: TaggedMoney): String =
    s"""{"amount":${t.amount},"currency":"${t.currency}","tagged":${t.tagged}}"""
