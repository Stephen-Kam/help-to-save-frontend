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

package uk.gov.hmrc.helptosavefrontend.connectors

import play.api.mvc.{Action, AnyContent, Request, Result}
import uk.gov.hmrc.helptosavefrontend.FrontendAuthConnector
import uk.gov.hmrc.play.frontend.auth._
import uk.gov.hmrc.play.frontend.auth.connectors.AuthConnector
import uk.gov.hmrc.play.frontend.auth.connectors.domain.{Accounts, ConfidenceLevel}
import uk.gov.hmrc.play.frontend.controller.FrontendController

import scala.concurrent.Future

trait HelpToSaveConnector extends FrontendController with Actions {

  override lazy val authConnector: AuthConnector = FrontendAuthConnector

  protected lazy val authenticationProvider: AuthenticationProvider = new GovernmentGateway {

    override def continueURL: String = "http://localhost:9000/help-to-save/declaration/"

    override def loginURL: String = "http://localhost:9949/auth-login-stub/auth-wizard/"
  }

  protected lazy val taxRegime = new TaxRegime {

    override def isAuthorised(accounts: Accounts): Boolean = true

    override def authenticationType: AuthenticationProvider = authenticationProvider
  }

  private val confidenceLevel = new IdentityConfidencePredicate(
    ConfidenceLevel.L0, Future.successful(Unauthorized))

  protected type AsyncPlayUserRequest = AuthContext => Request[AnyContent] => Future[Result]


  def authorisedUser(body: AsyncPlayUserRequest): Action[AnyContent] =
    AuthorisedFor(taxRegime, confidenceLevel).async(body)




}
