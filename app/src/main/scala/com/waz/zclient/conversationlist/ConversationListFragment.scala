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
package com.waz.zclient.conversationlist

import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.support.v7.widget.{LinearLayoutManager, RecyclerView}
import android.view.View.{GONE, VISIBLE}
import android.view.animation.Animation
import android.view.{LayoutInflater, View, ViewGroup}
import android.widget.{ImageView, LinearLayout}
import com.waz.ZLog.ImplicitTag._
import com.waz.ZLog._
import com.waz.model._
import com.waz.model.ConversationData.ConversationType
import com.waz.model.otr.Client
import com.waz.service.ZMessaging
import com.waz.threading.Threading
import com.waz.utils.events.{Signal, Subscription}
import com.waz.utils.returning
import com.waz.zclient.common.controllers.{IntegrationsController, UserAccountsController}
import com.waz.zclient.common.controllers.global.AccentColorController
import com.waz.zclient.common.views.PickableElement
import com.waz.zclient.controllers.navigation.{INavigationController, Page}
import com.waz.zclient.conversation.ConversationController
import com.waz.zclient.conversationlist.views.{ArchiveTopToolbar, ConversationListTopToolbar, IntegrationTopToolbar, NormalTopToolbar}
import com.waz.zclient.core.stores.conversation.ConversationChangeRequester
import com.waz.zclient.messages.UsersController
import com.waz.zclient.pages.BaseFragment
import com.waz.zclient.pages.main.conversation.controller.IConversationScreenController
import com.waz.zclient.pages.main.conversationlist.ConversationListAnimation
import com.waz.zclient.pages.main.conversationlist.views.ListActionsView
import com.waz.zclient.pages.main.conversationlist.views.ListActionsView.Callback
import com.waz.zclient.pages.main.conversationlist.views.listview.SwipeListView
import com.waz.zclient.pages.main.pickuser.controller.IPickUserController
import com.waz.zclient.preferences.PreferencesActivity
import com.waz.zclient.ui.text.TypefaceTextView
import com.waz.zclient.usersearch.views.{SearchBoxView, SearchEditText}
import com.waz.zclient.utils.ContextUtils
import com.waz.zclient.utils.ContextUtils._
import com.waz.zclient.utils.RichView
import com.waz.zclient.{FragmentHelper, OnBackPressedListener, R, ViewHolder}
import com.waz.zclient.utils.RichView

/**
  * Due to how we use the NormalConversationListFragment - it gets replaced by the ArchiveConversationListFragment or
  * PickUserFragment, thus destroying its views - we have to be careful about when assigning listeners to signals and
  * trying to instantiate things in onViewCreated - be careful to tare them down again.
  */
abstract class ConversationListFragment extends BaseFragment[ConversationListFragment.Container] with FragmentHelper {

  implicit lazy val context = getContext

  val layoutId: Int
  lazy val userAccountsController = inject[UserAccountsController]
  lazy val conversationController = inject[ConversationController]
  lazy val usersController        = inject[UsersController]
  lazy val screenController       = inject[IConversationScreenController]
  lazy val pickUserController     = inject[IPickUserController]

  protected var subs = Set.empty[Subscription]

  lazy val topToolbar: ViewHolder[_ <: ConversationListTopToolbar] = view[ConversationListTopToolbar](R.id.conversation_list_top_toolbar)
  lazy val adapter = returning(new ConversationListAdapter) { a =>
    a.setMaxAlpha(getResourceFloat(R.dimen.list__swipe_max_alpha))
    (for {
      mode <- a.currentMode
      user <- userAccountsController.currentUser
    } yield (mode, user)).on(Threading.Ui) {
      case (mode,user) => topToolbar.get.setTitle(mode, user)
    }

    a.onConversationClick { conv => conversationClicked(conv) }
    a.onConversationLongClick { conv => conversationLongClicked(conv) }
  }

  def conversationClicked(conv: ConversationData): Unit = {
    verbose(s"handleItemClick, switching conv to ${conv.id}")
    conversationController.selectConv(Option(conv.id), ConversationChangeRequester.CONVERSATION_LIST)
  }

  def conversationLongClicked(conv: ConversationData): Unit =
    if (conv.convType != ConversationType.Group &&
      conv.convType != ConversationType.OneToOne &&
      conv.convType != ConversationType.WaitForConnection) {
    } else
      screenController.showConversationMenu(IConversationScreenController.CONVERSATION_LIST_LONG_PRESS, conv.id)

