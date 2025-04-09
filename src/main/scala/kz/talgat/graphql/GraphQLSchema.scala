package kz.talgat.graphql

import akka.http.scaladsl.model.DateTime
import sangria.schema.{Field, ListType, ObjectType}
import models._
import sangria.ast.StringValue
import sangria.schema._
import sangria.execution.deferred.{DeferredResolver, Fetcher, HasId}
import sangria.macros.derive.{ReplaceField, deriveObjectType}


object GraphQLSchema {

  implicit val LinkType = deriveObjectType[Unit, Link](
    ReplaceField("createdAt", Field("createdAt", graphQlDateTime, resolve = _.value.createdAt))
  )
  implicit val linkHasId = HasId[Link, Int](_.id)
  implicit val graphQlDateTime = ScalarType[DateTime](
    "DateTime",
    coerceOutput = (dt, _) => dt.toString(),
    coerceInput = {
      case StringValue(dt, _, _) => DateTime.fromIsoDateTimeString(dt).toRight(DateTimeCoerceViolation)
      case _ => Left(DateTimeCoerceViolation)
    },
    coerceUserInput = { //5
      case s: String => DateTime.fromIsoDateTimeString(s).toRight(DateTimeCoerceViolation)
      case _ => Left(DateTimeCoerceViolation)
    }
  )

  val linksFetcher = Fetcher(
    (ctx: MyContext, ids: Seq[Int]) => ctx.dao.getLinks(ids)
  )

  val Resolver = DeferredResolver.fetchers(linksFetcher)

  val Id = Argument("id", IntType)
  val Ids = Argument("ids", ListInputType(IntType))

  val QueryType: ObjectType[MyContext, Any] = ObjectType(
    "Query",
    fields[MyContext, Any](
      Field("allLinks", ListType(LinkType), resolve = c => c.ctx.dao.allLinks),
      Field("link",
        OptionType(LinkType),
        arguments = Id :: Nil,
        resolve = c => linksFetcher.deferOpt(c.arg(Id))),
      Field("links",
        ListType(LinkType),
        arguments = List(Argument("ids", ListInputType(IntType))),
        resolve = c => linksFetcher.deferSeq(c.arg(Ids))
      )
    )
  )

  val SchemaDefinition: Schema[MyContext, Any] = Schema(QueryType)
}
