package logicov.tools

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import java.nio.file.Files

class DependencyBundleSpec extends AnyFlatSpec with Matchers with ToolSpecSupport {
  "Dependency installation" should "install only a complete checksum-verified bundle" in temporary { root =>
    val bytes = "binary fixture".getBytes("UTF-8")
    val name = "example/component/1/jars/component.jar"
    val file = archive(root, List("local/" + name -> bytes), Map(name -> ToolFiles.sha(bytes)))
    val destination = root.resolve("ivy")
    DependencyBundle.install(file, ToolFiles.sha(Files.readAllBytes(file)), destination) should be(1)
    Files.readAllBytes(destination.resolve("local/" + name)).toList should be(bytes.toList)
    intercept[IllegalArgumentException](DependencyBundle.install(file, "incorrect", root.resolve("wrong")))
    Files.exists(root.resolve("wrong")) should be(false)
  }
  it should "reject traversal, duplicate names, incomplete manifests and altered payloads before writing" in temporary { root =>
    val bytes = "binary".getBytes("UTF-8")
    val cases = List(
      List("local/../escape" -> bytes) -> Map("../escape" -> ToolFiles.sha(bytes)),
      List("/local/escape" -> bytes) -> Map("escape" -> ToolFiles.sha(bytes)),
      List("local/file" -> bytes, "local/file" -> bytes) -> Map("file" -> ToolFiles.sha(bytes)),
      List("local/file" -> bytes) -> Map("file" -> "incorrect"),
      List("local/file" -> bytes) -> Map("file" -> ToolFiles.sha(bytes), "missing" -> ToolFiles.sha(bytes)))
    cases.foreach { (entries, manifest) =>
      val file = archive(root, entries, manifest)
      intercept[IllegalArgumentException](DependencyBundle.install(file, ToolFiles.sha(Files.readAllBytes(file)), root.resolve("ivy")))
      Files.exists(root.resolve("ivy")) should be(false)
    }
  }
  it should "refuse destination symlinks" in temporary { root =>
    val bytes = "binary".getBytes("UTF-8")
    val file = archive(root, List("local/file" -> bytes), Map("file" -> ToolFiles.sha(bytes)))
    Files.createDirectory(root.resolve("outside"))
    Files.createDirectories(root.resolve("ivy"))
    Files.createSymbolicLink(root.resolve("ivy/local"), root.resolve("outside"))
    intercept[IllegalArgumentException](DependencyBundle.install(file, ToolFiles.sha(Files.readAllBytes(file)), root.resolve("ivy")))
    Files.exists(root.resolve("outside/file")) should be(false)
  }
  "Dependency export" should "retain reachable binaries while stripping source and test publications" in temporary { root =>
    Files.writeString(root.resolve("build.sbt"), "val componentVersion: String = \"1\"\n\"example\" %% \"component\" % componentVersion")
    Files.createDirectory(root.resolve("project")); Files.writeString(root.resolve("project/plugins.sbt"), "")
    val module = root.resolve("local/example/component_3/1")
    Files.createDirectories(module.resolve("ivys")); Files.createDirectories(module.resolve("jars"))
    Files.writeString(module.resolve("ivys/ivy.xml"), """<ivy-module version="2.0"><publications><artifact name="component" type="jar"/><artifact name="component" type="src"/></publications><dependencies><dependency org="example" name="unused" rev="1" conf="test->default"/></dependencies></ivy-module>""")
    Files.writeString(module.resolve("jars/component.jar"), "binary")
    Files.writeString(module.resolve("jars/component-sources.jar"), "private source")
    DependencyBundle.prepare(root, root.resolve("local"), root.resolve("out"))
    val file = root.resolve("out/logicov-build-dependencies.tar.gz")
    DependencyBundle.install(file, Files.readString(root.resolve("out/SHA256SUM")).trim, root.resolve("installed")) should be(2)
    val ivy = Files.readString(root.resolve("installed/local/example/component_3/1/ivys/ivy.xml"))
    ivy should not include "unused"
    ivy should not include "type=\"src\""
  }
}
