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

package hts.pages

import hts.utils.Configuration
import org.openqa.selenium.{By, WebDriver}

object AuthorityWizardPage {

  def goToPage()(implicit driver: WebDriver): Unit =
    driver.navigate().to(Configuration.authHost + "/auth-login-stub/gg-sign-in")

  def setRedirect(url: String)(implicit driver: WebDriver): Unit =
    driver.findElement(By.name("redirectionUrl")).sendKeys(url)

  def setNino(nino: String)(implicit driver: WebDriver): Unit =
    driver.findElement(By.name("nino")).sendKeys(nino)

  def setCredentialStrength(strength: String)(implicit driver: WebDriver): Unit =
    driver.findElement(By.name("credentialStrength")).sendKeys(strength)

  def setConfidenceLevel(level: Int)(implicit driver: WebDriver): Unit =
    driver.findElement(By.name("confidenceLevel")).sendKeys(level.toString)

  def submit()(implicit driver: WebDriver): Unit =
    driver.findElement(By.cssSelector("input.button")).click()

}
