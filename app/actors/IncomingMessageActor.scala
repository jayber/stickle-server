package actors

import actors.StickleDb
import akka.actor.{Actor, ActorRef, Props}
import OutgoingMessageActor._
import IncomingMessageActor._
import play.api.Logger
import play.api.libs.json.{JsValue, Json}
import reactivemongo.bson.BSONDocument

object IncomingMessageActor {
  def props(mySocket: ActorRef, phoneNumber: String, displayName: String) = Props(new IncomingMessageActor(mySocket, phoneNumber, displayName))

  val closed: String = "closed"
  val accepted: String = "accepted"
  val unaccepted: String = "un-accepted"
  val rejected: String = "rejected"
  val open: String = "open"
}

class IncomingMessageActor(mySocket: ActorRef, phoneNumber: String, displayName: String) extends Actor with StickleDb {

  import scala.concurrent.ExecutionContext.Implicits.global

  Logger.debug(s"UserActor - phoneNumber: $phoneNumber, path: ${self.path.toString}, out: ${mySocket.path.toString}")

  val outgoingMessageActor = context.actorOf(OutgoingMessageActor.props(mySocket), "out")

  override def receive = {

    case socket: ActorRef =>
      outgoingMessageActor ! socket

    case ("stickle", msg: JsValue) =>
      Logger.trace("stickle: " + Json.stringify(msg))
      val targetPhoneNumber = (msg \ "data" \ "to").as[String]
      (msg \ "data" \ "status").as[String] match {
        case `open` =>
          postEventToPeer(targetPhoneNumber, open, StickleOnIncoming(phoneNumber, displayName))
        case `closed` =>
          postEventToPeer(targetPhoneNumber, closed, StickleOffIncoming(phoneNumber))
      }

    case ("stickle-response", msg: JsValue) =>
      Logger.trace("stickle-response: " + Json.stringify(msg))
      val originPhoneNumber = (msg \ "data" \ "origin").as[String]
      (msg \ "data" \ "status").as[String] match {
        case `accepted` =>
          postEventToPeer(originPhoneNumber, accepted, StickleAcceptedIncoming(phoneNumber))
        case `unaccepted` =>
          postEventToPeer(originPhoneNumber, unaccepted, StickleUnAcceptedIncoming(phoneNumber))
        case `rejected` =>
          postEventToPeer(originPhoneNumber, rejected, StickleRejectedIncoming(phoneNumber))
      }

    case ("checkContactStatus", msg: JsValue) =>
      Logger.trace("checkContactStatus")
      val phoneNumber = (msg \ "data" \ "phoneNum").as[String]
      val query = BSONDocument("phoneNumber" -> phoneNumber)
      fuserCollection.flatMap(_.find(query).one[BSONDocument]).map {
        case Some(_) =>
          outgoingMessageActor ! ContactStatus(phoneNumber, "registered")
        case None =>
          outgoingMessageActor ! ContactStatus(phoneNumber, "unregistered")
      }

    case message: StickleMessage =>
      outgoingMessageActor ! message

    case (_, msg: JsValue) =>
      Logger.debug("Unhandled socket event: " + Json.stringify(msg))
  }

  def postEventToPeer(targetPhoneNumber: String, status: String, message: StickleMessage): Unit = {
    Logger.debug(s"stickle $status received by source to: $targetPhoneNumber")
    persistStickleEvent(phoneNumber, targetPhoneNumber, status)
    context.actorSelection(s"../$targetPhoneNumber") ! message
  }

  def persistStickleEvent(from: String, to: String, status: String) = {
    fstickleCollection foreach {
      _.insert(
        BSONDocument("phoneNumber" -> from, "stickleNum" -> to, "status" -> status))
    }
  }
}
