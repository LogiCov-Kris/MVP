import com.typesafe.sbt.packager.universal.UniversalPlugin.autoImport.{stagingDirectory => runtimeDirectory}

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
val sigilVersion: String = "1.5.0-SNAPSHOT"
val everyuiVersion: String = "0.1.0-SNAPSHOT"
val pdfBoxVersion: String = "3.0.7"
val scalaJavaTimeVersion: String = "2.6.0"
val scalatestVersion: String = "3.2.20"
val scalapassVersion: String = "1.4.2"
val poiVersion: String = "5.5.1"
val commonsCsvVersion: String = "1.14.1"
val cloudSqlVersion: String = "1.30.2"

ThisBuild / evictionErrorLevel := Level.Info
ThisBuild / outputStrategy := Some(StdoutOutput)

ThisBuild / resolvers += "GitHub OUTR Packages" at "https://maven.pkg.github.com/outr/_"
ThisBuild / credentials += Credentials(
  "GitHub Package Registry",
  "maven.pkg.github.com",
  "outr",
  sys.env.getOrElse("GITHUB_TOKEN", "")
)

def coreSettings: Seq[Setting[?]] = Seq(
  name := "logicov-core",
  Compile / unmanagedSourceDirectories += (ThisBuild / baseDirectory).value / "core" / "src" / "main" / "scala",
  Test / unmanagedSourceDirectories += (ThisBuild / baseDirectory).value / "core" / "src" / "test" / "scala",
  libraryDependencies ++= Seq(
    "org.typelevel" %% "fabric-core" % fabricVersion,
    "org.scalatest" %% "scalatest" % scalatestVersion % Test
  )
)

lazy val root = (project in file("."))
  .aggregate(core, server)
  .settings(
    name := "logicov",
    publish / skip := true
  )

lazy val core = (project in file("core/jvm"))
  .settings(coreSettings)

lazy val coreJs = (project in file("core/js"))
  .enablePlugins(ScalaJSPlugin)
  .settings(coreSettings)
  .settings(
    libraryDependencies += "io.github.cquiroz" %% "scala-java-time" % scalaJavaTimeVersion
  )

lazy val server = (project in file("server"))
  .dependsOn(core)
  .enablePlugins(BuildInfoPlugin, JavaServerAppPackaging, DockerPlugin)
  .settings(
    name := "logicov-server",
    Compile / mainClass := Some("logicov.App"),
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
      "com.outr" %% "sigil-core" % sigilVersion,
      "org.apache.pdfbox" % "pdfbox" % pdfBoxVersion,
      "org.apache.poi" % "poi-ooxml" % poiVersion,
      "org.apache.commons" % "commons-csv" % commonsCsvVersion,
      "com.outr" %% "scalapass" % scalapassVersion,
      "com.google.cloud.sql" % "postgres-socket-factory" % cloudSqlVersion,
      "org.scalatest" %% "scalatest" % scalatestVersion % Test,
      "com.outr" %% "rapid-test" % rapidVersion % Test
    ),
    buildInfoKeys := Seq(name, version, scalaVersion, sbtVersion),
    buildInfoPackage := "logicov",
    Test / fork := true,
    Test / parallelExecution := false,
    Universal / javaOptions ++= Seq("-J-XX:+ExitOnOutOfMemoryError", "-J--enable-native-access=ALL-UNNAMED"),
    Universal / runtimeDirectory := (ThisBuild / baseDirectory).value / "target" / "runtime",
    Universal / mappings ++= {
      val out = (app / webBundle).value
      val conv = fileConverter.value
      (out ** "*").get().filter(_.isFile).map { file =>
        (conv.toVirtualFile(file.toPath): xsbti.HashedVirtualFileRef) -> s"web/${out.toPath.relativize(file.toPath)}"
      }
    },
    Docker / packageName := "logicov-server",
    dockerExposedPorts += 8300,
    dockerUpdateLatest := true
  )

lazy val webBundle = taskKey[File]("Link the app and assemble app/out")

lazy val app = (project in file("app"))
  .dependsOn(coreJs)
  .enablePlugins(ScalaJSPlugin)
  .settings(
    name := "logicov-app",
    publish / skip := true,
    scalaJSUseMainModuleInitializer := true,
    Compile / mainClass := Some("logicov.ui.LogiCovApp"),
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
