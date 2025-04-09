package kz.talgat.graphql

import akka.http.scaladsl.model.DateTime
import slick.jdbc.H2Profile.api._
import java.sql.Timestamp

import scala.concurrent.duration._
import scala.concurrent.Await
import scala.language.postfixOps
import kz.talgat.graphql.models._


object DBSchema {

  implicit val dateTimeColumnType = MappedColumnType.base[DateTime, Timestamp](
    dt => new Timestamp(dt.clicks),
    ts => DateTime(ts.getTime)
  )

  class LinksTable(tag: Tag) extends Table[Link](tag, "LINKS") {

    def id = column[Int]("ID", O.PrimaryKey, O.AutoInc)

    def url = column[String]("URL")

    def description = column[String]("DESCRIPTION")

    def createdAt = column[DateTime]("CREATEDAT")

    def * = (id, url, description, createdAt).mapTo[Link]

  }

  class UsersTable(tag: Tag) extends Table[User](tag, "USERS") {
    def id = column[Int]("ID", O.PrimaryKey, O.AutoInc)

    def name = column[String]("NAME")

    def email = column[String]("EMAIL")

    def password = column[String]("PASSWORD")

    def createdAt = column[DateTime]("CREATEDAT")

    def * = (id, name, email, password, createdAt).mapTo[User]
  }

  class VotesTable(tag: Tag) extends Table[Vote](tag, "VOTES") {
    def id = column[Int]("ID", O.PrimaryKey, O.AutoInc)

    def createdAt = column[DateTime]("CREATEDAT")

    def userId = column[Int]("USERID")

    def linkId = column[Int]("LINKID")

    def * = (id, createdAt, userId, linkId).mapTo[Vote]
  }

  val Links = TableQuery[LinksTable]
  val Users = TableQuery[UsersTable]
  val Votes = TableQuery[VotesTable]

  val databaseSetup = DBIO.seq(
    Links.schema.create,
    Users.schema.create,
    Votes.schema.create,

    Links forceInsertAll Seq(
      Link(1, "http://mail.ru", "Email server address", DateTime(2025, 1, 12)),
      Link(2, "http://google.com", "Official Google web page", DateTime(2025, 1, 13)),
      Link(3, "https://speedtest.kz", "Internet speed test page", DateTime(2025, 1, 14))
    ),
    Users forceInsertAll Seq(
      User(1, "Talgat", "talgat@mail.com", "password"),
      User(2, "Shakirov", "shakirov@mail.com", "password")
    ),
    Votes forceInsertAll Seq(
      Vote(id = 1, userId = 1, linkId = 1),
      Vote(id = 2, userId = 1, linkId = 2),
      Vote(id = 3, userId = 1, linkId = 3),
      Vote(id = 4, userId = 2, linkId = 2),
    )
  )


  def createDatabase: DAO = {
    val db = Database.forConfig("h2mem")

    Await.result(db.run(databaseSetup), 10 seconds)

    new DAO(db)

  }


}
