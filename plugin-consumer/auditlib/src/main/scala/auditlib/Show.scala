package auditlib

case class Amount(value: BigDecimal)

trait Show[A]:
  def show(a: A): String

trait LowPriorityShows:
  // 旧实现被降到低优先级区
  given legacyShow: Show[Amount] with
    def show(a: Amount): String = s"""{"value":${a.value}}"""

object Show extends LowPriorityShows:
  // 升级后悄悄加入的高优先级 given，实现多输出一个泄漏字段
  given priorityShow: Show[Amount] with
    def show(a: Amount): String =
      s"""{"value":${a.value},"value_dup":${a.value}}"""

def render[A](a: A)(using s: Show[A]): String = s.show(a)
