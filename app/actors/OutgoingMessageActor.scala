package actors

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
  case class StickleStatusChangedEvent(sourcePhoneNumber: String, state: String) extends StickleEvent {
    val translation = Map("accepted" -> "ready to call", "un-accepted" -> "NOT ready to call", "rejected" -> "declined call")
    def translateState: String = {
      translation(state)
    }
  }

  case class ContactStatus(phoneNumber: String, status: String)

}

class OutgoingMessageActor extends Actor with StickleDb {

  Logger(this.getClass).trace(s"OutgoingMessageActor - path: ${self.path.toString}")

  var mySocket: Option[ActorRef] = None

  override def receive = {

    case socket: ActorRef => mySocket = Some(socket)

    case replay: StickleState => sendStateToSocket(replay)

    case ContactStatus(phoneNumber, status) =>
      sendContactStatusToSocket(phoneNumber, status)

    case (recipient: String, Delivery(_, status, time)) =>
      val message = Json.obj("event" -> "delivery", "data" -> Json.obj("recipient" -> recipient, "status" -> status, "time" -> time))
      Logger(this.getClass).debug(s"delivery message: ${Json.stringify(message)}")
      mySocket foreach {_ ! message}

    case StickleOnEvent(sourcePhoneNumber, sourceDisplayName) =>
      Logger(this.getClass).debug(s"stickle $open received by target from: $sourcePhoneNumber - $sourceDisplayName")
      mySocket foreach {_ ! Json.obj("event" -> "stickled", "data" -> Json.obj("from" -> sourcePhoneNumber, "displayName" -> sourceDisplayName, "status" -> open))}
    case StickleClosedEvent(sourcePhoneNumber) =>
      Logger(this.getClass).debug(s"stickle closed received by target from: $sourcePhoneNumber")
      mySocket foreach {_ ! Json.obj("event" -> "stickled", "data" -> Json.obj("from" -> sourcePhoneNumber, "status" -> closed))}
    case StickleStatusChangedEvent(sourcePhoneNumber, status) =>
      Logger(this.getClass).debug(s"stickle $status received by target from: $sourcePhoneNumber")
      mySocket foreach {_ ! Json.obj("event" -> "stickle-responded", "data" -> Json.obj("from" -> sourcePhoneNumber, "status" -> status))}
  }

  def sendContactStatusToSocket(phoneNumber: String, status: String): Unit = {
    Logger(this.getClass).trace(s"contactStatus $phoneNumber: $status")
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
        "createdDate" -> state.createdDate,
        "deliveryState" -> state.deliveryState,
        "deliveryTime" -> state.deliveryTime
      ))
    Logger(this.getClass).debug(s"state message: ${Json.stringify(message)}")
    mySocket foreach {_ ! message}
  }
}