  lazy val conversationListView = returning(view[SwipeListView](R.id.conversation_list_view)) { rv =>
    userAccountsController.currentUser.onChanged.onUi(_ => rv.scrollToPosition(0))
  }

  lazy val conversationsListScrollListener = new RecyclerView.OnScrollListener {
    override def onScrolled(recyclerView: RecyclerView, dx: Int, dy: Int) = {
      topToolbar.get.setScrolledToTop(!recyclerView.canScrollVertically(-1))
    }
  }

  override def onCreateView(inflater: LayoutInflater, container: ViewGroup, savedInstanceState: Bundle) =
    inflater.inflate(layoutId, container, false)

  override def onViewCreated(view: View, savedInstanceState: Bundle) = {
    super.onViewCreated(view, savedInstanceState)
    conversationListView.setLayoutManager(new LinearLayoutManager(getContext))
    conversationListView.setAdapter(adapter)
    conversationListView.setAllowSwipeAway(true)
    conversationListView.setOverScrollMode(View.OVER_SCROLL_NEVER)
    conversationListView.addOnScrollListener(conversationsListScrollListener)
  }

  override def onDestroyView() = {
    conversationListView.removeOnScrollListener(conversationsListScrollListener)
    subs.foreach(_.destroy())
    super.onDestroyView()
  }

  override def onCreateAnimation(transit: Int, enter: Boolean, nextAnim: Int): Animation = {
    if (nextAnim == 0)
      super.onCreateAnimation(transit, enter, nextAnim)
    else if (pickUserController.isHideWithoutAnimations)
      new ConversationListAnimation(0, getDimenPx(R.dimen.open_new_conversation__thread_list__max_top_distance), enter, 0, 0, false, 1f)
    else if (enter)
      new ConversationListAnimation(0, getDimenPx(R.dimen.open_new_conversation__thread_list__max_top_distance), enter,
        getInt(R.integer.framework_animation_duration_long), getInt(R.integer.framework_animation_duration_medium), false, 1f)
    else new ConversationListAnimation(0, getDimenPx(R.dimen.open_new_conversation__thread_list__max_top_distance), enter,
      getInt(R.integer.framework_animation_duration_medium), 0, false, 1f)
  }
}

object ArchiveListFragment{
  val TAG = ArchiveListFragment.getClass.getSimpleName
}

class ArchiveListFragment extends ConversationListFragment with OnBackPressedListener {

  override val layoutId = R.layout.fragment_archive_list
  override lazy val topToolbar = view[ArchiveTopToolbar](R.id.conversation_list_top_toolbar)

  override def onViewCreated(view: View, savedInstanceState: Bundle) = {
    super.onViewCreated(view, savedInstanceState)
    adapter.currentMode ! ConversationListAdapter.Archive
    subs += topToolbar.onRightButtonClick(_ => Option(getContainer).foreach(_.closeArchive()))
  }

  override def onBackPressed() = {
    Option(getContainer).foreach(_.closeArchive())
    true
  }
}

class ChooseConversationFragment extends ConversationListFragment with OnBackPressedListener {
  private lazy val providerId = ProviderId(getArguments.getString(ChooseConversationFragment.ProviderId))
  private lazy val integrationId = IntegrationId(getArguments.getString(ChooseConversationFragment.IntegrationId))

  private lazy val integrationsController = inject[IntegrationsController]

  override val layoutId: Int = R.layout.fragment_choose_conversation

  private var searchBoxView: SearchEditText = null
  private var pictureView: ViewHolder[ImageView] = _

  override def onViewCreated(v: View, savedInstanceState: Bundle) = {
    super.onViewCreated(v, savedInstanceState)
    adapter.currentMode ! ConversationListAdapter.Integration

    val nameView = returning(view[TypefaceTextView](R.id.integration_name)){ nv =>
      integration.map(_.name).onUi { name =>nv.foreach(_.setText(name)) }
    }

    val summaryView = returning(view[TypefaceTextView](R.id.integration_summary)){ sv =>
      integration.map(_.summary).onUi { summary => sv.foreach(_.setText(summary)) }
    }

    val descriptionView = returning(view[TypefaceTextView](R.id.integration_description)){ dv =>
      integration.map(_.description).onUi { description => dv.foreach(_.setText(description)) }
    }

    pictureView = returning(view[ImageView](R.id.integration_picture)){ pv =>
      pv.foreach(_.setImageDrawable(ContextUtils.getDrawable(R.drawable.services)))
    }

    integrationsIds ! (providerId, integrationId)
  }

