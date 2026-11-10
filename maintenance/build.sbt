ThisBuild / scalaVersion := "3.10.0-RC3"
ThisBuild / organization := "com.logicov"
ThisBuild / version := "0.1.0"

lazy val stage = taskKey[File]("Package standalone operator tools using public dependencies")
lazy val root = (project in file(".")).settings(
  name := "logicov-maintenance",
  publish / skip := true,
  libraryDependencies ++= Seq(
    "org.typelevel" %% "fabric-io" % "1.30.3",
    "org.apache.commons" % "commons-compress" % "1.28.0",
    "org.scalatest" %% "scalatest" % "3.2.20" % Test
  ),
  stage := Def.uncached {
    val jar = (Compile / packageBin).value
    val converter = fileConverter.value
    val dependencies = (Compile / dependencyClasspath).value.map(a => converter.toPath(a.data).toFile)
    val out = baseDirectory.value / "target" / "lib"
    IO.delete(out)
    IO.createDirectory(out)
    (converter.toPath(jar).toFile +: dependencies).foreach(f => IO.copyFile(f, out / f.getName))
    out
  }
)
