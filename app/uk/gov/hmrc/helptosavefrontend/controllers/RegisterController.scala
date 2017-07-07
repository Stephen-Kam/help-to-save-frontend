/*
 * Copyright 2017 HM Revenue & Customs
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package uk.gov.hmrc.helptosavefrontend.controllers

import java.time.format.DateTimeFormatter
import javax.inject.Singleton

import cats.data.EitherT
import cats.instances.either._
import cats.instances.future._
import cats.instances.option._
import cats.syntax.either._
import cats.syntax.traverse._
import com.fasterxml.jackson.databind.JsonNode
import com.github.fge.jackson.JsonLoader
import com.github.fge.jsonschema.core.report.{ProcessingReport, ProcessingMessage}
import com.github.fge.jsonschema.main.{JsonSchemaFactory, JsonValidator}
import com.google.inject.Inject
import configs.syntax._
import play.api.i18n.{I18nSupport, MessagesApi}
import play.api.libs.json.Json
import play.api.{Application, Configuration, Logger}
import play.api.mvc.{Action, AnyContent, Request}
import play.api.{Application, Logger}
import uk.gov.hmrc.helptosavefrontend.connectors.NSIConnector.SubmissionFailure
import uk.gov.hmrc.helptosavefrontend.connectors._
import uk.gov.hmrc.helptosavefrontend.controllers.RegisterController.OAuthConfiguration
import uk.gov.hmrc.helptosavefrontend.models.{EligibilityResult, HTSSession, NSIUserInfo}
import uk.gov.hmrc.helptosavefrontend.services.HelpToSaveService
import uk.gov.hmrc.helptosavefrontend.util.FEATURE
import uk.gov.hmrc.helptosavefrontend.views
import uk.gov.hmrc.http.cache.client.CacheMap
import uk.gov.hmrc.play.http.HeaderCarrier
import uk.gov.hmrc.helptosavefrontend.controllers.RegisterController.JSONValidationFeature._

import scala.concurrent.{ExecutionContext, Future}

@Singleton
class RegisterController @Inject()(val messagesApi: MessagesApi,
                                   helpToSaveService: HelpToSaveService,
                                   sessionCacheConnector: SessionCacheConnector)(implicit app: Application, ec: ExecutionContext)
  extends HelpToSaveAuth(app) with I18nSupport {


  private[controllers] val oauthConfig = app.configuration.underlying.get[OAuthConfiguration]("oauth").value

  def getAuthorisation: Action[AnyContent] = authorisedForHtsWithEnrolments {
    implicit request ⇒
      implicit userUrlWithNino ⇒
        Future.successful(redirectForAuthorisationCode(request, userUrlWithNino))
  }

  def confirmDetails(code: Option[String],
                     error: Option[String],
                     error_description: Option[String],
                     error_code: Option[String]): Action[AnyContent] =
    (code, error) match {
      case (Some(authorisationCode), _) ⇒
        authorisedForHtsWithEnrolments {
          implicit request ⇒
            implicit userUrlWithNino ⇒
              val userInfo: EitherT[Future, String, EligibilityResult] = for {
                userUrlWithNino ← EitherT.fromOption[Future](userUrlWithNino, "could not retrieve either userDetailsUrl or NINO from auth")
                eligible ← helpToSaveService.checkEligibility(userUrlWithNino.nino, userUrlWithNino.path, authorisationCode)
                nsiUserInfo ← toNSIUserInfo(eligible)
                _ <- EitherT.fromEither[Future](validateOutGoingUserInfo(nsiUserInfo))
                _ ← writeToKeyStore(nsiUserInfo)
              } yield eligible

              userInfo.fold(
                error ⇒ {
                  Logger.error(s"Could not perform eligibility check: $error")
                  InternalServerError("")
                }, _.result.fold(
                  SeeOther(routes.RegisterController.notEligible().url))(
                  userDetails ⇒ Ok(views.html.register.confirm_details(userDetails)))
              )
        }

      case (_, Some(e)) ⇒
        Logger.error(s"Could not get authorisation code: $e. Error description was" +
          s"${error_description.getOrElse("-")}, error code was ${error_code.getOrElse("-")}")
        // TODO: do something better
        Action {
          InternalServerError(s"Could not get authorisation code: $e")
        }

      case _ ⇒
        // we should never reach here - we shouldn't have a successful code and an error at the same time
        Logger.error("Inconsistent result found when attempting to retrieve an authorisation code")
        Action {
          InternalServerError("")
        }
    }

  def getCreateAccountHelpToSavePage: Action[AnyContent] = authorisedForHtsWithConfidence {
    implicit request ⇒
      Future.successful(Ok(views.html.register.create_account_help_to_save()))
  }

  def validateOutGoingUserInfo(userInfo: Option[NSIUserInfo]): Either[String, Option[NSIUserInfo]] = {
    val conf = app.configuration
    userInfo match {
      case None => Right(None)
      case Some(ui) =>
        FEATURE("outgoing-json-validation", conf, featureLogger) thenOrElse(
          for {
            t0 <- validateUserInfoAgainstSchema(ui, validationSchema)
            t1 <- before1800(ui)
            t2 <- futureDate(ui)
          } yield t2,
          Right(userInfo))
    }
  }

  def createAccountHelpToSave: Action[AnyContent] = authorisedForHtsWithConfidence {
    implicit request ⇒
      val result: EitherT[Future, String, NSIUserInfo] = for {
        userInfo ← retrieveUserInfo()
        _ ← helpToSaveService.createAccount(userInfo).leftMap(submissionFailureToString)
      } yield userInfo

      // TODO: plug in actual pages below
      result.fold(
        error ⇒ {
          Logger.error(s"Could not create account: $error")
          Ok(uk.gov.hmrc.helptosavefrontend.views.html.core.stub_page(s"Account creation failed: $error"))
        },
        info ⇒ {
          Logger.debug(s"Successfully created account for ${info.nino}")
          Ok(uk.gov.hmrc.helptosavefrontend.views.html.core.stub_page("Successfully created account"))
        }
      )
  }

  def accessDenied: Action[AnyContent] = Action.async {
    implicit request ⇒
      Future.successful(Ok(views.html.access_denied()))
  }

  val notEligible: Action[AnyContent] = Action.async { implicit request ⇒
    Future.successful(Ok(views.html.core.not_eligible()))
  }

  private def retrieveUserInfo()(implicit hc: HeaderCarrier): EitherT[Future, String, NSIUserInfo] = {
    val session = sessionCacheConnector.get
    val userInfo: Future[Option[NSIUserInfo]] = session.map(_.flatMap(_.userInfo))

    EitherT(
      userInfo.map(_.fold[Either[String, NSIUserInfo]](
        Left("Session cache did not contain session data"))(Right(_))))
  }

  /**
    * Writes the user info to key-store if it exists and returns the associated [[CacheMap]]. If the user info
    * is not defined, don't do anything and return [[None]]. Any errors during writing to key-store are
    * captured as a [[String]] in the [[Either]].
    */
  private def writeToKeyStore(userDetails: Option[NSIUserInfo])(implicit hc: HeaderCarrier): EitherT[Future, String, Option[CacheMap]] = {
    // write to key-store
    val cacheMapOption: Option[Future[CacheMap]] =
      userDetails.map { details ⇒ sessionCacheConnector.put(HTSSession(Some(details))) }

    // use traverse to swap the option and future
    val cacheMapFuture: Future[Option[CacheMap]] =
      cacheMapOption.traverse[Future, CacheMap](identity)

    EitherT(
      cacheMapFuture.map[Either[String, Option[CacheMap]]](Right(_))
        .recover { case e ⇒ Left(s"Could not write to key-store: ${e.getMessage}") }
    )
  }

  private type EitherOrString[A] = Either[String, A]

  private def toNSIUserInfo(eligibilityResult: EligibilityResult): EitherT[Future, String, Option[NSIUserInfo]] = {
    val conversion: Option[Either[String, NSIUserInfo]] = eligibilityResult.result.map { result ⇒
      val validation = NSIUserInfo(result)
      validation.toEither.leftMap(
        errors ⇒ s"User info did not pass NS&I validity checks: ${errors.toList.mkString("; ")}"
      )
    }

    // use sequence to push the option into the either
    val result: Either[String, Option[NSIUserInfo]] = conversion.sequence[EitherOrString, NSIUserInfo]

    EitherT.fromEither[Future](result)
  }

  private def submissionFailureToString(failure: SubmissionFailure): String =
    s"Call to NS&I failed: message ID was ${failure.errorMessageId.getOrElse("-")}, " +
      s"error was ${failure.errorMessage}, error detail was ${failure.errorDetail}}"


  private lazy val redirectForAuthorisationCode =
    if (oauthConfig.enabled) {
      { (_: Request[AnyContent], _: Option[UserDetailsUrlWithNino]) ⇒
        Logger.info("Received request to get user details: redirecting to oauth obtain authorisation code")

        // we need to get an authorisation token from OAuth - redirect to OAuth here. When the authorisation
        // is done they'll redirect to the callback url we give them
        Redirect(
          oauthConfig.url,
          Map(
            "client_id" -> Seq(oauthConfig.clientID),
            "scope" -> oauthConfig.scopes,
            "response_type" -> Seq("code"),
            "redirect_uri" -> Seq(oauthConfig.callbackURL)
          ))
      }
    } else {
      { (request: Request[AnyContent], userDetailsUrlWithNino: Option[UserDetailsUrlWithNino]) ⇒
        // if the redirect to oauth is not enabled redirect straight to our 'confirm-details' endpoint
        // using the NINO as the authorisation code
        implicit val r = request

        userDetailsUrlWithNino.fold {
          Logger.error("NINO or user details URI not available")
          Redirect(routes.RegisterController.notEligible().absoluteURL())
        } { u ⇒
          val nino = u.nino
          Logger.info(s"Received request to get user details: redirecting to get user details using NINO $nino as authorisation code")
          Redirect(routes.RegisterController.confirmDetails(Some(nino), None, None, None).absoluteURL())
        }
      }
    }

  private def anError(json: JsonNode) = json.path("level").asText() == "error"

  private def instanceIs(json: JsonNode, s: String): Boolean = json.path("instance").path("pointer").asText() == s

  private def keywordIs(json: JsonNode, s: String): Boolean = json.path("keyword").asText() == s

  private def messageContains(json: JsonNode, s: Option[String]): Boolean = s.fold(true) { contents => json.path("message").asText().contains(contents) }

  def classify(message: ProcessingMessage, userInfo: NSIUserInfo): Either[String, Option[NSIUserInfo]] = {
    val json: JsonNode = message.asJson()
    if (anError(json)) {
      val firingRule = logClassificationKeys.find(rule => instanceIs(json, rule.instance) && keywordIs(json, rule.keyword) && messageContains(json, rule.messageContains))
      firingRule.fold(Right(Some(userInfo)): Either[String, Option[NSIUserInfo]]) { firedRule => Left(logClassificationRules.getOrElse(firedRule, "").format(userInfo.nino)) }
    } else {
      Right(Some(userInfo))
    }
  }

  private[controllers] def validateUserInfoAgainstSchema(userInfo: NSIUserInfo, schema: JsonNode): Either[String, Option[NSIUserInfo]] = {
    import scala.collection.JavaConversions._

    val userInfoJson = JsonLoader.fromString(Json.toJson(userInfo).toString)
    try {
      val report: ProcessingReport = jsonValidator.validate(schema, userInfoJson)
      val classification = report.iterator().toIterable.map(msg => classify(msg, userInfo)).find(_.isLeft)
      classification.fold(Right(Some(userInfo)): Either[String, Option[NSIUserInfo]]) {
        identity
      }
    } catch {
      case e: Exception => Left(e.getMessage)
    }
  }

  private[controllers] def futureDate(userInfo: NSIUserInfo): Either[String, Option[NSIUserInfo]] = {
    val today = java.time.LocalDate.now()
    if (userInfo.dateOfBirth.isAfter(today)) Left("FEATURE outgoing-json-validation: Date of birth in the future") else Right(Some(userInfo))
  }

  private[controllers] def before1800(userInfo: NSIUserInfo): Either[String, Option[NSIUserInfo]] = {
    val year = userInfo.dateOfBirth.getYear
    if (year < 1800) Left("FEATURE outgoing-json-validation: Date of birth before 1800") else Right(Some(userInfo))
  }
}

