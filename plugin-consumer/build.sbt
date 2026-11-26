ThisBuild / scalaVersion := "3.3.4"
ThisBuild / organization := "shop"

// 外部库：提供 given。升级时改它模拟“高优先级 given 被悄悄加入”。
lazy val auditlib = project
  .in(file("auditlib"))
  // 库工程本身不做隐式审计（它是被审计的来源，不包含业务调用点）
  .disablePlugins(givenaudit.sbt.GivenAuditPlugin)

// 业务工程：审计目标
lazy val app = project
  .in(file("app"))
  .settings(
    givenAuditOwnPrefix := Seq("shop"),
    givenAuditBaseline := Some(baseDirectory.value / "given-audit-baseline.json")
  )
  .dependsOn(auditlib)

lazy val root = (project in file("."))
  .aggregate(auditlib, app)
  .disablePlugins(givenaudit.sbt.GivenAuditPlugin)
