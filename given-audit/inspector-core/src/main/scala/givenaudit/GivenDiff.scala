package givenaudit

import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Paths}

/**
 * 比对升级前后两份隐式使用清单。
 *
 * 配对键（同一调用点的稳定身份）：(callSite, sourceFile, line, col)
 * 比对值：implicitSymbol —— 同一点位选中的隐式实例变了，就是要阻断的"隐式换将"。
 *
 * 误报抑制：
 *   origin == "owned"（项目自有前缀）不参与 external 变更告警；
 *   kind == implicit-conversion 与 given 一样纳入比对（转换换实现同样危险）。
 */
object GivenDiff:

  case class Key(callSite: String, sourceFile: String, line: Int, col: Int)
  case class Record(
      key: Key,
      implicitSymbol: String,
      kind: String,
      sourceLang: String,
      origin: String,
      definedAt: String
  )
  case class Change(key: Key, before: String, after: String, afterDefinedAt: String, kind: String)

  def diff(beforeJson: String, afterJson: String, reportPath: String): Int =
    val before = parse(beforeJson)
    val after = parse(afterJson)
    val beforeByKey = before.map(r => r.key -> r).toMap
    val afterByKey = after.map(r => r.key -> r).toMap

    val changes = List.newBuilder[Change]
    after.foreach { r =>
      // macro-call 是盲区标记（宏展开体内的隐式选择不可见），不参与隐式切换判定
      if r.kind != "macro-call" then
        beforeByKey.get(r.key) match
          case Some(old) =>
            if old.implicitSymbol != r.implicitSymbol && r.origin == "external" then
              changes += Change(r.key, old.implicitSymbol, r.implicitSymbol, r.definedAt, "switched")
          case None =>
            if r.origin == "external" then
              changes += Change(r.key, "<none>", r.implicitSymbol, r.definedAt, "added")
    }
    before.foreach { r =>
      if r.kind != "macro-call"
        && !afterByKey.contains(r.key) && r.origin == "external"
      then
        changes += Change(r.key, r.implicitSymbol, "<gone>", r.definedAt, "removed")
    }

    val all = changes.result()
    writeReport(reportPath, before, after, all)
    printConsole(all)
    all.count(_.kind == "switched")

  private def printConsole(all: List[Change]): Unit =
    if all.isEmpty then
      println("[given-audit] PASS：升级前后外部隐式选择无变化")
    else
      println(s"[given-audit] 发现 ${all.size} 处差异：")
      all.foreach { c =>
        val tag = c.kind match
          case "switched" => "阻断[SWITCHED]"
          case "added"    => "提示[ADDED]"
          case "removed"  => "提示[REMOVED]"
        println(s"  $tag ${c.key.sourceFile}:${c.key.line}:${c.key.col} @ ${c.key.callSite}")
        println(s"      before: ${c.before}")
        println(s"      after : ${c.after}  (定义于 ${c.afterDefinedAt})")
      }

  private def parse(path: String): List[Record] =
    val text = new String(Files.readAllBytes(Paths.get(path)), StandardCharsets.UTF_8)
    val p = new MiniJson(text)
    p.skipWs(); p.consume('[')
    val records = List.newBuilder[Record]
    p.skipWs()
    if p.peek != ']' then
      while
        p.skipWs()
        records += parseRecord(p)
        p.skipWs()
        if p.peek == ',' then { p.next(); true } else false
      do ()
    p.consume(']')
    records.result()

  private def parseRecord(p: MiniJson): Record =
    p.consume('{')
    val fields = scala.collection.mutable.Map.empty[String, String]
    p.skipWs()
    if p.peek != '}' then
      while
        p.skipWs()
        val key = p.readString()
        p.skipWs(); p.consume(':'); p.skipWs()
        val value = if p.peek == '"' then p.readString() else p.readNumber()
        fields(key) = value
        p.skipWs()
        if p.peek == ',' then { p.next(); true } else false
      do ()
    p.consume('}')
    def s(k: String) = fields.getOrElse(k, "")
    val key = Key(s("callSite"), s("sourceFile"), s("line").toInt, s("col").toInt)
    Record(key, s("implicitSymbol"), s("kind"), s("sourceLang"), s("origin"), s("definedAt"))

  private def writeReport(path: String, before: List[Record], after: List[Record], changes: List[Change]): Unit =
    val sb = StringBuilder()
    sb.append(s"""{ "beforeCount": ${before.size}, "afterCount": ${after.size}, "changes": [\n""")
    changes.zipWithIndex.foreach { (c, i) =>
      sb.append("  {")
      sb.append(s""""kind": ${q(c.kind)}, "callSite": ${q(c.key.callSite)}, """)
      sb.append(s""""sourceFile": ${q(c.key.sourceFile)}, "line": ${c.key.line}, "col": ${c.key.col}, """)
      sb.append(s""""before": ${q(c.before)}, "after": ${q(c.after)}, "afterDefinedAt": ${q(c.afterDefinedAt)}""")
      sb.append("}")
      if i < changes.size - 1 then sb.append(",")
      sb.append("\n")
    }
    sb.append("] }\n")
    Files.write(Paths.get(path), sb.toString.getBytes(StandardCharsets.UTF_8))
    println(s"[given-audit] 报告已写入 $path")

  private def q(s: String): String =
    "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"") + "\""

/** 只支持本工具所需子集的 JSON 游标解析器。 */
private final class MiniJson(text: String):
  private var i = 0
  def peek: Char = text(i)
  def next(): Char = { val c = text(i); i += 1; c }
  def consume(expected: Char): Unit =
    skipWs()
    if text(i) != expected then sys.error(s"JSON 解析错误: 期望 '$expected' 但遇到 '${text(i)}' (位置 $i)")
    i += 1
  def skipWs(): Unit =
    while i < text.length && Character.isWhitespace(text(i)) do i += 1
  def readNumber(): String =
    val start = i
    while i < text.length && (text(i).isDigit || text(i) == '-') do i += 1
    text.substring(start, i)
  def readString(): String =
    consume('"')
    val sb = StringBuilder()
    while text(i) != '"' do
      val c = text(i)
      if c == '\\' then
        i += 1
        text(i) match
          case 'n'  => sb += '\n'
          case 'r'  => sb += '\r'
          case 't'  => sb += '\t'
          case '\\' => sb += '\\'
          case '"'  => sb += '"'
          case other => sb += other
        i += 1
      else { sb += c; i += 1 }
    consume('"')
    sb.toString
