ThisBuild / scalaVersion := "3.3.4"
ThisBuild / version      := "0.1.0"
ThisBuild / organization := "givenaudit"

lazy val common = Seq(
  scalacOptions ++= Seq("-deprecation")
)

// ---- 被审计的“清算系统”所依赖的两个库版本 ----
lazy val libV1 = project
  .in(file("demo/lib-v1"))
  .settings(common, name := "auditlib-v1")

lazy val libV2 = project
  .in(file("demo/lib-v2"))
  .settings(common, name := "auditlib-v2")

// Scala 2.13 老依赖：产出 pickle 签名而非 TASTy
lazy val libScala2 = project
  .in(file("demo/lib-scala2"))
  .settings(
    name := "auditlib-legacy",
    scalaVersion := "2.13.14",
    crossScalaVersions := Seq("2.13.14")
  )

// ---- 清算系统应用：同一份源码，分别链接 v1 / v2，产出两份 TASTy ----
lazy val appV1 = project
  .in(file("demo/app"))
  .settings(
    common,
    name := "clearing-app-v1",
    Compile / classDirectory := (ThisBuild / baseDirectory).value / "demo/out-v1"
  )
  .dependsOn(libV1, libScala2)

lazy val appV2 = project
  .in(file("demo/app-v2"))
  .settings(
    common,
    name := "clearing-app-v2",
    Compile / unmanagedSourceDirectories := Seq((ThisBuild / baseDirectory).value / "demo/app/src/main/scala"),
    Compile / classDirectory := (ThisBuild / baseDirectory).value / "demo/out-v2"
  )
  .dependsOn(libV2, libScala2)

// ---- 审计器本体：TASTy 读取 + 解析 + 比对（Scala 3，fork 运行）----
lazy val inspector = project
  .in(file("inspector-core"))
  .settings(
    common,
    name := "given-audit-inspector",
    // TastyInspector 自 3.3 起是独立 artifact；只依赖公共 API，不碰内部管线
    libraryDependencies ++= Seq(
      "org.scala-lang" %% "scala3-compiler" % scalaVersion.value,
      "org.scala-lang" %% "scala3-tasty-inspector" % scalaVersion.value
    ),
    Compile / mainClass := Some("givenaudit.Main")
  )

// ---- sbt 插件：Scala 2.12（sbt 1.x 约束），fork 调用上面的 Scala 3 检查器 ----
lazy val plugin = project
  .in(file("plugin"))
  .enablePlugins(SbtPlugin)
  .settings(
    name := "given-audit-plugin",
    scalaVersion := "2.12.18",
    sbtPlugin := true,
    pluginCrossBuild / sbtVersion := "1.10.7",
    // 把 Scala3 检查器 fat jar 作为资源打进插件；插件运行时解压后加入 fork classpath
    Compile / resourceGenerators += Def.task {
      val jar = (inspector / assembly).value
      val resDir = (Compile / resourceManaged).value / "given-audit"
      IO.createDirectory(resDir)
      val out = resDir / jar.getName
      IO.copyFile(jar, out)
      // 清单文件：插件按此名字定位资源，无需猜测版本号
      val listing = resDir / "jar-name.txt"
      IO.write(listing, jar.getName)
      Seq(out, listing)
    }.taskValue
  )

// 检查器 fat jar 的合并策略：丢弃签名/模块描述，避免多 jar 冲突
ThisBuild / assemblyMergeStrategy := {
  case PathList("META-INF", n: String) if n.endsWith(".SF") || n.endsWith(".RSA") || n.endsWith(".DSA") =>
    MergeStrategy.discard
  case PathList("META-INF", "MANIFEST.MF") => MergeStrategy.discard
  case "module-info.class" => MergeStrategy.discard
  case _ => MergeStrategy.first
}
