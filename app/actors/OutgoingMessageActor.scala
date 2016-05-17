package actors

import actors.IncomingMessageActor._
import actors.OutgoingMessageActor._
import akka.actor.{Actor, ActorRef, Props}
import play.api.Logger
import play.api.libs.json.{JsObject, Json}
import reactivemongo.bson.{BSONDateTime, BSONDocument}

object OutgoingMessageActor {
  def props() = Props(new OutgoingMessageActor)

  trait StickleMessage
  case class StickleOn(sourcePhoneNumber: String, sourceDisplayName: String) extends StickleMessage
  case class StickleStatusChanged(sourcePhoneNumber: String, state: String) extends StickleMessage

  case class ContactStatus(phoneNumber: String, status: String)
}

class OutgoingMessageActor extends Actor with StickleDb {

  var mySocket: Option[ActorRef] = None

  override def receive = {

    case socket: ActorRef => mySocket = Some(socket)

    case replay: BSONDocument => sendStateToSocket(replay)

    case ContactStatus(phoneNumber, status) =>
      sendContactStatusToSocket(phoneNumber, status)

    case StickleOn(sourcePhoneNumber, sourceDisplayName) =>
      Logger.debug(s"stickle $open received by target from: $sourcePhoneNumber - $sourceDisplayName")
      mySocket foreach {_ ! Json.obj("event" -> "stickled", "data" -> Json.obj("from" -> sourcePhoneNumber, "displayName" -> sourceDisplayName, "status" -> open))}
    case StickleStatusChanged(sourcePhoneNumber, status) =>
      Logger.debug(s"stickle $status received by target from: $sourcePhoneNumber")
      mySocket foreach {_ ! Json.obj("event" -> "stickled", "data" -> Json.obj("from" -> sourcePhoneNumber, "status" -> status))}
  }

  def sendContactStatusToSocket(phoneNumber: String, status: String): Unit = {
    Logger.trace(s"contactStatus $phoneNumber: $status")
    mySocket foreach {_ ! Json.obj("event" -> "contactStatus", "data" -> Json.obj("phoneNum" -> phoneNumber, "status" -> status))}
  }

  def sendStateToSocket(bSONDocument: BSONDocument) = {
    val message: JsObject = Json.obj(
      "event" -> "state", "data" -> Json.obj(
        "from" -> bSONDocument.getAs[String]("from"),
        "to" -> bSONDocument.getAs[String]("to"),
        "status" -> bSONDocument.getAs[String]("status"),
        "createdDate" -> bSONDocument.getAs[BSONDateTime]("createdDate").get.value
      )
    )
    Logger.debug(s"state message: $message")
    mySocket foreach {
      _ ! message
    }
  }
}
