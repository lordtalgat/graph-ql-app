package kz.talgat.graphql.models

case class AuthorizationException(message: String) extends Exception(message)
