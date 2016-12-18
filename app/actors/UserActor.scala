package actors

import java.util.Date

import actors.UserActor._
import akka.actor.{Actor, ActorRef, Props}
import play.api.Logger
import play.api.libs.iteratee._
import play.api.libs.ws.WSClient
import reactivemongo.bson._
import services.StickleDb

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future

object UserActor {
  def props(phoneNumber: String, displayName: String)(implicit ws: WSClient) = Props(new UserActor(phoneNumber, displayName))

  case class CheckState(target: String, inbound: Boolean)

  val closed: String = "closed"
  val accepted: String = "accepted"
  val unaccepted: String = "un-accepted"
  val rejected: String = "rejected"
  val open: String = "open"
  val completed: String = "completed"

  case class StickleState(id: Option[String], originator: String, originatorDisplayName: String, recipient: String, createdDate: Date, state: String)

  implicit val stickleStateDocumentReader: BSONDocumentReader[StickleState] = new BSONDocumentReader[StickleState] {
    override def read(bson: BSONDocument): StickleState = {
      Logger(this.getClass).trace(s"reading: ${bson.getAs[BSONObjectID]("_id").map(_.stringify)}")
      StickleState(
        bson.getAs[BSONObjectID]("_id").map(_.stringify),
        bson.getAs[String]("originator").get,
        bson.getAs[String]("originatorDisplayName").getOrElse(""),
        bson.getAs[String]("recipient").get,
        new Date(bson.getAs[BSONDateTime]("createdDate").get.value),
        bson.getAs[String]("status").get
      )
    }
  }

  implicit val stickleStateDocumentWriter: BSONDocumentWriter[StickleState] = new BSONDocumentWriter[StickleState] {
    override def write(state: StickleState): BSONDocument = BSONDocument(
      "_id" -> state.id,
      "originator" -> state.originator,
      "originatorDisplayName" -> state.originatorDisplayName,
      "recipient" -> state.recipient,
      "createdDate" -> BSONDateTime(state.createdDate.getTime),
      "status" -> state.state
    )
  }
}

class UserActor(phoneNumber: String, displayName: String)(implicit ws: WSClient) extends Actor with StickleDb {

  Logger(this.getClass).trace(s"UserActor - phoneNumber: $phoneNumber, path: ${self.path.toString}")

  val outgoingMessageActor = context.actorOf(OutgoingMessageActor.props(), "out")
  val incomingMessageActor = context.actorOf(IncomingMessageActor.props(phoneNumber, displayName, outgoingMessageActor), "in")

  override def receive = {

    case socket: ActorRef =>
      outgoingMessageActor ! socket
      syncStateFromDb()
      sender() ! incomingMessageActor

    case message@CheckState(targetPhoneNumber, inbound) =>
      Logger(this.getClass).trace(s"check-state message: $message")

      val query: BSONDocument = inbound match {
        case true =>
          BSONDocument("originator" -> targetPhoneNumber, "recipient" -> phoneNumber)
        case _ =>
          BSONDocument("originator" -> phoneNumber, "recipient" -> targetPhoneNumber)
      }

      querySendAndDelete(query, broadcastClosedAndRejected = true).foreach { empty =>
        if (empty) {
          Logger(this.getClass).trace(s"doing an empty for $phoneNumber, target=$targetPhoneNumber, inbound=$inbound")
          inbound match {
            case true =>
              outgoingMessageActor ! StickleState(None, targetPhoneNumber, "", phoneNumber, new Date(), closed)
            case _ =>
              outgoingMessageActor ! StickleState(None, phoneNumber, "", targetPhoneNumber, new Date(), closed)
          }
        }
      }
  }

  def syncStateFromDb(): Unit = {
    Logger(this.getClass).trace("sync state from db")
    val query: BSONDocument = BSONDocument("$or" -> BSONArray(BSONDocument("originator" -> phoneNumber), BSONDocument("recipient" -> phoneNumber)))
    querySendAndDelete(query, broadcastClosedAndRejected = false)
  }

  def querySendAndDelete(query: BSONDocument, broadcastClosedAndRejected: Boolean): Future[Boolean] = {
    fstickleCollection.map { coll =>
      var empty = true
      var eventMap = Map[(String, String), Option[StickleState]]()
      coll.find(query)
        .sort(BSONDocument("createdDate" -> -1)).cursor[StickleState]().enumerate().run(Iteratee.foreach { row =>
        eventMap = rowMatch(broadcastClosedAndRejected, row, eventMap)
        empty = false
      })
      empty
    }
  }

  private def rowMatch(broadcastClosedAndRejected: Boolean, row: StickleState, eventMap: Map[(String, String), Option[StickleState]]): Map[(String, String), Option[StickleState]] = {
    Logger(this.getClass).trace(row.toString)
    if (List(completed, closed, rejected).contains(row.state)) {
      dealWithClosedOrRejected(eventMap, row.originator, row.recipient, row, broadcastClosedAndRejected = broadcastClosedAndRejected)
    } else if (List(unaccepted, accepted).contains(row.state)) {
      eventMap.getOrElse((row.originator, row.recipient), null) match {
        case null => outgoingMessageActor ! row
          eventMap + ((row.originator -> row.recipient) -> None)
        case Some(previous) => outgoingMessageActor ! StickleState(None, row.originator, row.originatorDisplayName, row.recipient, previous.createdDate, previous.state)
          eventMap + ((row.originator -> row.recipient) -> None)
        case _ => deleteFromDB(row)
          eventMap
      }
    } else {
      // row.state == open
      eventMap.getOrElse((row.originator, row.recipient), null) match {
        case null =>
          eventMap + ((row.originator -> row.recipient) -> Some(row))
        case _ => deleteFromDB(row)
          eventMap
      }
    }
  }


  def dealWithClosedOrRejected(eventMap: Map[(String, String), Option[StickleState]], originator: String, recipient: String, result: StickleState, broadcastClosedAndRejected: Boolean): Map[(String, String), Option[StickleState]] = {
    eventMap.getOrElse((originator, recipient), null) match {
      case null =>
        if (broadcastClosedAndRejected) {
          outgoingMessageActor ! result
        }
        eventMap + ((originator -> recipient) -> None)
      case _ => deleteFromDB(result)
        eventMap
    }
  }

  def deleteFromDB(result: StickleState): Unit = {
    Logger(this.getClass).trace(s"removing $result")
    fstickleCollection foreach (coll => coll.remove(BSONDocument("_id" -> BSONObjectID(result.id.get))))
  }
}
