package logicov.ingest

import fabric.io.{JsonFormatter, JsonParser}
import fabric.rw.*

import java.nio.file.{Files, Path, StandardOpenOption}

object ImportApp {
  def main(args: Array[String]): Unit = {
    require(args.length == 3, "Usage: ImportApp <statement.pdf|csv|xls|xlsx> <options.json> <new-output-directory>")
    val input = Path.of(args(0))
    require(Files.size(input) <= StatementImporter.MaxBytes, "File exceeds 5 MB")
    val options = JsonParser(Files.readString(Path.of(args(1)))).as[ImportOptions]
    val result = StatementImporter.run(input.getFileName.toString, Files.readAllBytes(input), options)
    val out = Path.of(args(2))
    Files.createDirectory(out)
    Files.copy(input, out.resolve(input.getFileName))
    Files.writeString(out.resolve("import.json"), JsonFormatter.Default(result.json), StandardOpenOption.CREATE_NEW)
    Files.writeString(out.resolve("options.json"), JsonFormatter.Default(options.json), StandardOpenOption.CREATE_NEW)
    println(s"${result.grid.values.size} cited values; ${result.issues.size} review issues. Output: $out")
  }
}
