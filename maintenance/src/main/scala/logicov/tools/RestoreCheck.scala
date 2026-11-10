package logicov.tools

object RestoreCheck {
  def run(): Unit = {
    val base = sys.env("LOGICOV_RESTORE_URL")
    val client = new ToolHttp(base)
    val other = new ToolHttp(base)
    try {
      client.login(sys.env("LOGICOV_TEST_EMAIL"), sys.env("LOGICOV_TEST_PASSWORD"))
      other.login(sys.env("LOGICOV_OTHER_EMAIL"), sys.env("LOGICOV_TEST_PASSWORD"))
      val deals = ToolFiles.array(client.data("/api/deals"))
      require(deals.nonEmpty, "Restore contains no deals for this analyst")
      deals.foreach { summary =>
        val route = "/api/deals/" + summary("id").asString
        val deal = client.data(route)
        other.request(route).expect(404)
        val sources = ToolFiles.array(deal("documents")).filterNot(_("name").asString.startsWith("generated-"))
        require(sources.nonEmpty, "Restored deal has no sources")
        sources.foreach { source =>
          val path = route + "/files/" + source("id").asString
          require(ToolFiles.sha(client.request(path).expect(200).bytes) == source("sha256").asString, "Restored source hash differs")
          other.request(path).expect(404)
        }
        ToolFiles.optional(deal, "memo").foreach { memo =>
          require(client.request(route + "/files/" + memo.asString).expect(200).text.startsWith("%PDF-"), "Restored memo is not a PDF")
          require(ToolFiles.optional(deal, "approval").nonEmpty, "Completed memo lost its approved snapshot")
        }
      }
      println(s"Restore verified: ${deals.size} deals, original sources, saved approvals/PDFs and cross-tenant denial")
    } finally { client.close(); other.close() }
  }
}
