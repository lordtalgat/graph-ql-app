package kz.talgat.graphql

import akka.http.scaladsl.model.DateTime
import sangria.schema.{Field, ListType, ObjectType}
import models._
import sangria.ast.StringValue
import sangria.schema._
import sangria.execution.deferred.{DeferredResolver, Fetcher}
import sangria.macros.derive.{Interfaces, ReplaceField, deriveObjectType}


object GraphQLSchema {

  implicit val LinkType = deriveObjectType[Unit, Link](
    ReplaceField("createdAt", Field("createdAt", graphQlDateTime, resolve = _.value.createdAt))
  )
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
  val UserType = deriveObjectType[Unit, User]()
  val userFetcher = Fetcher(
    (ctx: MyContext, ids: Seq[Int]) => ctx.dao.getUsers(ids)
  )

  val VoteType = deriveObjectType[Unit,Vote]()
  val voteFetcher = Fetcher(
    (ctx: MyContext, ids: Seq[Int]) => ctx.dao.getVotes(ids)
  )

  val linksFetcher = Fetcher(
    (ctx: MyContext, ids: Seq[Int]) => ctx.dao.getLinks(ids)
  )

  val Resolver = DeferredResolver.fetchers(linksFetcher, userFetcher, voteFetcher)

  val Id = Argument("id", IntType)
  val Ids = Argument("ids", ListInputType(IntType))

  val QueryType: ObjectType[MyContext, Any] = ObjectType(
    "Query",
    fields[MyContext, Any](
      Field("allLinks", ListType(LinkType), resolve = c => c.ctx.dao.allLinks),
      Field("allUsers", ListType(UserType), resolve = c => c.ctx.dao.allUsers),
      Field("allVotes", ListType(VoteType), resolve = c => c.ctx.dao.allVotes),
      Field("link",
        OptionType(LinkType),
        arguments = Id :: Nil,
        resolve = c => linksFetcher.deferOpt(c.arg(Id))),
      Field("links",
        ListType(LinkType),
        arguments = List(Argument("ids", ListInputType(IntType))),
        resolve = c => linksFetcher.deferSeq(c.arg(Ids))
      ),
      Field("user",
        OptionType(UserType),
        arguments = Id :: Nil,
        resolve = c => userFetcher.deferOpt(c.arg(Id))),
      Field("users",
        ListType(UserType),
        arguments = List(Argument("ids", ListInputType(IntType))),
        resolve = c => userFetcher.deferSeq(c.arg(Ids))),
      Field("vote",
        OptionType(VoteType),
        arguments = Id :: Nil,
        resolve = c => voteFetcher.deferOpt(c.arg(Id))
      ),
      Field("votes",
        ListType(VoteType),
        arguments = List(Argument("ids", ListInputType(IntType))),
        resolve = c => voteFetcher.deferSeq(c.arg(Ids))
      )
    )
  )

  val SchemaDefinition: Schema[MyContext, Any] = Schema(QueryType)
}
