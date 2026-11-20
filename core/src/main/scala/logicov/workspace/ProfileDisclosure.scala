package logicov.workspace

import fabric.rw.*

case class ProfileDisclosure(documentId: String, documentName: String, excerpt: String,
                             suggestions: List[ProfileEntry]) derives RW
