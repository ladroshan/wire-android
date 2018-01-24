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
package com.waz.zclient.participants

import android.content.Context
import android.support.v7.widget.RecyclerView
import android.view.{LayoutInflater, View, ViewGroup}
import android.widget.{AbsListView, TextView}
import com.waz.model.UserId
import com.waz.service.ZMessaging
import com.waz.utils.events.{EventContext, EventStream, Signal, SourceStream}
import com.waz.utils.returning
import com.waz.zclient.common.views.ChatheadWithTextFooter
import com.waz.zclient.conversation.ConversationController
import com.waz.zclient.utils.ViewUtils
import com.waz.zclient.{Injectable, Injector, R}

import scala.collection.mutable

class ParticipantsChatheadAdapter(numOfColumns: Int)(implicit context: Context, injector: Injector, eventContext: EventContext)
  extends RecyclerView.Adapter[ParticipantsChatheadAdapter.ViewHolder] with Injectable {
  import ParticipantsChatheadAdapter._

  private lazy val convController = inject[ConversationController]
  private lazy val zms = inject[Signal[ZMessaging]]

  private var items = List.empty[Either[UserId, Int]]

  val onClick = EventStream[UserId]()

  private lazy val users = for {
    z       <- zms
    convId  <- convController.currentConvId
    userIds <- z.membersStorage.activeMembers(convId)
    users   <- Signal.sequence(userIds.map(z.users.userSignal).toSeq: _*)
  } yield users

  private lazy val partitions = users.map { seq =>
    val (bots, users) = seq.partition(_.isWireBot)
    val (verified, unverified) = users.partition(_.isVerified)
    (unverified, verified, bots)
  }

  private lazy val positions = partitions.map { case (unverified, verified, bots) =>
    val list = mutable.ListBuffer[Either[UserId, Int]]()
    list.appendAll(unverified.map(data => Left(data.id)))

    if (verified.nonEmpty) {
      list.append(Right(SEPARATOR_VERIFIED)) // one empty slot for the unverified/verified separator
      list.appendAll(verified.map(data => Left(data.id)))
    }

    if (bots.nonEmpty) {
      list.append(Right(SEPARATOR_BOTS)) // one empty slot for the (unverified|verified)/bots separator
      list.appendAll(bots.map(data => Left(data.id)))
    }

    list.toList
  }

  positions.onUi { case list =>
    items = list
    notifyDataSetChanged()
  }

  override def onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder = viewType match {
    case t if t == CHATHEAD =>
      val view = LayoutInflater.from(parent.getContext).inflate(R.layout.participants_grid_chathead, parent, false)
      new ChatheadViewHolder(view.asInstanceOf[ChatheadWithTextFooter], onClick)
    case _ => new SeparatorViewHolder(getSeparatorView(parent))
  }

  override def onBindViewHolder(holder: ViewHolder, position: Int): Unit = (items(position), holder) match {
    case (Left(userId), h: ChatheadViewHolder)                                     => h.setUserId(userId)
    case (Right(sepType), h: SeparatorViewHolder) if sepType == SEPARATOR_VERIFIED => h.setTitle(R.string.pref_devices_device_verified)
    case (Right(sepType), h: SeparatorViewHolder) if sepType == SEPARATOR_BOTS     => h.setTitle(R.string.integrations_picker__section_title)
    case _ =>
  }

  def getSpanSize(position: Int): Int = getItemViewType(position) match {
    case t if t == SEPARATOR_VERIFIED || t == SEPARATOR_BOTS => numOfColumns
    case _                                                   => 1
  }

  override def getItemCount: Int = items.size

  override def getItemId(position: Int): Long = position

  override def getItemViewType(position: Int): Int = items(position) match {
    case Right(sepType) => sepType
    case _              => CHATHEAD
  }

  private def getSeparatorView(parent: ViewGroup): View = {
    returning(LayoutInflater.from(parent.getContext).inflate(R.layout.participants_separator_row, parent, false)) {
      _.setLayoutParams(new AbsListView.LayoutParams(
        parent.getMeasuredWidth,
        parent.getResources.getDimensionPixelSize(R.dimen.participants__verified_row__height)
      ))
    }
  }

}

object ParticipantsChatheadAdapter {
  val CHATHEAD = 0
  val SEPARATOR_VERIFIED = 1
  val SEPARATOR_BOTS = 2

  abstract class ViewHolder(itemView: View) extends RecyclerView.ViewHolder(itemView)

  class ChatheadViewHolder(chathead: ChatheadWithTextFooter, onClick: SourceStream[UserId]) extends ViewHolder(chathead) with View.OnClickListener {
    private var userId: Option[UserId] = None

    chathead.setOnClickListener(this)

    def setUserId(userId: UserId): Unit = {
      this.userId = Some(userId)
      chathead.setUserId(userId)
    }

    override def onClick(v: View): Unit = userId.foreach { id => this.onClick ! id }
  }

  class SeparatorViewHolder(separator: View) extends ViewHolder(separator) {

    def setTitle(title: Int): Unit = {
      ViewUtils.getView(separator, R.id.separator_title).asInstanceOf[TextView].setText(title)
    }
  }

}