object RegisterController {

  object JSONValidationFeature {
    //For ICD v 1.2
    private[controllers] lazy val validationSchemaStr =
      """
        |{
        |  "$schema": "http://json-schema.org/schema#",
        |  "description": "A JSON schema to validate JSON as described in PPM-30048-UEM009-ICD001-HTS-HMRC-Interfaces v1.2.docx",
        |
        |  "type" : "object",
        |  "additionalProperties": false,
        |  "required": ["forename", "surname", "dateOfBirth", "contactDetails", "registrationChannel", "nino"],
        |  "properties" : {
        |    "forename" : {
        |      "type" : "string",
        |      "minLength": 1,
        |      "maxLength": 26,
        |      "pattern": "^[a-zA-Z][,a-zA-Z\\s\\.\\&\\-]*$"
        |    },
        |    "surname": {
        |      "type": "string",
        |      "minLength": 1,
        |      "maxLength": 300,
        |      "pattern": "^[a-zA-Z][`,'a-zA-Z\\s\\.\\&\\-]*$"
        |    },
        |    "dateOfBirth": {
        |      "type": "string",
        |      "minLength": 8,
        |      "maxLength": 8,
        |      "pattern": "^[0-9]{4}(01|02|03|04|05|06|07|08|09|10|11|12)[0-9]{2}$"
        |    },
        |    "contactDetails": {
        |      "type": "object",
        |      "additionalProperties": false,
        |      "required": ["address1", "address2", "postcode", "communicationPreference"],
        |      "properties": {
        |        "countryCode": {
        |          "type": "string",
        |          "minLength": 2,
        |          "maxLength": 2,
        |          "pattern": "[A-Z][A-Z]"
        |        },
        |        "address1": {
        |          "type": "string",
        |          "maxLength": 35
        |        },
        |        "address2": {
        |          "type": "string",
        |          "maxLength": 35
        |        },
        |        "address3": {
        |          "type": "string",
        |          "maxLength": 35
        |        },
        |        "address4": {
        |          "type": "string",
        |          "maxLength": 35
        |        },
        |        "address5": {
        |          "type": "string",
        |          "maxLength": 35
        |        },
        |        "postcode": {
        |          "type": "string",
        |          "maxLength": 10
        |        },
        |        "communicationPreference": {
        |          "type": "string",
        |          "minLength": 2,
        |          "maxLength": 2,
        |          "pattern": "00|02"
        |        },
        |        "phoneNumber": {
        |          "type": "string",
        |          "maxLength": 15
        |        },
        |        "email": {
        |          "type": "string",
        |          "maxLength": 254,
        |          "pattern": "^.{1,64}@.{1,252}$"
        |        }
        |      }
        |    },
        |    "registrationChannel": {
        |      "type": "string",
        |      "maxLength": 10,
        |      "pattern": "^online$|^callCentre$"
        |    },
        |    "nino" : {
        |      "type" : "string",
        |      "minLength": 9,
        |      "maxLength": 9,
        |      "pattern": "^(([A-CEGHJ-PR-TW-Z][A-CEGHJ-NPR-TW-Z])([0-9]{2})([0-9]{2})([0-9]{2})([A-D]{1})|((XX)(99)(99)(99)(X)))$"
        |    }
        |  }
        |}
      """.stripMargin

