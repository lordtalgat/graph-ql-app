package kz.talgat.graphql

import akka.http.scaladsl.model.DateTime
import slick.jdbc.H2Profile.api._
import java.sql.Timestamp

import scala.concurrent.duration._
import scala.concurrent.Await
import scala.language.postfixOps
import kz.talgat.graphql.models._


object DBSchema {

  implicit val dateTimeColumnType = MappedColumnType.base[DateTime,Timestamp](
    dt => new Timestamp(dt.clicks),
    ts => DateTime(ts.getTime)
  )

  class LinksTable(tag: Tag) extends Table[Link](tag, "LINKS"){

    def id = column[Int]("ID", O.PrimaryKey, O.AutoInc)
    def url = column[String]("URL")
    def description = column[String]("DESCRIPTION")
    def createdAt = column[DateTime]("createdAt")

    def * = (id, url, description, createdAt).mapTo[Link]

  }

  val Links = TableQuery[LinksTable]

  val databaseSetup = DBIO.seq(
    Links.schema.create,

    Links forceInsertAll Seq(
      Link(1, "http://mail.ru", "Email server address", DateTime(2025,1,12)),
      Link(2, "http://google.com", "Official Google web page", DateTime(2025,1,13)),
      Link(3, "https://speedtest.kz", "Internet speed test page", DateTime(2025,1,14))
    )
  )


  def createDatabase: DAO = {
    val db = Database.forConfig("h2mem")

    Await.result(db.run(databaseSetup), 10 seconds)

    new DAO(db)

  }


}
