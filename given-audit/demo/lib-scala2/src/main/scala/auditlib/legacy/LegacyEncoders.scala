// Scala 2.13 编写的老依赖：只有 pickle 签名、没有 TASTy。
// 完全自包含（不依赖 Scala 3 模块），提供 Scala 2 风格隐式实例与隐式转换。
package auditlib.legacy

// 老库自带的域类型与类型类（模拟历史遗留、无法迁移到 Scala 3 的基础库）
case class LegacyMoney(amount: BigDecimal, currency: String)

trait LegacyEncoder[A] {
  def encode(a: A): String
}

case class TaggedMoney(amount: BigDecimal, currency: String, tagged: Boolean)

object LegacyEncoders {
  // Scala 2 隐式 val：在 Scala 3 调用方看来是可被 summon 的隐式实例
  implicit val intEncoder: LegacyEncoder[Int] = new LegacyEncoder[Int] {
    def encode(a: Int): String = s"""{"int":$a}"""
  }

  implicit val moneyEncoder: LegacyEncoder[LegacyMoney] = new LegacyEncoder[LegacyMoney] {
    def encode(m: LegacyMoney): String =
      s"""{"amount":${m.amount},"currency":"${m.currency}"}"""
  }

  // Scala 2 隐式转换（老式 implicit def，非 given Conversion）
  implicit def moneyToTagged(m: LegacyMoney): TaggedMoney =
    TaggedMoney(m.amount, m.currency, tagged = true)

  implicit val taggedEncoder: LegacyEncoder[TaggedMoney] = new LegacyEncoder[TaggedMoney] {
    def encode(t: TaggedMoney): String =
      s"""{"amount":${t.amount},"currency":"${t.currency}","tagged":${t.tagged}}"""
  }
}
