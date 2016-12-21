package actors

import java.util.Date

import actors.OutgoingMessageActor._
import actors.UserActor._
import akka.actor.{Actor, ActorRef, Props}
import play.api.Logger
import play.api.libs.json.{JsValue, Json}
import play.api.libs.ws.WSClient
import reactivemongo.bson.{BSONArray, BSONDateTime, BSONDocument, BSONDocumentWriter}
import services.{PushNotifications, StickleConsts, StickleDb}

object IncomingMessageActor {
  def props(phoneNumber: String, displayName: String, outgoingMessageActor: ActorRef)(implicit ws: WSClient): Props = Props(new IncomingMessageActor(phoneNumber, displayName, outgoingMessageActor))
}

class IncomingMessageActor(phoneNumber: String, displayName: String, outgoingMessageActor: ActorRef)(implicit ws: WSClient) extends Actor with StickleDb with StickleConsts {

  import scala.concurrent.ExecutionContext.Implicits.global

  Logger(this.getClass).trace(s"IncomingMessageActor - phoneNumber: $phoneNumber, path: ${self.path.toString}")

  override def receive = {

    case ("stickle", msg: JsValue) =>
      val targetPhoneNumber = (msg \ "data" \ "to").as[String]
      (msg \ "data" \ "status").as[String] match {
        case `open` =>
          postStickleEventToPeer(targetPhoneNumber, open, Some(displayName), StickleOnEvent(phoneNumber, displayName))
        case `closed` =>
          postStickleEventToPeer(targetPhoneNumber, closed, Some(displayName), StickleClosedEvent(phoneNumber))
        case `completed` =>
          postStickleEventToPeer(targetPhoneNumber, completed, Some(displayName), StickleClosedEvent(phoneNumber))
      }

    case ("stickle-response", msg: JsValue) =>
      postResponseEventToPeer((msg \ "data" \ "origin").as[String], phoneNumber, Some(displayName), (msg \ "data" \ "status").as[String])

    case ("checkContactStatus", msg: JsValue) =>
      Logger(this.getClass).trace("checkContactStatus")
      val targetPhoneNumber = (msg \ "data" \ "phoneNum").as[String]
      fuserCollection.flatMap(_.find(BSONDocument("phoneNumber" -> targetPhoneNumber, "authId" -> BSONDocument("$exists" -> true))).one[BSONDocument]).map {
        case Some(_) =>
          outgoingMessageActor ! ContactStatus(targetPhoneNumber, "registered")
        case None =>
          outgoingMessageActor ! ContactStatus(targetPhoneNumber, "unregistered")
      }

    case ("sync", msg: JsValue) =>
      context.parent ! "sync"

    case ("check-state", msg: JsValue) =>
      context.parent ! CheckState((msg \ "data" \ "phoneNum").as[String], (msg \ "data" \ "inbound").as[Boolean])

    case ("delivery", msg: JsValue) =>
      Logger(this.getClass).debug("delivery: " + Json.stringify(msg))
      context.parent ! Delivery((msg \ "data" \ "origin").as[String], (msg \ "data" \ "status").as[String], new Date())

    case (_, msg: JsValue) =>
      Logger(this.getClass).debug("Unhandled socket event: " + Json.stringify(msg))
  }

  def postResponseEventToPeer(originatorPhoneNumber: String, recipientPhoneNumber: String, sourceDisplayName: Option[String], status: String): Unit = {
    Logger(this.getClass).debug(s"stickle $status received by source, origin: $originatorPhoneNumber, recipient: $recipientPhoneNumber")
    val stickleState = persistStickleEvent(originatorPhoneNumber, None, recipientPhoneNumber, status)
    val message = StickleStatusChangedEvent(phoneNumber, status)
    sendMessage(originatorPhoneNumber, sourceDisplayName: Option[String], message, stickleState)
  }

  def postStickleEventToPeer(targetPhoneNumber: String, status: String, sourceDisplayName: Option[String], message: StickleEvent): Unit = {
    Logger(this.getClass).debug(s"stickle $status received by source ($sourceDisplayName) to: $targetPhoneNumber")
    val stickleState = persistStickleEvent(phoneNumber, sourceDisplayName, targetPhoneNumber, status)
    sendMessage(targetPhoneNumber, sourceDisplayName, message, stickleState)
  }

  def sendMessage(targetPhoneNumber: String, sourceDisplayName: Option[String], message: StickleEvent, stickleState: StickleState): Unit = {
    outgoingMessageActor ! stickleState
    context.actorSelection(s"../../$targetPhoneNumber/out") ! message
    fuserCollection foreach {
      _.find(BSONDocument("phoneNumber" -> targetPhoneNumber)).one[BSONDocument] foreach {
        _ foreach { doc =>
          PushNotifications.sendNotification(doc.getAs[String](pushRegistrationId).get, sourceDisplayName, message)
        }
      }
    }
  }

  def persistStickleEvent(originator: String, originatorDisplayName: Option[String], recipient: String, status: String): StickleState = {

    implicit val stickleStateDocumentWriter: BSONDocumentWriter[StickleState] = new BSONDocumentWriter[StickleState] {
      override def write(state: StickleState): BSONDocument = BSONDocument(
        "_id" -> state.id,
        "originator" -> state.originator,
        "originatorDisplayName" -> state.originatorDisplayName,
        "recipient" -> state.recipient,
        "createdDate" -> BSONDateTime(state.createdDate.getTime),
        "status" -> state.state,
        "delivery" -> BSONArray(BSONDocument("status" -> state.deliveryState, "time" -> state.deliveryTime.map { date => BSONDateTime(date.getTime) }))
      )
    }

    val stickleState = StickleState(None, originator, originatorDisplayName.getOrElse(""), recipient, new Date(), status, Some("sent"), Some(new Date()))

    fstickleCollection foreach {
      _.insert(stickleState)
    }
    stickleState
  }
}