  override lazy val topToolbar = returning(view[IntegrationTopToolbar](R.id.integration_top_toolbar)){ t =>
    t.foreach(_.closeButtonEnd.onClick(close()))
    t.foreach(_.backButton.onClick(goBack()))
    integration.onUi { data => t.foreach(_.setTitle(data)) }
  }

  override def onBackPressed() = goBack()

  override def conversationClicked(conv: ConversationData): Unit = {
    import Threading.Implicits.Ui
    integrationsController.addBot(conv.id, providerId, integrationId).map { _ =>
      close()
      conversationController.selectConv(Option(conv.id), ConversationChangeRequester.CONVERSATION_LIST)
    }
  }

  final val searchBoxViewCallback: SearchBoxView.Callback = new SearchBoxView.Callback() {
    override def onRemovedTokenSpan(element: PickableElement): Unit = {}

    override def onFocusChange(hasFocus: Boolean): Unit = {}

    override def onClearButton(): Unit = {}

    override def afterTextChanged(s: String): Unit = {
      val filter = searchBoxView.getSearchFilter
      if (filter.isEmpty && searchBoxView.getElements.isEmpty){}
    }

  }

  def goBack(): Boolean = {
    getFragmentManager.popBackStack()
    // not necessary for the actual transition, but it may be used to trigger some listeners waiting for it
    inject[INavigationController].setLeftPage(Page.PICK_USER, ChooseConversationFragment.Tag)
    true
  }

  def close(): Boolean = {
    getFragmentManager.popBackStack()
    getFragmentManager.popBackStack()
    inject[INavigationController].setLeftPage(Page.CONVERSATION_LIST, ChooseConversationFragment.Tag)
    true
  }

  private val integrationsIds = Signal[(ProviderId, IntegrationId)]()
  private val integration = integrationsIds.flatMap {
    case (pId, iId) => Signal.future(integrationsController.getIntegration(pId, iId))
  }
}

object ChooseConversationFragment {
  val Tag = classOf[ChooseConversationFragment].getName
  val IntegrationId = "ARG_INTEGRATION_ID"
  val ProviderId = "ARG_PROVIDER_ID"

  def newInstance(providerId: ProviderId, integrationId: IntegrationId): ChooseConversationFragment =
    returning(new ChooseConversationFragment) {
      _.setArguments(returning(new Bundle) { b =>
        b.putString(ProviderId, providerId.str)
        b.putString(IntegrationId, integrationId.str)
      })
    }
}

object NormalConversationListFragment {
  val TAG = NormalConversationListFragment.getClass.getSimpleName
}

class NormalConversationFragment extends ConversationListFragment {

  override val layoutId = R.layout.fragment_conversation_list

  lazy val zms = inject[Signal[ZMessaging]]
  lazy val accentColor = inject[AccentColorController].accentColor
  lazy val incomingClients = for{
    z       <- zms
    acc     <- z.account.accountData
    clients <- acc.clientId.fold(Signal.empty[Seq[Client]])(aid => z.otrClientsStorage.incomingClientsSignal(z.selfUserId, aid))
  } yield clients

  private lazy val unreadCount = (for {
    Some(accountId) <- ZMessaging.currentAccounts.activeAccountPref.signal
    count  <- userAccountsController.unreadCount.map(_.filterNot(_._1 == accountId).values.sum)
  } yield count).orElse(Signal.const(0))

  lazy val hasConversationsAndArchive = for {
    z <- zms
    convs <- z.convsStorage.convsSignal
  } yield {
    (convs.conversations.exists(c => !c.archived && !c.hidden && !Set(ConversationType.Self, ConversationType.Unknown).contains(c.convType)),
    convs.conversations.exists(c => c.archived && !c.hidden && !Set(ConversationType.Self, ConversationType.Unknown).contains(c.convType)))
  }

  lazy val archiveEnabled = hasConversationsAndArchive.map(_._2)

  private val waitingAccount = Signal[Option[AccountId]](None)

  lazy val loading = for {
    Some(waitingAcc) <- waitingAccount
    adapterAccount <- adapter.conversationListData.map(_._1)
  } yield waitingAcc != adapterAccount

  override lazy val topToolbar = returning(view[NormalTopToolbar](R.id.conversation_list_top_toolbar)) { v =>
    accentColor.map(_.getColor).onUi(v.setIndicatorColor(_))
    Signal(unreadCount, incomingClients).onUi {
      case (count, clients) => v.setIndicatorVisible(clients.nonEmpty || count > 0)
    }
  }

