package shop

import auditlib.{Amount, Show, render}

object Billing:
  // 调用点：依赖外部库 Show[Amount] 的隐式解析
  def bill(a: Amount): String =
    render(a)

  // 项目【有意】提供的 given 覆盖：审计必须识别为 owned，且与基线一致时不报警
  object Local:
    given customShow: Show[Amount] with
      def show(a: Amount): String = s"""LOCAL(${a.value})"""

    def billLocal(a: Amount): String =
      render(a)
