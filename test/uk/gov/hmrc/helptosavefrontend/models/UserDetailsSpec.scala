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

package uk.gov.hmrc.helptosavefrontend.models

import org.scalacheck.Arbitrary
import org.scalatest.prop.GeneratorDrivenPropertyChecks
import org.scalatest.{Matchers, WordSpec}
import play.api.libs.json.{JsSuccess, Json}

class UserDetailsSpec extends WordSpec with Matchers with GeneratorDrivenPropertyChecks {

  "UserDetails" must {
    "have a JSON Format instance" in {
      implicit val userInfoArb: Arbitrary[UserInfo] = Arbitrary(userInfoGen)

      forAll { user: UserInfo ⇒
        val json = Json.toJson(user)
        Json.fromJson[UserInfo](json) shouldBe JsSuccess(user)
      }
    }
  }
}
