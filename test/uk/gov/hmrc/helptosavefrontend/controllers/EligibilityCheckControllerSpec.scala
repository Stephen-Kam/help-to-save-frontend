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

import cats.data.EitherT
import cats.instances.future._
import cats.syntax.either._
import play.api.http.Status
import play.api.i18n.MessagesApi
import play.api.libs.json.{JsValue, Json, Writes}
import play.api.mvc.{Result ⇒ PlayResult}
import play.api.test.FakeRequest
import play.api.test.Helpers._
import uk.gov.hmrc.auth.core._
import uk.gov.hmrc.helptosavefrontend.TestSupport
import uk.gov.hmrc.helptosavefrontend.connectors.SessionCacheConnector
import uk.gov.hmrc.helptosavefrontend.controllers.EligibilityCheckController.OAuthConfiguration
import uk.gov.hmrc.helptosavefrontend.models.EligibilityCheckError.{BackendError, MissingUserInfos}
import uk.gov.hmrc.helptosavefrontend.models.HtsAuth.AuthWithConfidence
import uk.gov.hmrc.helptosavefrontend.models.MissingUserInfo.{Contact, Email}
import uk.gov.hmrc.helptosavefrontend.models._
import uk.gov.hmrc.helptosavefrontend.services.{HelpToSaveService, JSONSchemaValidationService}
import uk.gov.hmrc.helptosavefrontend.util.HTSAuditor
import uk.gov.hmrc.http.cache.client.CacheMap
import uk.gov.hmrc.play.audit.http.connector.AuditResult
import uk.gov.hmrc.play.http.HeaderCarrier

import scala.concurrent.{Await, Future}
import scala.concurrent.duration._

class EligibilityCheckControllerSpec extends TestSupport {

  private val mockHtsService = mock[HelpToSaveService]

  val nino = "WM123456C"

  private val enrolment = Enrolment("HMRC-NI", Seq(EnrolmentIdentifier("NINO", nino)), "activated", ConfidenceLevel.L200)
  private val enrolments = Enrolments(Set(enrolment))

  private val mockAuthConnector = mock[PlayAuthConnector]
  val mockSessionCacheConnector: SessionCacheConnector = mock[SessionCacheConnector]
  val jsonSchemaValidationService = mock[JSONSchemaValidationService]
  val testOAuthConfiguration = OAuthConfiguration(true, "url", "client-ID", "callback", List("scope1", "scope2"))
  val mockAuditor = mock[HTSAuditor]

  val oauthAuthorisationCode = "authorisation-code"

  val controller = new EligibilityCheckController(
    fakeApplication.injector.instanceOf[MessagesApi],
    mockHtsService,
    mockSessionCacheConnector,
    jsonSchemaValidationService,
    fakeApplication,
    mockAuditor)(
    ec) {
    override val oauthConfig = testOAuthConfiguration
    override lazy val authConnector = mockAuthConnector
  }

  def mockEligibilityResult(nino: String, authorisationCode: String)(result: Either[EligibilityCheckError, EligibilityCheckResult]): Unit =
    (mockHtsService.checkEligibility(_: String, _: String)(_: HeaderCarrier))
      .expects(nino, authorisationCode, *)
      .returning(EitherT.fromEither[Future](result))

  def mockSendAuditEvent =
    (mockAuditor.sendEvent(_: HTSEvent))
      .expects(*)
      .returning(Future.successful(AuditResult.Success))

  def mockSessionCacheConnectorPut(result: Either[String, CacheMap]): Unit =
    (mockSessionCacheConnector.put(_: HTSSession)(_: Writes[HTSSession], _: HeaderCarrier))
      .expects(*, *, *)
      .returning(result.fold(
        e ⇒ Future.failed(new Exception(e)),
        Future.successful))


  def mockPlayAuthWithWithConfidence(): Unit =
    (mockAuthConnector.authorise(_: Predicate, _: Retrieval[Unit])(_: HeaderCarrier))
      .expects(AuthWithConfidence, *, *)
      .returning(Future.successful(()))

