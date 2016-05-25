package actors

import java.util.Date

import actors.OutgoingMessageActor._
import actors.UserActor._
import akka.actor.{Actor, ActorRef, Props}
import play.api.Logger
import play.api.libs.json.{JsObject, Json}
import services.StickleDb

object OutgoingMessageActor {
  def props() = Props(new OutgoingMessageActor)

  trait StickleEvent
  case class StickleOnEvent(sourcePhoneNumber: String, sourceDisplayName: String) extends StickleEvent
  case class StickleClosedEvent(sourcePhoneNumber: String) extends StickleEvent
  case class StickleStatusChangedEvent(sourcePhoneNumber: String, state: String) extends StickleEvent

  case class ContactStatus(phoneNumber: String, status: String)

  case class StickleState(id: Option[String], originator: String, originatorDisplayName: String, recipient: String, createdDate: Date, state: String)
}

class OutgoingMessageActor extends Actor with StickleDb {

  Logger.trace(s"OutgoingMessageActor - path: ${self.path.toString}")

  var mySocket: Option[ActorRef] = None

  override def receive = {

    case socket: ActorRef => mySocket = Some(socket)

    case replay: StickleState => sendStateToSocket(replay)

    case ContactStatus(phoneNumber, status) =>
      sendContactStatusToSocket(phoneNumber, status)

    case StickleOnEvent(sourcePhoneNumber, sourceDisplayName) =>
      Logger.debug(s"stickle $open received by target from: $sourcePhoneNumber - $sourceDisplayName")
      mySocket foreach {_ ! Json.obj("event" -> "stickled", "data" -> Json.obj("from" -> sourcePhoneNumber, "displayName" -> sourceDisplayName, "status" -> open))}
    case StickleClosedEvent(sourcePhoneNumber) =>
      Logger.debug(s"stickle closed received by target from: $sourcePhoneNumber")
      mySocket foreach {_ ! Json.obj("event" -> "stickled", "data" -> Json.obj("from" -> sourcePhoneNumber, "status" -> closed))}
    case StickleStatusChangedEvent(sourcePhoneNumber, status) =>
      Logger.debug(s"stickle $status received by target from: $sourcePhoneNumber")
      mySocket foreach {_ ! Json.obj("event" -> "stickle-responded", "data" -> Json.obj("from" -> sourcePhoneNumber, "status" -> status))}
  }

  def sendContactStatusToSocket(phoneNumber: String, status: String): Unit = {
    Logger.trace(s"contactStatus $phoneNumber: $status")
    mySocket foreach {_ ! Json.obj("event" -> "contactStatus", "data" -> Json.obj("phoneNum" -> phoneNumber, "status" -> status))}
  }

  def sendStateToSocket(state: StickleState): Unit = {
    val message: JsObject = Json.obj(
      "event" -> "state",
      "data" -> Json.obj(
        "originator" -> state.originator,
        "originatorDisplayName" -> state.originatorDisplayName,
        "recipient" -> state.recipient,
        "state" -> state.state,
        "createdDate" -> state.createdDate
      ))
    Logger.debug(s"state message: ${Json.stringify(message)}")
    mySocket foreach {_ ! message}
  }
}
