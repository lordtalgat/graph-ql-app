package kz.talgat.graphql.models
import akka.http.scaladsl.model.DateTime

case class Link(id: Int, url: String, description: String, postedBy: Int, createdAt: DateTime = DateTime.now) extends Identifiable
