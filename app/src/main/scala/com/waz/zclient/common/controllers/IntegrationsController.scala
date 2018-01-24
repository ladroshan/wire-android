/**
 * Wire
 * Copyright (C) 2018 Wire Swiss GmbH
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */
package com.waz.zclient.common.controllers

import android.content.Context
import com.waz.model.{ConvId, IntegrationData, IntegrationId, ProviderId}
import com.waz.service.ZMessaging
import com.waz.threading.Threading
import com.waz.utils.events.Signal
import com.waz.zclient.{Injectable, Injector}

import scala.concurrent.Future

class IntegrationsController(implicit injector: Injector, context: Context) extends Injectable {
  import Threading.Implicits.Background

  private lazy val integrations = inject[Signal[ZMessaging]].map(_.integrations)

  val searchQuery = Signal[String]("")

  def searchIntegrations = for {
    in        <- integrations
    startWith <- searchQuery
    data      <- in.searchIntegrations(startWith).map(Option(_)).orElse(Signal.const(Option.empty[Seq[IntegrationData]]))
  } yield data.map(_.toIndexedSeq)

  def getIntegration(pId: ProviderId, iId: IntegrationId): Future[IntegrationData] =
    integrations.head.flatMap(_.getIntegration(pId, iId))

  def addBot(cId: ConvId, pId: ProviderId, iId: IntegrationId): Future[Unit] =
    integrations.head.flatMap(_.addBot(cId, pId, iId))
}

