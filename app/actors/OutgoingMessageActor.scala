package actors

import actors.IncomingMessageActor._
import actors.OutgoingMessageActor._
import akka.actor.{Actor, ActorRef, Props}
import play.api.Logger
import play.api.libs.json.Json

object OutgoingMessageActor {
  def props(mySocket: ActorRef) = Props(new OutgoingMessageActor(mySocket))

  trait StickleMessage
  case class StickleOnIncoming(sourcePhoneNumber: String, sourceDisplayName: String) extends StickleMessage
  case class StickleOffIncoming(sourcePhoneNumber: String) extends StickleMessage
  case class StickleAcceptedIncoming(sourcePhoneNumber: String) extends StickleMessage
  case class StickleUnAcceptedIncoming(sourcePhoneNumber: String) extends StickleMessage
  case class StickleRejectedIncoming(sourcePhoneNumber: String) extends StickleMessage

  case class ContactStatus(phoneNumber: String, status: String)
}

class OutgoingMessageActor(var mySocket: ActorRef) extends Actor {

  override def receive = {

    case socket: ActorRef => mySocket = socket

    case ContactStatus(phoneNumber, status) =>
      sendContactStatusToSocket(phoneNumber, status)

    case StickleOnIncoming(sourcePhoneNumber, sourceDisplayName) =>
      Logger.debug(s"stickle $open received by target from: $sourcePhoneNumber - $sourceDisplayName")
      mySocket ! Json.obj("event" -> "stickled", "data" -> Json.obj("from" -> sourcePhoneNumber, "displayName" -> sourceDisplayName, "status" -> open))
    case StickleOffIncoming(sourcePhoneNumber) =>
      sendStickleToSocket(sourcePhoneNumber, closed)
    case StickleAcceptedIncoming(sourcePhoneNumber) =>
      sendStickleToSocket(sourcePhoneNumber, accepted)
    case StickleUnAcceptedIncoming(sourcePhoneNumber) =>
      sendStickleToSocket(sourcePhoneNumber, unaccepted)
    case StickleRejectedIncoming(sourcePhoneNumber) =>
      sendStickleToSocket(sourcePhoneNumber, rejected)
  }

  def sendContactStatusToSocket(phoneNumber: String, status: String): Unit = {
    Logger.trace(s"contactStatus $phoneNumber: $status")
    mySocket ! Json.obj("event" -> "contactStatus", "data" -> Json.obj("phoneNum" -> phoneNumber, "status" -> status))
  }

  def sendStickleToSocket(sourcePhoneNumber: String, status: String): Unit = {
    Logger.debug(s"stickle $status received by target from: $sourcePhoneNumber")
    mySocket ! Json.obj("event" -> "stickled", "data" -> Json.obj("from" -> sourcePhoneNumber, "status" -> status))
  }
}
