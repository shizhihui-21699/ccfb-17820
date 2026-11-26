// 清算系统应用代码 —— v1 / v2 两个构建里这份源码一字不差，
// 唯一区别是 classpath 上链接的 auditlib 版本不同。
package demo

import auditlib.{Money, Encoder, TaggedMoney, toLogLine, inlineEncode, stringToMoney, taggedMoneyEncoder, MacroEncode}
import auditlib.legacy.{LegacyMoney, LegacyEncoder, TaggedMoney => LegacyTagged}
import auditlib.legacy.LegacyEncoders.{intEncoder, moneyEncoder, moneyToTagged, taggedEncoder}
import scala.language.implicitConversions

object Clearing:
  // 调用点 1：普通方法，given 由编译器在调用 toLogLine 时解析
  def settle(m: Money): String =
    toLogLine(m)

  // 调用点 2：内联方法，summon 发生在内联展开里（更难追位置）
  def settleInline(m: Money): String =
    inlineEncode(m)

  // 调用点 2b：真正的宏 —— summon 发生在宏编译期生成的树里
  def settleMacro(m: Money): String =
    MacroEncode.macroEncode(m)

  // 调用点 3：Scala 3 given Conversion —— String 被隐式转成 Money
  def settleFromString(raw: String): Money =
    val m: Money = raw   // 触发 given stringToMoney
    m

  // 调用点 4：Scala 2 implicit val（LegacyEncoder[Int]）—— 来自无 TASTy 的老库
  def settleNumber(n: Int): String =
    val enc = summon[LegacyEncoder[Int]]
    enc.encode(n)

  // 调用点 5：Scala 2 implicit def 转换（LegacyMoney -> TaggedMoney），
  // 转换实例来自无 TASTy 的老库
  def settleTagged(m: LegacyMoney): String =
    val t: LegacyTagged = m          // 触发 LegacyEncoders.moneyToTagged
    val enc = summon[LegacyEncoder[LegacyTagged]]
    enc.encode(t)

  object Own:
    // 调用点 6：项目自己有意提供的 given（审计时必须识别为"自有"，不能误报）
    given projectOwnedEncoder: Encoder[Money] with
      def encode(m: Money): String = s"""PROJECT(${m.amount})"""

    def settleWithOwnGiven(m: Money): String =
      toLogLine(m)
