package kz.talgat.graphql

import akka.http.scaladsl.server.Route
import sangria.parser.QueryParser
import spray.json.{JsObject, JsString, JsValue}
import akka.http.scaladsl.model.StatusCodes._
import akka.http.scaladsl.server.Directives._

import scala.concurrent.ExecutionContext
import scala.util.{Failure, Success}
import sangria.execution.{ExceptionHandler => EHandler, _}
import sangria.ast.Document
import sangria.execution._
import akka.http.scaladsl.marshallers.sprayjson.SprayJsonSupport._
import kz.talgat.graphql.models.{AuthenticationException, AuthorizationException}
import sangria.marshalling.sprayJson._

object GraphQLServer {
  private val dao = DBSchema.createDatabase

  def endpoint(requestJSON: JsValue)(implicit ec: ExecutionContext): Route = {
    val JsObject(fields) = requestJSON
    val JsString(query) = fields("query")

    QueryParser.parse(query) match {
      case Success(queryAst) =>
        val operationName = fields.get("operationName").collect {
          case JsString(name) => name
        }

        val variables = fields.get("variables") match {
          case Some(obj: JsObject) => obj
          case _ => JsObject.empty
        }
        complete(executeGraphQLQuery(queryAst, operationName, variables))
      case Failure(exception) =>
        complete(BadRequest, JsObject("error" -> JsString(exception.getMessage)))
    }
  }

  val ErrorHandler = EHandler {
    case (_, AuthenticationException(message)) ⇒ HandledException(message)
    case (_, AuthorizationException(message)) ⇒ HandledException(message)
  }

  private def executeGraphQLQuery(query: Document, operationName: Option[String], vars: JsObject)(implicit ec: ExecutionContext) = {
    Executor.execute(
        GraphQLSchema.SchemaDefinition,
        query,
        MyContext(dao),
        variables = vars,
        operationName = operationName,
        deferredResolver = GraphQLSchema.Resolver,
        exceptionHandler = ErrorHandler,
        middleware = AuthMiddleware :: Nil
    ).map(OK -> _)
      .recover {
        case error: QueryAnalysisError => BadRequest -> error.resolveError
        case error: ErrorWithResolver => InternalServerError -> error.resolveError
      }
  }
}
