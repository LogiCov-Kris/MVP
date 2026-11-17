package logicov.publiccompany

import logicov.grid.GoldenGrid

case class PublicSnapshot(issuer: PublicIssuer, grid: GoldenGrid, sources: List[(String, String, Array[Byte])])
