package givenaudit.sbt

import sbt._
import sbt.Keys._
import scala.sys.process._
import scala.util.Try
import java.io.File

/**
 * given-audit sbt 插件。
 *
 * 架构：插件本体是 Scala 2.12（sbt 1.x 约束），而 TASTy 解析器 given-audit-inspector 是 Scala 3。
 * 因此插件【fork 一个 JVM】运行检查器（与 sbt 官方 Pipelining/Scala3 插件同样的隔离方式），
 * 插件自身只负责：组装 classpath、调用、读取产物 JSON、做基线比对并决定是否阻断。
 *
 * 任务：
 *   givenAuditExtract —— 从当前工程 TASTy 提取隐式选择清单到 target/given-audit/current.json
 *   givenAuditCheck   —— 与基线 givenAuditBaseline 比对，外部 given/隐式发生"切换"则阻断构建
 *
 * 典型用法（依赖升级守卫）：
 *   1. 升级依赖前：givenAuditExtract，把 current.json 提交为基线 given-audit-baseline.json
 *   2. 升级依赖后：givenAuditCheck（或在 CI 里把它接到 compile / test 之后）
 */
object GivenAuditPlugin extends AutoPlugin {

  override def trigger = allRequirements
  override def requires = sbt.plugins.JvmPlugin

  object autoImport {
    val givenAuditBaseline   = settingKey[Option[File]]("隐式选择基线 JSON；设置后 givenAuditCheck 与之比对")
    val givenAuditOwnPrefix  = settingKey[Seq[String]]("项目自有 given 的包前缀，命中则标记 owned 并抑制误报")
    val givenAuditFailOnDiff = settingKey[Boolean]("检测到外部隐式切换时是否让构建失败（默认 true）")
    val givenAuditExtract    = taskKey[File]("提取当前工程的隐式选择清单 JSON")
    val givenAuditCheck      = taskKey[Unit]("与基线比对，外部隐式切换则按 givenAuditFailOnDiff 决定阻断")
  }

  import autoImport._

  // Scala 3 检查器的入口类与其所在 classes 目录（由 inspector 子工程提供）
  private val InspectorMain = "givenaudit.Main"

  override def projectSettings: Seq[Def.Setting[?]] = Seq(
    givenAuditBaseline := None,
    givenAuditOwnPrefix := Seq(organization.value),
    givenAuditFailOnDiff := true,

    // 仅 Scala 3 工程需要注入 tasty-inspector 运行依赖；Scala 2 工程跳过本插件
    libraryDependencies ++= {
      if (scalaVersion.value.startsWith("3."))
        Seq(
          "org.scala-lang" %% "scala3-tasty-inspector" % scalaVersion.value % Runtime,
          "org.scala-lang" %% "scala3-compiler" % scalaVersion.value % Runtime
        )
      else Seq.empty
    },

    givenAuditExtract := {
      val _ = (Compile / compile).value
      val outDir = target.value / "given-audit"
      IO.createDirectory(outDir)
      val outJson = outDir / "current.json"

      if (!scalaVersion.value.startsWith("3.")) {
        streams.value.log.warn(
          s"[given-audit] 工程 ${name.value} 是 Scala ${scalaVersion.value}，无 TASTy，跳过提取。")
        outJson
      } else {
        // 运行 classpath = 检查器 fat jar（打包在插件资源里，运行时解压）+ 被审计工程全 classpath
        val inspectorJar = extractInspectorJar(target.value).get
        val appCp = (Compile / fullClasspath).value.map(_.data)
        val cp = (inspectorJar +: appCp).distinct
        val own = givenAuditOwnPrefix.value
        val args = Seq("audit", cp.map(_.getAbsolutePath).mkString(File.pathSeparator), outJson.getAbsolutePath) ++ own

        streams.value.log.info(s"[given-audit] 提取隐式选择 -> ${outJson.getAbsolutePath}")
        val rc = forkInspector(cp, args, streams.value.log)
        if (rc != 0) sys.error(s"[given-audit] TASTy 检查器异常退出（rc=$rc）")
        outJson
      }
    },

    givenAuditCheck := {
      val log = streams.value.log
      val current = givenAuditExtract.value
      givenAuditBaseline.value match {
        case None =>
          log.warn(
            "[given-audit] 未设置 givenAuditBaseline，已生成当前清单但未做比对。" +
            "将该文件设为基线后即可在依赖升级时阻断隐式换将。")
        case Some(baseline) =>
          if (!baseline.exists) sys.error(s"[given-audit] 基线文件不存在：${baseline.getAbsolutePath}")
          val changed = countSwitched(baseline, current)
          if (changed > 0) {
            val msg = s"[given-audit] 检测到 $changed 处外部 given/隐式实例在依赖升级后被切换（详见两个 JSON 的差异）。"
            if (givenAuditFailOnDiff.value) sys.error(msg + " 构建已阻断。")
            else log.warn(msg + " givenAuditFailOnDiff=false，仅告警不阻断。")
          } else {
            log.info("[given-audit] PASS：外部隐式选择与基线一致。")
          }
      }
    }
  )

