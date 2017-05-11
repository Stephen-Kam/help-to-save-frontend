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

import java.util.UUID

import play.api.mvc._
import uk.gov.hmrc.helptosavefrontend.FrontendAuthConnector
import uk.gov.hmrc.helptosavefrontend.auth.{HtsCompositePageVisibilityPredicate, HtsRegime}
import uk.gov.hmrc.play.frontend.auth._
import uk.gov.hmrc.play.frontend.auth.connectors.AuthConnector
import uk.gov.hmrc.play.frontend.controller.FrontendController

import scala.concurrent.Future

trait HelpToSaveController extends FrontendController with Actions with AuthActions

trait AuthActions  { self: Actions =>
  protected type AsyncPlayUserRequest = AuthContext => Request[AnyContent] => Future[Result]

  override def authConnector: AuthConnector = FrontendAuthConnector

  def AuthorisedHtsUserAction(body: AsyncPlayUserRequest): Action[AnyContent] =
    AuthorisedFor(HtsRegime, HtsCompositePageVisibilityPredicate).async(body)
}