  lazy val loadingListView = view[View](R.id.conversation_list_loading_indicator)
  lazy val listActionsView = returning(view[ListActionsView](R.id.lav__conversation_list_actions)){ v =>
   archiveEnabled.onUi(v.setArchiveEnabled(_))
    hasConversationsAndArchive.map {
      case (false, false) => true
      case _ => false
    }.onUi(v.setContactsCentered(_))
  }

  lazy val noConvsTitle = returning(view[TypefaceTextView](R.id.conversation_list_empty_title)) { v =>
    hasConversationsAndArchive.map {
      case (false, true) => Some(R.string.all_archived__header)
      case _ => None
    }.onUi(_.foreach(v.setText(_)))
    hasConversationsAndArchive.map {
      case (false, true) => VISIBLE
      case _ => GONE
    }.onUi(v.setVisibility(_))
  }

  private lazy val noConvsMessage = returning(view[LinearLayout](R.id.empty_list_message)) { v =>
    hasConversationsAndArchive.map {
      case (false, false) => VISIBLE
      case _ => GONE
    }.onUi(v.setVisibility(_))
  }

  lazy val listActionsScrollListener = new RecyclerView.OnScrollListener {
    override def onScrolled(recyclerView: RecyclerView, dx: Int, dy: Int) =
      listActionsView.setScrolledToBottom(!recyclerView.canScrollVertically(1))
  }

  lazy val listActionsCallback = new Callback {
    override def onAvatarPress() =
      getControllerFactory.getPickUserController.showPickUser(IPickUserController.Destination.CONVERSATION_LIST)

    override def onArchivePress() =
      Option(getContainer).foreach(_.showArchive())
  }

  override def onViewCreated(v: View, savedInstanceState: Bundle) = {
    super.onViewCreated(v, savedInstanceState)

    conversationListView.addOnScrollListener(listActionsScrollListener)

    adapter.currentMode ! ConversationListAdapter.Normal

    subs += loading.onUi {
      case true => showLoading()
      case false =>
        hideLoading()
        waitingAccount ! None
    }
    subs += topToolbar.onRightButtonClick(_ => getActivity.startActivityForResult(PreferencesActivity.getDefaultIntent(getContext), PreferencesActivity.SwitchAccountCode))

    listActionsView.setCallback(listActionsCallback)
    listActionsView.setScrolledToBottom(!conversationListView.canScrollVertically(1))

    noConvsMessage.foreach(_.onClick(
      inject[IPickUserController].showPickUser(IPickUserController.Destination.CONVERSATION_LIST)
    ))

    //initialise lazy vals
    loadingListView
    noConvsTitle


    Option(findById[ImageView](v, R.id.empty_list_arrow)).foreach { v =>
      val drawable = DownArrowDrawable()
      v.setImageDrawable(drawable)
      drawable.setColor(Color.WHITE)
      drawable.setAlpha(102)
    }
  }

  override def onDestroyView() = {
    conversationListView.removeOnScrollListener(listActionsScrollListener)
    listActionsView.setCallback(null)
    super.onDestroyView()
  }

  private def showLoading(): Unit = {
    conversationListView.setVisibility(View.INVISIBLE)
    loadingListView.setVisibility(VISIBLE)
    loadingListView.setAlpha(1f)
    listActionsView.setAlpha(0.5f)
    topToolbar.setLoading(true)
  }

  private def hideLoading(): Unit = {
    if (conversationListView.getVisibility != VISIBLE) {
      conversationListView.setVisibility(VISIBLE)
      conversationListView.setAlpha(0f)
      conversationListView.animate().alpha(1f).setDuration(500)
      listActionsView.animate().alpha(1f).setDuration(500)
      loadingListView.animate().alpha(0f).setDuration(500).withEndAction(new Runnable {
        override def run() = loadingListView.view.foreach(_.setVisibility(GONE))
      })
    }
    topToolbar.setLoading(false)
  }

  override def onActivityResult(requestCode: Int, resultCode: Int, data: Intent) = {
    if (requestCode == PreferencesActivity.SwitchAccountCode && data != null) {
      showLoading()
      waitingAccount ! Some(AccountId(data.getStringExtra(PreferencesActivity.SwitchAccountExtra)))
    }
  }
}


object ConversationListFragment {
  trait Container {
    def showArchive(): Unit
    def closeArchive(): Unit
  }

  def newNormalInstance(): ConversationListFragment = {
    new NormalConversationFragment()
  }

  def newArchiveInstance(): ConversationListFragment = {
    new ArchiveListFragment()
  }
}
