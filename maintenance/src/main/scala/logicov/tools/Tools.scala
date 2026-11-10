package logicov.tools

import java.nio.file.Path

object Tools {
  def main(args: Array[String]): Unit = args.toList match {
    case "install-dependencies" :: archive :: hash :: destination :: Nil =>
      println(s"Installed ${DependencyBundle.install(Path.of(archive), hash, Path.of(destination))} verified dependency files")
    case "prepare-dependencies" :: rest =>
      require(rest.size <= 1, "Usage: prepare-dependencies [repository]")
      val root = Path.of(rest.headOption.getOrElse(".")).toAbsolutePath.normalize()
      val local = Path.of(sys.env.getOrElse("LOGICOV_IVY_LOCAL", Path.of(System.getProperty("user.home"), ".ivy2", "local").toString))
      DependencyBundle.prepare(root, local, root.resolve("target/build-dependencies"))
    case "capture-mail" :: Nil => MailCapture.run(Path.of("target/email-smoke/message.json"))
    case "smoke" :: Nil => HttpSmoke.run()
    case "verify-restore" :: Nil => RestoreCheck.run()
    case "compare-records" :: first :: second :: Nil =>
      require(ToolFiles.read(Path.of(first)) == ToolFiles.read(Path.of(second)), "Trimmed fixture changed the deal record")
      println("Trimmed fixture reproduces the original deal record")
    case "verify-artifacts" :: Nil => ArtifactCheck.run(Path.of(sys.env.getOrElse("LOGICOV_TEST_OUTPUT", "poc/out/workspace-verification")))
    case "trim-pack" :: source :: destination :: repo :: Nil => PackTrimmer.run(Path.of(source), Path.of(destination), Path.of(repo))
    case "verify-grid" :: rest =>
      require(rest.size % 2 == 0, "Supply --records, --packs, --pack-suffix and --output as name/value pairs")
      val options = rest.grouped(2).map(p => p.head -> p(1)).toMap
      require(options.keySet.subsetOf(Set("--records", "--packs", "--pack-suffix", "--output")) && options.contains("--output"), "Unknown option or missing --output")
      GridSourceCheck.run(Path.of(options.getOrElse("--records", "poc/out/round3")), Path.of(options.getOrElse("--packs", "poc/samples/packs")), options.getOrElse("--pack-suffix", ""), Path.of(options("--output")))
    case _ => throw new IllegalArgumentException("Commands: install-dependencies, prepare-dependencies, capture-mail, smoke, verify-restore, verify-artifacts, trim-pack, compare-records, verify-grid")
  }
}
