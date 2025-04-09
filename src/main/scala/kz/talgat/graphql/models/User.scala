package kz.talgat.graphql.models

import akka.http.scaladsl.model.DateTime

case class User(id: Int, name: String, email: String, password: String, createdAt: DateTime = DateTime.now) extends Identifiable
