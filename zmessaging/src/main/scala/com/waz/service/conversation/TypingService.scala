/*
 * Wire
 * Copyright (C) 2016 Wire Swiss GmbH
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program. If not, see <http://www.gnu.org/licenses/>.
 */
package com.waz.service.conversation

import java.util.Date

import com.waz.ZLog._
import com.waz.content.ConversationStorageImpl
import com.waz.model._
import com.waz.service._
import com.waz.sync.SyncServiceHandle
import com.waz.threading.{CancellableFuture, SerialDispatchQueue}
import com.waz.utils.RichFuture.processSequential
import com.waz.utils.events.{AggregatingSignal, EventContext, EventStream}

import scala.concurrent.Future
import scala.concurrent.duration._

class TypingService(conversations: ConversationStorageImpl, timeouts: Timeouts, lifecycle: ZmsLifecycle, sync: SyncServiceHandle) {
  import timeouts.typing._

  private implicit val ev = EventContext.Global
  private implicit val dispatcher = new SerialDispatchQueue(name = "TypingService")
  private implicit val tag: LogTag = logTagFor[TypingService]

  private var typing: ConvId Map IndexedSeq[TypingUser] = Map().withDefaultValue(Vector.empty)

  private var selfIsTyping: Option[(ConvId, Long)] = None

  private var stopTypingTimeout: CancellableFuture[Unit] = CancellableFuture.successful(())
  private var refreshIsTyping: CancellableFuture[Unit] = CancellableFuture.successful(())

  val onTypingChanged = EventStream[(ConvId, IndexedSeq[TypingUser])]()

  val typingEventStage = EventScheduler.Stage[TypingEvent]((c, es) => processSequential(es filter isRecent)(handleTypingEvent))

  lifecycle.lifecycleState.on(dispatcher) {
    case LifecycleState.UiActive => // fine
    case _ => stopTyping()
  }

  def typingUsers(conv: ConvId) = new AggregatingSignal[IndexedSeq[TypingUser], IndexedSeq[UserId]](onTypingChanged.filter(_._1 == conv).map(_._2), Future { typing(conv).map(_.id) }, { (_, updated) => updated.map(_.id) })

  def handleTypingEvent(e: TypingEvent): Future[Unit] = {
    conversations.getByRemoteId(e.convId) map {
      case Some(conv) => setUserTyping(conv.id, e.from, e.time, e.isTyping)
      case None => warn(s"Conversation ${e.convId} not found, ignoring.")
    }
  }

  def selfChangedInput(conv: ConvId): Future[Unit] = Future {
    stopTypingTimeout.cancel()
    selfIsTyping = Some((conv, System.currentTimeMillis))
    if (refreshIsTyping.isCompleted) postIsTyping(conv)
    stopTypingTimeout = CancellableFuture.delayed(stopTimeout) { stopTyping(conv) }
  }

  def selfClearedInput(conv: ConvId): Future[Unit] = Future {
    stopTypingTimeout.cancel()
    stopTyping(conv)
  }

  private def stopTyping(conv: ConvId): Unit = selfIsTyping match {
    case Some((`conv`, _)) =>
      refreshIsTyping.cancel()
      selfIsTyping = None
      sync.postTypingState(conv, typing = false)
    case _ => ()
  }

  private def stopTyping(): Unit = selfIsTyping foreach {
    case (conv, _) =>
      stopTypingTimeout.cancel()
      refreshIsTyping.cancel()
      selfIsTyping = None
      sync.postTypingState(conv, typing = false)
  }

  private def postIsTyping(conv: ConvId): Unit = {
    sync.postTypingState(conv, typing = true)
    refreshIsTyping = CancellableFuture.delayed(refreshDelay) { postIsTyping(conv) }
  }

  def getTypingUsers(conv: ConvId): CancellableFuture[IndexedSeq[UserId]] = dispatcher { typing(conv) map (_.id) }

  def isSelfTyping(conv: ConvId): CancellableFuture[Boolean] = dispatcher { selfIsTyping.exists(_._1 == conv) }

  private def setUserTyping(conv: ConvId, user: UserId, time: Date, isTyping: Boolean): Unit = {
    val current = typing(conv)
    current.find(_.id == user).foreach(_.cleanUp.cancel())

    if (!isTyping && current.exists(_.id == user)) {
      typing += conv -> current.filterNot(user == _.id)
      onTypingChanged ! (conv -> typing(conv))
    } else if (isTyping) {
      val cleanUp = CancellableFuture.delayed(receiverTimeout - (System.currentTimeMillis - time.getTime).millis) {
        setUserTyping(conv, user, new Date, isTyping = false)
      }
      val idx = current.indexWhere(_.id == user)
      if (idx == -1) {
        typing += conv -> (current :+ TypingUser(user, time, cleanUp))
        onTypingChanged ! (conv -> typing(conv))
      } else {
        typing += conv -> current.updated(idx, TypingUser(user, time, cleanUp))
      }
    }
  }

  def isRecent(event: TypingEvent): Boolean = System.currentTimeMillis - event.localTime.getTime < receiverTimeout.toMillis
}

case class TypingUser(id: UserId, time: Date, cleanUp: CancellableFuture[Unit])
