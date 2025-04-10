package kz.talgat.graphql.models

import akka.http.scaladsl.model.DateTime

case class Vote(id: Int, userId: Int, linkId: Int, createdAt: DateTime = DateTime.now) extends Identifiable
