package kz.talgat.graphql

import kz.talgat.graphql.DBSchema.Links
import kz.talgat.graphql.models.Link
import slick.jdbc.H2Profile.api._

import scala.concurrent.Future

class DAO(db: Database) {
  def allLinks: Future[Seq[Link]] = db.run(Links.result)
}
