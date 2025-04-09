package kz.talgat.graphql.models

import akka.http.scaladsl.model.DateTime

case class Vote(id: Int, createdAt: DateTime = DateTime.now, userId: Int, linkId: Int) extends Identifiable
