package kz.talgat.graphql.models
import akka.http.scaladsl.model.DateTime

case class Link(id: Int, url: String, description: String, createdAt: DateTime) extends Identifiable