  /**
   * 从插件自身资源里定位检查器 fat jar，解压到工程 target 下并返回其 File。
   * 资源路径形如 given-audit/given-audit-inspector-assembly-*.jar（由 resourceGenerators 注入）。
   */
  private def extractInspectorJar(targetDir: File): Try[File] = Try {
    val dir = targetDir / "given-audit" / "inspector"
    IO.createDirectory(dir)
    val resourcePath = findInspectorResource()
    val out = dir / new File(resourcePath).getName
    if (!out.exists) {
      val in = Option(getClass.getClassLoader.getResourceAsStream(resourcePath))
        .getOrElse(sys.error(s"[given-audit] 插件资源缺失：$resourcePath"))
      try IO.transfer(in, out) finally in.close()
    }
    out
  }

  private def findInspectorResource(): String = {
    // 打包时写入的清单文件 given-audit/jar-name.txt 记录了 assembly jar 的真实文件名
    val loader = getClass.getClassLoader
    val listing = loader.getResourceAsStream("given-audit/jar-name.txt")
    if (listing != null) {
      try "given-audit/" + scala.io.Source.fromInputStream(listing).mkString.trim
      finally listing.close()
    } else sys.error(
      "[given-audit] 找不到 given-audit/jar-name.txt，插件资源未正确打包。")
  }

  private def forkInspector(cp: Seq[File], args: Seq[String], log: Logger): Int = {
    val javaBin = new File(sys.props("java.home"), "bin/java").getAbsolutePath
    val cmd = Seq(javaBin, "-cp", cp.map(_.getAbsolutePath).mkString(File.pathSeparator), InspectorMain) ++ args
    log.debug("[given-audit] fork: " + cmd.mkString(" "))
    val code = Process(cmd).!(ProcessLogger(log.info(_), log.error(_)))
    code
  }

  // ---- 轻量比对：扫描两个 JSON 中"同一调用点但 implicitSymbol 不同"的 external 记录 ----
  private case class Rec(callSite: String, sourceFile: String, line: String, col: String,
                         symbol: String, origin: String)

  private def countSwitched(baseline: File, current: File): Int = {
    val before = parseRecs(baseline)
    val after  = parseRecs(current)
    val keyOf = (r: Rec) => (r.callSite, r.sourceFile, r.line, r.col)
    val beforeByKey = before.map(r => keyOf(r) -> r).toMap
    after.count { r =>
      r.origin == "external" &&
      beforeByKey.get(keyOf(r)).exists(_.symbol != r.symbol)
    }
  }

  private def parseRecs(f: File): Seq[Rec] = {
    val text = IO.read(f)
    // 仅匹配本工具写出的扁平对象，逐对象抽取字段（避免在 2.12 插件里引入 JSON 依赖）
    val objRegex = "(?s)\\{[^{}]*\\}".r
    objRegex.findAllIn(text).toSeq.map { obj =>
      def str(field: String): String = {
        val m = (s""""$field"\\s*:\\s*"([^"]*)"""").r.findFirstMatchIn(obj)
        m.map(_.group(1)).getOrElse("")
      }
      Rec(
        callSite = str("callSite"),
        sourceFile = str("sourceFile"),
        line = str("line"),
        col = str("col"),
        symbol = str("implicitSymbol"),
        origin = str("origin")
      )
    }
  }
}
