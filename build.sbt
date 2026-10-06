ThisBuild / organization := "com.logicov"
ThisBuild / version := "0.1.0-SNAPSHOT"
ThisBuild / scalaVersion := "3.10.0-RC3"

ThisBuild / scalacOptions ++= Seq(
  "-deprecation",
  "-feature",
  "-unchecked",
  "-Xmax-inlines", "128"
)

val spiceVersion: String = "1.10.9-SNAPSHOT6"
val rapidVersion: String = "2.9.10-SNAPSHOT"
val fabricVersion: String = "1.30.2-SNAPSHOT"
val profigVersion: String = "3.8.0"
val scribeVersion: String = "3.19.0"
val scribeJsVersion: String = "3.19.1-SNAPSHOT"
val lightdbVersion: String = "4.48.0-SNAPSHOT"
val everyuiVersion: String = "0.1.0-SNAPSHOT"
val pdfBoxVersion: String = "3.0.7"
val scalatestVersion: String = "3.2.20"

ThisBuild / evictionErrorLevel := Level.Info
ThisBuild / outputStrategy := Some(StdoutOutput)

ThisBuild / resolvers += "GitHub OUTR Packages" at "https://maven.pkg.github.com/outr/_"
ThisBuild / credentials += Credentials(
  "GitHub Package Registry",
  "maven.pkg.github.com",
  "outr",
  sys.env.getOrElse("GITHUB_TOKEN", "")
)

def sharedSources(buildRoot: File): File = buildRoot / "shared" / "src" / "main" / "scala"

lazy val root = (project in file("."))
  .aggregate(server)
  .settings(
    name := "logicov",
    publish / skip := true
  )

lazy val server = (project in file("server"))
  .enablePlugins(BuildInfoPlugin, JavaServerAppPackaging, DockerPlugin)
  .settings(
    name := "logicov-server",
    Compile / mainClass := Some("logicov.App"),
    Compile / unmanagedSourceDirectories += sharedSources((ThisBuild / baseDirectory).value),
    fork := true,
    libraryDependencies ++= Seq(
      "com.outr" %% "spice-server-undertow" % spiceVersion,
      "com.outr" %% "spice-openapi" % spiceVersion,
      "com.outr" %% "rapid-core" % rapidVersion,
      "org.typelevel" %% "fabric-io" % fabricVersion,
      "com.outr" %% "profig" % profigVersion,
      "com.outr" %% "scribe" % scribeVersion,
      "com.outr" %% "scribe-file" % scribeVersion,
      "com.outr" %% "lightdb-postgresql" % lightdbVersion,
      "org.apache.pdfbox" % "pdfbox" % pdfBoxVersion,
      "org.scalatest" %% "scalatest" % scalatestVersion % Test,
      "com.outr" %% "rapid-test" % rapidVersion % Test
    ),
    buildInfoKeys := Seq(name, version, scalaVersion, sbtVersion),
    buildInfoPackage := "logicov",
    Test / fork := true,
    Test / parallelExecution := false,
    Universal / javaOptions += "-J-XX:+ExitOnOutOfMemoryError",
    Docker / packageName := "logicov-server",
    dockerExposedPorts += 8300,
    dockerUpdateLatest := true
  )

lazy val webBundle = taskKey[File]("Link the app and assemble app/out")

lazy val app = (project in file("app"))
  .enablePlugins(ScalaJSPlugin)
  .settings(
    name := "logicov-app",
    publish / skip := true,
    scalaJSUseMainModuleInitializer := true,
    Compile / mainClass := Some("logicov.ui.main"),
    scalaJSLinkerConfig ~= (_.withModuleKind(ModuleKind.NoModule)
      .withESFeatures(_.withESVersion(org.scalajs.linker.interface.ESVersion.ES2021))),
    excludeDependencies += ExclusionRule("com.outr", "reactify_3"),
    libraryDependencies ++= Seq(
      "com.outr" %% "everyui-web" % everyuiVersion,
      "com.outr" %% "everyui-network" % everyuiVersion,
      "com.outr" %% "spice-client" % spiceVersion,
      "org.typelevel" %% "fabric-io" % fabricVersion,
      "com.outr" %% "rapid-core" % rapidVersion,
      "com.outr" %% "scribe" % scribeJsVersion,
      "com.outr" %% "profig" % profigVersion,
      ("org.scala-js" %% "scalajs-java-securerandom" % "1.0.0").cross(CrossVersion.for3Use2_13)
    ),
    Compile / unmanagedSourceDirectories += sharedSources((ThisBuild / baseDirectory).value),
    everyuiWebTarget := WebTarget.Js,
    webBundle := Def.uncached {
      val out = baseDirectory.value / "out"
      IO.delete(out)
      everyuiWebScripts.value.writeTo(out, baseDirectory.value / "index.html")
      IO.copyDirectory(everyuiWebFonts.value, out / "fonts")
      streams.value.log.info(s"web bundle: $out")
      out
    }
  )
