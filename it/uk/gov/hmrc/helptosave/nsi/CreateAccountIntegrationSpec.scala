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

import org.scalatest.{FeatureSpec, GivenWhenThen, Matchers}
import play.api.libs.json.Json
import play.api.libs.ws.WSResponse
import uk.gov.hmrc.helptosavefrontend.config.FrontendAppConfig._
import uk.gov.hmrc.helptosavefrontend.models.NSIUserInfo
import uk.gov.hmrc.helptosavefrontend.models.NSIUserInfo.ContactDetails
import play.api.libs.ws.WSClient

class CreateAccountIntegrationSpec extends FeatureSpec with GivenWhenThen with Matchers with ScenarioHelpers {

  val wsClient = app.injector.instanceOf[WSClient]

  def createAccount (nSIUserInfo: NSIUserInfo): WSResponse = {
    val payload = Json.obj(
      "forename" -> nSIUserInfo.forename,
      "surname" -> nSIUserInfo.surname,
      "dateOfBirth" → nSIUserInfo.dateOfBirth,
      "nino" → nSIUserInfo.nino,
      "contactDetails" → nSIUserInfo.contactDetails,
      "registrationChannel" → "online")
    wsClient
      .url(nsiUrl)
      .post(payload)
      .futureValue
  }

  feature("Create a new help to save account for a fake user") {

    scenario("User is advised that their surname is missing") {

      Given("A basic request to apply for a help to save account")
      val contactDetails = ContactDetails("address line1", "address line2", Some("line3"), Some("line4"), None, "BN43 XXX", Some("GB"), "sarah@gmail.com", None, "02")
      val userInfo = NSIUserInfo("Forename", "Surname", new LocalDate(1999-12-12), "AE123456X", contactDetails, "online")


      When("I call the create account endpoint")
      val createAccountResponse = createAccount(userInfo)

      Then("I receive a 400 BAD_REQUEST response")
      createAccountResponse.status shouldBe BAD_REQUEST


    }
  }


}
