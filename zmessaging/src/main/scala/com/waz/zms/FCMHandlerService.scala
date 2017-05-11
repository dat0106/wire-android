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
package com.waz.zms

import android.util.Base64
import com.google.firebase.messaging.{FirebaseMessagingService, RemoteMessage}
import com.waz.ZLog.ImplicitTag._
import com.waz.ZLog._
import com.waz.model._
import com.waz.service.conversation.ConversationsContentUpdater
import com.waz.service.otr.OtrService
import com.waz.service.push.PushService
import com.waz.service.{ZMessaging, ZmsLifecycle}
import com.waz.sync.SyncServiceHandle
import com.waz.sync.client.PushNotification
import com.waz.threading.{SerialDispatchQueue, Threading}
import com.waz.utils.{JsonDecoder, LoggedTry}
import org.json
import org.json.JSONObject

import scala.collection.JavaConverters._
import scala.collection.breakOut
import scala.concurrent.Future
import scala.util.Try

/**
  * For more information, see: https://firebase.google.com/docs/cloud-messaging/android/receive
  */
class FCMHandlerService extends FirebaseMessagingService with ZMessagingService {
  import Threading.Implicits.Background

  lazy val pushSenderId = ZMessaging.currentGlobal.backend.pushSenderId
  lazy val accounts = ZMessaging.currentAccounts

  /**
    * According to the docs, we have 10 seconds to process notifications upon receiving the `remoteMessage`.
    * This should be plenty of time (?)
    */
  override def onMessageReceived(remoteMessage: RemoteMessage) = {

    import com.waz.zms.FCMHandlerService._

    Option(remoteMessage.getData).map(_.asScala.toMap).foreach { data =>
      verbose(s"onMessageReceived with data: $data")
      accounts.getCurrentZms.flatMap {
        case Some(zms) if zms.backend.pushSenderId.str == remoteMessage.getFrom =>
          FCMHandler(zms, data)
        case Some(_) => warn(s"Received FCM notification from unknown sender: ${remoteMessage.getFrom}. Ignoring..."); Future.successful({})
        case _ => warn("No zms instance available"); Future.successful({})
      }
    }
  }

  /**
    * Called when the device hasn't connected to the FCM server in over 1 month, or there are more than 100 FCM
    * messages available for this device on the FCM servers.
    *
    * Since we have our own missing notification tracking on websocket, we should be able to ignore this.
    */
  override def onDeletedMessages() = warn("onDeleteMessages")
}

object FCMHandlerService {
  class FCMHandler(otrService:   OtrService,
                   lifecycle:    ZmsLifecycle,
                   push:         PushService,
                   self:         UserId,
                   convsContent: ConversationsContentUpdater,
                   sync:         SyncServiceHandle) {

    private implicit val dispatcher = new SerialDispatchQueue(name = "FCMHandler")

    def handleMessage(data: Map[String, String]): Future[Unit] = {

      data.get(UserKey).map(UserId) match {
        case Some(intended) if intended != self =>
          warn(s"received notification for wrong user, ignoring"); Future.successful(())
        case _ => //If the 'UserKey' field is empty, we need to do a fetch to check if the notification was for us
          data match {
            case EncryptedContent(content, mac) =>
              decryptNotification(content, mac) flatMap {
                case Some(notification) => addNotificationToProcess(notification.id, Some(notification))
                case None => Future.successful(())
              }

            case UnencryptedNotification(notification) =>
              addNotificationToProcess(notification.id, Some(notification))

            case LargeNotification(nId) =>
              warn("Received large notification - need to trigger fetch")
              addNotificationToProcess(nId)

            case _ => warn(s"Unexpected notification"); Future.successful({})
          }
      }
    }

    private def decryptNotification(content: Array[Byte], mac: Array[Byte]) =
      otrService.decryptGcm(content, mac) map {
        case Some(DecryptedNotification(notification)) => Some(notification)
        case resp =>
          warn(s"gcm decoding failed: $resp")
          None
      }

    private def addNotificationToProcess(nId: Uid, not: Option[PushNotification] = None): Future[Unit] = {
      lifecycle.active.head flatMap {
        case true => Future.successful(()) // no need to process GCM when ui is active
        case _ =>
          verbose(s"addNotification: $nId")
          // call state events can not be directly dispatched like the other events because they might be stale
          not.foreach(n => syncCallStateForConversations(n.events.collect { case e: CallStateEvent => e }.map(_.withCurrentLocalTime())))
          Future.successful(push.cloudPushNotificationsToProcess.mutate(_ + nId))
      }
    }

    //TODO can be removed after calling v2
    private def syncCallStateForConversations(events: Seq[Event]): Unit = {
      val convs: Set[RConvId] = events.collect { case e: CallStateEvent => e.convId }(breakOut)
      Future.traverse(convs) { convId =>
        convsContent.processConvWithRemoteId(convId, retryAsync = false) { conv =>
          sync.syncCallState(conv.id, fromFreshNotification = true)
        }
      }
    }
  }

  object FCMHandler {
    def apply(zms: ZMessaging, data: Map[String, String]): Future[Unit] =
      new FCMHandler(zms.otrService, zms.lifecycle, zms.push, zms.selfUserId, zms.convsContent, zms.sync).handleMessage(data)
  }

  val DataKey = "data"
  val UserKey = "user"
  val TypeKey = "type"
  val MacKey = "mac"

  object EncryptedContent {
    def unapply(data: Map[String, String]): Option[(Array[Byte], Array[Byte])] =
      (data.get(UserKey), data.get(TypeKey), data.get(DataKey), data.get(MacKey)) match {
        case (Some(userId), Some("otr" | "cipher"), Some(content), Some(mac)) =>
          LoggedTry.local { (Base64.decode(content, Base64.NO_WRAP | Base64.NO_CLOSE), Base64.decode(mac, Base64.NO_WRAP | Base64.NO_CLOSE)) } .toOption
        case _ => None
      }
  }

  object UnencryptedNotification {
    def unapply(data: Map[String, String]): Option[PushNotification] =
      (data.get(UserKey), data.get(DataKey)) match {
        case (Some(user), Some(content)) =>
          LoggedTry.local(PushNotification.NotificationDecoder(new JSONObject(content))).toOption
        case _ => None
      }
  }

  object LargeNotification {
    def unapply(data: Map[String, String]): Option[Uid] = data.get(DataKey).flatMap(s => Try(JsonDecoder.decodeUid('id)(new json.JSONObject(s))).toOption)
  }

  object DecryptedNotification {
    def unapply(js: JSONObject): Option[PushNotification] = LoggedTry.local { PushNotification.NotificationDecoder(js.getJSONObject("data")) }.toOption
  }
}
