package givenaudit

/**
 * 用法:
 *   given-audit dump  <classpath> [包名前缀]
 *   given-audit audit <classpath> <输出json> [项目自有包前缀...]
 *   given-audit diff  <before.json> <after.json> <报告json>
 *                      diff 退出码 = 外部 given 发生"切换"的点数（非0即应阻断构建）
 */
object Main:
  def main(args: Array[String]): Unit =
    args.toList match
      case "dump" :: classpath :: rest =>
        val prefix = rest.headOption
        val tasty = DumpTasty.collectTasty(classpath)
        println(s"[given-audit] 发现 ${tasty.size} 个 tasty 条目")
        DumpTasty.run(classpath, tasty, prefix)

      case "audit" :: classpath :: outJson :: ownPrefixes =>
        val tasty = DumpTasty.collectTasty(classpath)
        GivenAudit.run(classpath, tasty, outJson, ownPrefixes)

      case "diff" :: beforeJson :: afterJson :: reportJson :: Nil =>
        val switched = GivenDiff.diff(beforeJson, afterJson, reportJson)
        sys.exit(if switched == 0 then 0 else 1)

      case _ =>
        System.err.println(
          """用法:
            |  given-audit dump  <classpath> [包名前缀]
            |  given-audit audit <classpath> <输出json> [项目自有包前缀...]
            |  given-audit diff  <before.json> <after.json> <报告json>
            |""".stripMargin)
        sys.exit(2)
