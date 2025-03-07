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
package com.waz.model

import com.waz.db.Dao
import com.waz.db.Col._
import com.waz.sync.client.EncodedEvent
import com.waz.utils.Identifiable
import com.waz.utils.wrappers.{DB, DBCursor}

object PushNotificationEvents {
  implicit object PushNotificationEventsDao extends Dao[PushNotificationEvent, Int] {
    private val PushId = id[Uid]('pushId).apply(_.pushId)
    private val Index = int('event_index)(_.index)
    private val Decrypted = bool('decrypted)(_.decrypted)
    private val EventStr = text('event)(_.event.str)
    private val Plain = opt(blob('plain))(_.plain)
    private val Transient = bool('transient)(_.transient)

    override val idCol = Index
    override val table = Table("PushNotificationEvents", PushId, Index, Decrypted, EventStr, Plain, Transient)

    override def apply(implicit cursor: DBCursor): PushNotificationEvent =
      PushNotificationEvent(PushId, Index, Decrypted, EncodedEvent(cursor.getString(3)), Plain, Transient)

    override def onCreate(db: DB): Unit = {
      super.onCreate(db)
    }

    def maxIndex()(implicit db: DB): Int = queryForLong(maxWithDefault(Index.name)).toInt

    def listDecrypted()(implicit db: DB): Vector[PushNotificationEvent] =
      list(db.query(table.name, null, s"${Decrypted.name} = 1", null, null, null, "event_index ASC"))

    def listEncrypted()(implicit db: DB): Vector[PushNotificationEvent] =
      list(db.query(table.name, null, s"${Decrypted.name} = 0", null, null, null, "event_index ASC"))
  }
}

final case class PushNotificationEvent(pushId:    Uid,
                                       index:     Int,
                                       decrypted: Boolean = false,
                                       event:     EncodedEvent,
                                       plain:     Option[Array[Byte]] = None,
                                       transient: Boolean) extends Identifiable[Int] {
  override val id: Int = index
}