  def mockPlayAuthWithRetrievals[A, B](predicate: Predicate)(result: Enrolments): Unit =
    (mockAuthConnector.authorise(_: Predicate, _: Retrieval[Enrolments])(_: HeaderCarrier))
      .expects(predicate, *, *)
      .returning(Future.successful(result))

  def mockJsonSchemaValidation(input: NSIUserInfo)(result: Either[String, NSIUserInfo]) =
    (jsonSchemaValidationService.validate(_: JsValue))
      .expects(Json.toJson(input))
      .returning(result.map(Json.toJson(_)))


  "The EligiblityCheckController" when {

    "checking eligibility" must {
      def doConfirmDetailsRequest(): Future[PlayResult] = controller.getAuthorisation(FakeRequest())

      def doConfirmDetailsCallbackRequest(authorisationCode: String): Future[PlayResult] =
        controller.confirmDetails(Some(authorisationCode), None, None, None)(FakeRequest())


      "redirect to confirm-details with the NINO as the authorisation code if redirects to OAUTH are disabled" in {
        val controller = new EligibilityCheckController(
          fakeApplication.injector.instanceOf[MessagesApi],
          mockHtsService,
          mockSessionCacheConnector,
          jsonSchemaValidationService,
          fakeApplication,
          mockAuditor)(ec) {
          override val oauthConfig = testOAuthConfiguration.copy(enabled = false)
          override lazy val authConnector = mockAuthConnector
        }

        mockPlayAuthWithRetrievals(AuthWithConfidence)(enrolments)

        implicit val request = FakeRequest()
        val result = controller.getAuthorisation(request)
        status(result) shouldBe Status.SEE_OTHER
        redirectLocation(result) shouldBe Some(routes.EligibilityCheckController.confirmDetails(Some(nino), None, None, None).absoluteURL())
      }

      "return error if redirects to OAUTH are disabled and a NINO is not available" in {
        val register = new EligibilityCheckController(
          fakeApplication.injector.instanceOf[MessagesApi],
          mockHtsService,
          mockSessionCacheConnector,
          jsonSchemaValidationService,
          fakeApplication,
          mockAuditor)(ec) {
          override val oauthConfig = testOAuthConfiguration.copy(enabled = false)
          override lazy val authConnector = mockAuthConnector
        }

        mockPlayAuthWithRetrievals(AuthWithConfidence)(Enrolments(Set.empty[Enrolment]))

        implicit val request = FakeRequest()
        val result = register.getAuthorisation(request)
        status(result) shouldBe Status.INTERNAL_SERVER_ERROR
      }

      "redirect to OAuth to get an access token if enabled" in {
        mockPlayAuthWithRetrievals(AuthWithConfidence)(enrolments)

        val result = doConfirmDetailsRequest()
        status(result) shouldBe Status.SEE_OTHER

        val (url, params) = redirectLocation(result).get.split('?').toList match {
          case u :: p :: Nil ⇒
            val paramList = p.split('&').toList
            val keyValueSet = paramList.map(_.split('=').toList match {
              case key :: value :: Nil ⇒ key → value
              case _ ⇒ fail(s"Could not parse query parameters: $p")
            }).toSet

            u → keyValueSet

          case _ ⇒ fail("Could not parse URL with query parameters")
        }

        url shouldBe testOAuthConfiguration.url
        params shouldBe (testOAuthConfiguration.scopes.map("scope" → _).toSet ++ Set(
          "client_id" -> testOAuthConfiguration.clientID,
          "response_type" -> "code",
          "redirect_uri" -> testOAuthConfiguration.callbackURL
        ))
      }

      "return a 500 if there is an error while getting the authorisation token" in {
        mockPlayAuthWithRetrievals(AuthWithConfidence)(enrolments)
        val result = controller.confirmDetails(None, Some("uh oh"), None, None)(FakeRequest())
        status(result) shouldBe 500
      }

      "return user details if the user is eligible for help-to-save" in {
        val user = validUserInfo

        inSequence {
          mockPlayAuthWithRetrievals(AuthWithConfidence)(enrolments)
          mockEligibilityResult(nino, oauthAuthorisationCode)(Right(EligibilityCheckResult(Some(user))))
          mockJsonSchemaValidation(validNSIUserInfo)(Right(validNSIUserInfo))
          mockSessionCacheConnectorPut(Right(CacheMap("1", Map.empty[String, JsValue])))
          mockSendAuditEvent
        }

        val responseFuture: Future[PlayResult] = doConfirmDetailsCallbackRequest(oauthAuthorisationCode)
        val result = Await.result(responseFuture, 5.seconds)

        status(result) shouldBe Status.OK

        contentType(result) shouldBe Some("text/html")
        charset(result) shouldBe Some("utf-8")

        val html = contentAsString(result)

        html should include(user.forename)
        html should include(user.email)
        html should include(user.nino)
        html should include("Sign out")
      }

      "display a 'Not Eligible' page if the user is not eligible" in {

        inSequence {
          mockPlayAuthWithRetrievals(AuthWithConfidence)(enrolments)
          mockEligibilityResult(nino, oauthAuthorisationCode)(Right(EligibilityCheckResult(None)))
          mockSendAuditEvent
        }

        val result = doConfirmDetailsCallbackRequest(oauthAuthorisationCode)

        status(result) shouldBe Status.SEE_OTHER

        redirectLocation(result) shouldBe Some("/help-to-save/register/not-eligible")
      }

      "report missing user info back to the user" in {
        inSequence {
          mockPlayAuthWithRetrievals(AuthWithConfidence)(enrolments)
          mockEligibilityResult(nino, oauthAuthorisationCode)(Left(MissingUserInfos(Set(Email, Contact), nino)))
          mockSendAuditEvent
        }

        val responseFuture: Future[PlayResult] = doConfirmDetailsCallbackRequest(oauthAuthorisationCode)
        val result = Await.result(responseFuture, 5.seconds)

        status(result) shouldBe Status.OK

        contentType(result) shouldBe Some("text/html")
        charset(result) shouldBe Some("utf-8")

        val html = contentAsString(result)

        html should include("Email")
        html should include("Contact")
      }

      "return an error" must {

        def isError(result: Future[PlayResult]): Boolean =
          status(result) == 500

        // test if the given mock actions result in an error when `confirm_details` is called
        // on the controller
        def test(mockActions: ⇒ Unit): Unit = {
          mockActions
          val result = doConfirmDetailsCallbackRequest(oauthAuthorisationCode)
          isError(result) shouldBe true
        }

        "the nino is not available" in {
          test(
            mockPlayAuthWithRetrievals(AuthWithConfidence)(Enrolments(Set.empty[Enrolment]))
          )
        }

        "the eligibility check call returns with an error" in {
          test(
            inSequence {
              mockPlayAuthWithRetrievals(AuthWithConfidence)(enrolments)
              mockEligibilityResult(nino, oauthAuthorisationCode)(Left(BackendError("Oh no!", nino)))
            })
        }

        "if the JSON schema validation is unsuccessful" in {
          test(inSequence {
            mockPlayAuthWithRetrievals(AuthWithConfidence)(enrolments)
            mockEligibilityResult(nino, oauthAuthorisationCode)(Right(EligibilityCheckResult(Some(validUserInfo))))
            mockJsonSchemaValidation(validNSIUserInfo)(Left("uh oh"))
          })
        }

        "there is an error writing to the session cache" in {
          test(inSequence {
            mockPlayAuthWithRetrievals(AuthWithConfidence)(enrolments)
            mockEligibilityResult(nino, oauthAuthorisationCode)(Right(EligibilityCheckResult(Some(validUserInfo))))
            mockJsonSchemaValidation(validNSIUserInfo)(Right(validNSIUserInfo))
            mockSessionCacheConnectorPut(Left("Bang"))
          })
        }
      }
    }
  }
}