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

package uk.gov.hmrc.helptosave.nsi

import java.time.LocalDate

import com.github.tomakehurst.wiremock.client.WireMock._
import org.scalatest._
import org.scalatest.concurrent.ScalaFutures
import org.scalatest.time.{Millis, Seconds, Span}
import org.scalatestplus.play.OneServerPerSuite
import uk.gov.hmrc.helptosavefrontend.models.NSIUserInfo
import uk.gov.hmrc.helptosavefrontend.models.NSIUserInfo.ContactDetails
import play.api.http.{HeaderNames, Status}
import play.api.libs.json.Json
import play.api.libs.ws.{WSClient, WSResponse}
import play.api.test.FakeApplication

class CreateAccountIntegrationSpec
  extends WordSpec
    with  WiremockHelper
    with OneServerPerSuite
    with LoginStub
    with BeforeAndAfterEach
    with BeforeAndAfterAll
    with ScalaFutures
    with Matchers {

  implicit val defaultPatience: PatienceConfig =
    PatienceConfig(timeout = Span(5, Seconds), interval = Span(50, Millis))

  override def beforeEach(): Unit = {
    super.beforeEach()
    resetWiremock()
  }

  override def beforeAll(): Unit = {
    super.beforeAll()
    startWiremock()
  }

  override def afterAll(): Unit = {
    super.afterAll()
    stopWiremock()
  }

  val mockHost = WiremockHelper.wiremockHost
  val mockPort = WiremockHelper.wiremockPort
  val mockUrl = s"http://$mockHost:$mockPort"

  val wsClient = app.injector.instanceOf[WSClient]

  override implicit lazy val app: FakeApplication = FakeApplication(additionalConfiguration = Map(
    "play.filters.csrf.header.bypassHeaders.X-Requested-With" -> "*",
    "play.filters.csrf.header.bypassHeaders.Csrf-Token" -> "nocheck",
    "auditing.consumer.baseUri.host" -> s"$mockHost",
    "auditing.consumer.baseUri.port" -> s"$mockPort",
    "microservice.services.gg-reg-fe.url" -> s"wibble",
    "microservice.services.cachable.session-cache.host" -> s"$mockHost",
    "microservice.services.cachable.session-cache.port" -> s"$mockPort",
    "microservice.services.cachable.session-cache.domain" -> "keystore",
    "microservice.services.auth.host" -> s"$mockHost",
    "microservice.services.auth.port" -> s"$mockPort"
  ))


  def createAccountHelpToSave(): WSResponse =
    wsClient
      .url(s"http://localhost:$port/help-to-save/register/nsi")
      .withHeaders(HeaderNames.COOKIE -> getSessionCookie())
      .get()
      .futureValue



  val userId = "/auth/oid/1234567890"

  def setupSimpleAuthMocks() = {
    stubPost("/write/audit", 200, """{"x":2}""")
    stubGet("/auth/authority", 200,
      s"""
         |{
         |"uri":"${userId}",
         |"loggedInAt": "2017-06-07T14:57:09.522Z",
         |"previouslyLoggedInAt": "2017-06-07T14:48:24.841Z",
         |"credentials":{"gatewayId":"xxx2"},
         |"accounts":{},
         |"levelOfAssurance": "2",
         |"confidenceLevel" : 200,
         |"credentialStrength": "strong",
         |"legacyOid":"1234567890",
         |"userDetailsLink":"xxx3",
         |"ids":"/auth/ids"
         |}""".stripMargin
    )
    stubGet("/auth/ids", 200, """{"internalId":"Int-xxx","externalId":"Ext-xxx"}""")
  }

  def stubKeystore(session: String, nSIUserInfo: NSIUserInfo) = {
    val keystoreUrl = s"/keystore/company-regisation-frontend/${session}"
    stubFor(get(urlMatching(keystoreUrl))
      .willReturn(
        aResponse().
          withStatus(200).
          withBody(
            s"""{
                |"id": "${session}",
                |"data": ${Json.toJson(nSIUserInfo).toString()}
                |}""".stripMargin
          )
      )
    )
  }


  "The create account endpoint" when {

    "the user info is in the keystore" must {

      "return a 200 if the account creation is successful" in {
        val contactDetails = ContactDetails("address line1", "address line2", Some("line3"), Some("line4"), None, "BN43 XXX",
          Some("GB"), "sarah@gmail.com", None, "02")
        val userInfo =
          NSIUserInfo("Forename", "Surname", LocalDate.of(1999,12,12), "AE12345XX", contactDetails, "online")

        setupSimpleAuthMocks()
        //stubSuccessfulLogin()
        stubKeystore(SessionId, userInfo)
        stubPost("/auth/authorise", 200, "{}")
        val createAccountResponse = createAccountHelpToSave()
        createAccountResponse.status shouldBe Status.OK

      }
    }
  }

}
