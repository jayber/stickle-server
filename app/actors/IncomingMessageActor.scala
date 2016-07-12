package actors

import actors.OutgoingMessageActor._
import actors.UserActor._
import akka.actor.{Actor, ActorRef, Props}
import play.api.Logger
import play.api.libs.json.{JsValue, Json}
import play.api.libs.ws.WSClient
import reactivemongo.bson.{BSONDateTime, BSONDocument}
import services.{PushNotifications, StickleDb}

object IncomingMessageActor {
  def props(phoneNumber: String, displayName: String, outgoingMessageActor: ActorRef)(implicit ws: WSClient): Props = Props(new IncomingMessageActor(phoneNumber, displayName, outgoingMessageActor))
}

class IncomingMessageActor(phoneNumber: String, displayName: String, outgoingMessageActor: ActorRef)(implicit ws: WSClient) extends Actor with StickleDb {

  import scala.concurrent.ExecutionContext.Implicits.global

  Logger.trace(s"IncomingMessageActor - phoneNumber: $phoneNumber, path: ${self.path.toString}")

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
      Logger.trace("checkContactStatus")
      val targetPhoneNumber = (msg \ "data" \ "phoneNum").as[String]
      fuserCollection.flatMap(_.find(BSONDocument("phoneNumber" -> targetPhoneNumber)).one[BSONDocument]).map {
        case Some(_) =>
          outgoingMessageActor ! ContactStatus(targetPhoneNumber, "registered")
        case None =>
          outgoingMessageActor ! ContactStatus(targetPhoneNumber, "unregistered")
      }

    case ("check-state", msg: JsValue) =>
      context.parent ! CheckState((msg \ "data" \ "phoneNum").as[String], (msg \ "data" \ "inbound").as[Boolean])

    case (_, msg: JsValue) =>
      Logger.debug("Unhandled socket event: " + Json.stringify(msg))
  }

  def postResponseEventToPeer(originatorPhoneNumber: String, recipientPhoneNumber: String, sourceDisplayName: Option[String], status: String): Unit = {
    Logger.debug(s"stickle $status received by source, origin: $originatorPhoneNumber, recipient: $recipientPhoneNumber")
    persistStickleEvent(originatorPhoneNumber, None, recipientPhoneNumber, status)
    val message = StickleStatusChangedEvent(phoneNumber, status)
    sendMessage(originatorPhoneNumber, sourceDisplayName: Option[String], message)
  }

  def postStickleEventToPeer(targetPhoneNumber: String, status: String, sourceDisplayName: Option[String], message: StickleEvent): Unit = {
    Logger.debug(s"stickle $status received by source to: $targetPhoneNumber")
    persistStickleEvent(phoneNumber, sourceDisplayName, targetPhoneNumber, status)
    sendMessage(targetPhoneNumber, sourceDisplayName, message)
  }

  def sendMessage(targetPhoneNumber: String, sourceDisplayName: Option[String], message: StickleEvent): Unit = {
    context.actorSelection(s"../../$targetPhoneNumber/out") ! message
    fuserCollection foreach {
      _.find(BSONDocument("phoneNumber" -> targetPhoneNumber)).one[BSONDocument] foreach {
        _ foreach { doc =>
          PushNotifications.sendNotification(doc.getAs[String]("pushRegistrationId").get, sourceDisplayName, message)
        }
      }
    }
  }

  def persistStickleEvent(originator: String, originatorDisplayName: Option[String], recipient: String, status: String) = {
    fstickleCollection foreach {
      _.insert(
        BSONDocument("originator" -> originator,
          "originatorDisplayName" -> originatorDisplayName.getOrElse(""),
          "recipient" -> recipient,
          "status" -> status,
          "createdDate" -> BSONDateTime(System.currentTimeMillis)
        )
      )
    }
  }
}
