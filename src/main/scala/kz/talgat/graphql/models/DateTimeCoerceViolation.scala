package kz.talgat.graphql.models

import sangria.validation.Violation

case object DateTimeCoerceViolation extends Violation {

  override def errorMessage: String = "Error during parsing DateTime"
}
