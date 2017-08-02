package uk.gov.hmrc.helptosavefrontend.controllers

import cats.data.EitherT
import play.api.mvc.Result
import uk.gov.hmrc.helptosavefrontend.enrolment.EnrolmentStore
import uk.gov.hmrc.helptosavefrontend.models.HtsContext
import uk.gov.hmrc.helptosavefrontend.services.EnrolmentService
import uk.gov.hmrc.helptosavefrontend.util.{Logging, NINO}
import uk.gov.hmrc.play.frontend.controller.FrontendController

import scala.concurrent.Future

trait EnrolmentCheckBehaviour { this: FrontendController with Logging ⇒

  import EnrolmentCheckBehaviour._

  val enrolmentService: EnrolmentService

  def checkIfAlreadyEnrolled(ifNotEnrolled: NINO ⇒ Future[Result])(implicit htsContext: HtsContext): Future[Result] = {
    val enrolled = for {
      nino ← EitherT.fromOption[Future](htsContext.nino, NoNINO)
      enrolmentStatus ← enrolmentService.getUserEnrolmentStatus(nino).leftMap(e ⇒ EnrolmentServiceError(nino, e))
    } yield (nino, enrolmentStatus)


    enrolled.semiflatMap {
      case (nino, EnrolmentStore.Enrolled(itmpHtSFlag)) ⇒
        // if the user is enrolled but the itmp flag is not set then just
        // start the process to set the itmp flag here without worrying about the result
        if (!itmpHtSFlag) {
          enrolmentService.setITMPFlag(nino).fold(
            e ⇒ logger.warn(s"Could not start process to set ITMP flag for user $nino: $e"),
            _ ⇒ logger.info(s"Process started to set ITMP flag for user $nino")
          )
        }
        Future.successful(Ok("You've already got an account - yay!"))

      case (nino, EnrolmentStore.NotEnrolled) ⇒
        ifNotEnrolled(nino)
    }.leftMap(handleError)
      .value
      .map(_.merge)
  }

  private def handleError(enrolmentCheckError: EnrolmentCheckError): Result = enrolmentCheckError match {
    case NoNINO ⇒
      logger.warn("Could not get NINO")
      InternalServerError

    case EnrolmentServiceError(nino, message) ⇒
      logger.warn(s"Error while trying to check if user $nino was already enrolled to HtS: $message")
      InternalServerError
  }

}

object EnrolmentCheckBehaviour {

  private sealed trait EnrolmentCheckError

  private case object NoNINO extends EnrolmentCheckError

  private case class EnrolmentServiceError(nino: NINO, message: String) extends EnrolmentCheckError

}