    lazy val validationSchema = JsonLoader.fromString(validationSchemaStr)
    lazy val featureLogger = Logger("outgoing-json-validation")
    lazy val jsonValidator: JsonValidator = JsonSchemaFactory.byDefault().getValidator

    case class LogClassificationRule(instance: String, keyword: String, messageContains: Option[String])

    object LogClassificationRule {
      def apply(instance: String, keyword: String): LogClassificationRule = LogClassificationRule(instance, keyword, None)

      def apply(instance: String, keyword: String, contains: String): LogClassificationRule = LogClassificationRule(instance, keyword, Some(contains))
    }

    val logClassificationRules = Map[LogClassificationRule, String](
      LogClassificationRule("/forename", "type") -> "For NINO %s: forename is wrong type, needs to be a string",
      LogClassificationRule("/forename", "minLength") -> "For NINO %s: forename is less than 1 char, needs to be at least 1 char",
      LogClassificationRule("/forename", "maxLength") -> "For NINO %s: forename is greater than 26 chars",
      LogClassificationRule("/forename", "pattern") -> "For NINO %s: forename contained an unrecognised char sequence",
      LogClassificationRule("", "required", "forename") -> "For NINO %s: forename was mandatory but not supplied",
      LogClassificationRule("/surname", "type") -> "For NINO %s: surname is wrong type, needs to be a string",
      LogClassificationRule("/surname", "minLength") -> "For NINO %s: surname is less than 1 char, needs to be at least 1 char",
      LogClassificationRule("/surname", "maxLength") -> "For NINO %s: surname is greater than 300 chars",
      LogClassificationRule("/surname", "pattern") -> "For NINO %s: surname contained an unrecognised char sequence",
      LogClassificationRule("", "required", "surname") -> "For NINO %s: surname was mandatory but not supplied",
      LogClassificationRule("/dateOfBirth", "type") -> "For NINO %s: date of birth is wrong type, needs to be a string",
      LogClassificationRule("/dateOfBirth", "minLength") -> "For NINO %s: date of birth is less than 8 chars, needs to be 8 chars",
      LogClassificationRule("/dateOfBirth", "maxLength") -> "For NINO %s: date of birth is greater than 8 chars, needs to be 8 chars",
      LogClassificationRule("/dateOfBirth", "pattern") -> "For NINO %s: date of birth contained an unrecognised char sequence",
      LogClassificationRule("", "required", "dateOfBirth") -> "For NINO %s: date of birth was mandatory but not supplied",
      LogClassificationRule("/contactDetails/countryCode", "type") -> "For NINO %s: country code is wrong type, needs to be a string",
      LogClassificationRule("/contactDetails/countryCode", "minLength") -> "For NINO %s: country code is less than 2 chars, needs to be 2 chars",
      LogClassificationRule("/contactDetails/countryCode", "maxLength") -> "For NINO %s: country code is greater than 2 chars, needs to be 2 chars",
      LogClassificationRule("/contactDetails/countryCode", "pattern") -> "For NINO %s: country code contained an unrecognised char sequence",
      LogClassificationRule("/contactDetails/address1", "type") -> "For NINO %s: address1 field is wrong type, needs to be a string",
      LogClassificationRule("/contactDetails/address1", "maxLength") -> "For NINO %s: address1 field is greater than 35 chars",
      LogClassificationRule("/contactDetails", "required", "address1") -> "For NINO %s: address1 field was mandatory but not supplied",
      LogClassificationRule("/contactDetails/address2", "type") -> "For NINO %s: address2 field is wrong type, needs to be a string",
      LogClassificationRule("/contactDetails/address2", "maxLength") -> "For NINO %s: address2 field is greater than 35 chars",
      LogClassificationRule("/contactDetails", "required", "address2") -> "For NINO %s: address2 field was mandatory but not supplied",
      LogClassificationRule("/contactDetails/address3", "type") -> "For NINO %s: address3 field is wrong type, needs to be a string",
      LogClassificationRule("/contactDetails/address3", "maxLength") -> "For NINO %s: address3 field is greater than 35 chars",
      LogClassificationRule("/contactDetails/address4", "type") -> "For NINO %s: address4 field is wrong type, needs to be a string",
      LogClassificationRule("/contactDetails/address4", "maxLength") -> "For NINO %s: address4 field is greater than 35 chars",
      LogClassificationRule("/contactDetails/address5", "type") -> "For NINO %s: address5 field is wrong type, needs to be a string",
      LogClassificationRule("/contactDetails/address5", "maxLength") -> "For NINO %s: address5 field is greater than 35 chars",
      LogClassificationRule("/contactDetails/postcode", "type") -> "For NINO %s: postcode field is wrong type, needs to be a string",
      LogClassificationRule("/contactDetails/postcode", "maxLength") -> "For NINO %s: postcode is greater than 10 chars",
      LogClassificationRule("/contactDetails", "required", "postcode") -> "For NINO %s: postcode was mandatory but not supplied",
      LogClassificationRule("/contactDetails/communicationPreference", "type") -> "For NINO %s: communication preference field is wrong type, needs to be a string",
      LogClassificationRule("/contactDetails/communicationPreference", "minLength") -> "For NINO %s: communications preference is less than 2 chars, needs to be 2 chars",
      LogClassificationRule("/contactDetails/communicationPreference", "maxLength") -> "For NINO %s: communications preference is greater than 2 chars, needs to be 2 chars",
      LogClassificationRule("/contactDetails/communicationPreference", "pattern") -> "For NINO %s: communications preference contained an unrecognised char sequence",
      LogClassificationRule("/contactDetails", "required", "communicationPreference") -> "For NINO %s: communications preference was mandatory but not supplied",
      LogClassificationRule("/contactDetails/phoneNumber", "maxLength") -> "For NINO %s: phone number is greater than 15 chars",
      LogClassificationRule("/contactDetails/email", "maxLength") -> "For NINO %s: email address is greater than 254 chars",
      LogClassificationRule("/registrationChannel", "maxLength") -> "For NINO %s: registration channel is greater than 10 chars",
      LogClassificationRule("/registrationChannel", "pattern") -> "For NINO %s: registration channel contained an unrecognised char sequence",
      LogClassificationRule("", "required", "registrationChannel") -> "For NINO %s: registration channel was mandatory but not supplied",
      LogClassificationRule("/nino", "minLength") -> "For NINO %s: nino is less than 9 chars, needs to be 9 chars",
      LogClassificationRule("/nino", "maxLength") -> "For NINO %s: nino is greater than 9 chars, needs to be 9 chars",
      LogClassificationRule("/nino", "pattern") -> "For NINO %s: nino contained an unrecognised char sequence",
      LogClassificationRule("", "required", "nino") -> "Nino was mandatory but not supplied"


    )
    val logClassificationKeys = logClassificationRules.keySet.seq
  }

  // details required to get an authorisation token from OAuth
  private[controllers] case class OAuthConfiguration(enabled: Boolean, url: String, clientID: String, callbackURL: String, scopes: List[String])

}
