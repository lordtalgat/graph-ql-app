package kz.talgat.graphql

import slick.jdbc.H2Profile.api._

import scala.concurrent.duration._
import scala.concurrent.Await
import scala.language.postfixOps
import kz.talgat.graphql.models._


object DBSchema {

  class LinksTable(tag: Tag) extends Table[Link](tag, "LINKS"){

    def id = column[Int]("ID", O.PrimaryKey, O.AutoInc)
    def url = column[String]("URL")
    def description = column[String]("DESCRIPTION")

    def * = (id, url, description).mapTo[Link]

  }

  val Links = TableQuery[LinksTable]

  val databaseSetup = DBIO.seq(
    Links.schema.create,

    Links forceInsertAll Seq(
      Link(1, "http://mail.ru", "Email server address"),
      Link(2, "http://google.com", "Official Google web page"),
      Link(3, "https://speedtest.kz", "Internet speed test page")
    )
  )


  def createDatabase: DAO = {
    val db = Database.forConfig("h2mem")

    Await.result(db.run(databaseSetup), 10 seconds)

    new DAO(db)

  }


}
