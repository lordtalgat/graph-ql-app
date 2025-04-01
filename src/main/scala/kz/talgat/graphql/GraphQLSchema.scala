package kz.talgat.graphql

import sangria.schema.{Field, ListType, ObjectType}
import models._
import sangria.schema._


object GraphQLSchema {

  val LinkType = ObjectType[Unit, Link](
    "Link",
    fields[Unit, Link](
      Field("id", IntType, resolve = _.value.id),
      Field("url", StringType, resolve = _.value.url),
      Field("description", StringType, resolve = _.value.description)
    )
  )

  val QueryType: ObjectType[MyContext, Any] = ObjectType(
    "Query",
    fields[MyContext, Any](
      Field("allLinks", ListType(LinkType), resolve = c => c.ctx.dao.allLinks)
    )
  )

  val SchemaDefinition: Schema[MyContext, Any] = Schema(QueryType)
}
