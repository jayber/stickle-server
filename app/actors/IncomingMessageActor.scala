package actors

import actors.OutgoingMessageActor._
import actors.UserActor._
import akka.actor.{Actor, ActorRef, Props}
import play.api.Logger
import play.api.libs.json.{JsValue, Json}
import reactivemongo.bson.{BSONDateTime, BSONDocument}
import services.StickleDb

object IncomingMessageActor {
  def props(phoneNumber: String, displayName: String, outgoingMessageActor: ActorRef) = Props(new IncomingMessageActor(phoneNumber, displayName, outgoingMessageActor))
}

class IncomingMessageActor(phoneNumber: String, displayName: String, outgoingMessageActor: ActorRef) extends Actor with StickleDb {

  import scala.concurrent.ExecutionContext.Implicits.global

  Logger.trace(s"IncomingMessageActor - phoneNumber: $phoneNumber, path: ${self.path.toString}")

  override def receive = {

    case ("stickle", msg: JsValue) =>
      val targetPhoneNumber = (msg \ "data" \ "to").as[String]
      (msg \ "data" \ "status").as[String] match {
        case `open` =>
          postStickleEventToPeer(targetPhoneNumber, open, Some(displayName), StickleOnEvent(phoneNumber, displayName))
        case `closed` =>
          postStickleEventToPeer(targetPhoneNumber, closed, None, StickleStatusChangedEvent(phoneNumber, closed))
      }

    case ("stickle-response", msg: JsValue) =>
      postResponseEventToPeer((msg \ "data" \ "origin").as[String], phoneNumber, (msg \ "data" \ "status").as[String])

    case ("checkContactStatus", msg: JsValue) =>
      Logger.trace("checkContactStatus")
      val phoneNumber = (msg \ "data" \ "phoneNum").as[String]
      fuserCollection.flatMap(_.find(BSONDocument("phoneNumber" -> phoneNumber)).one[BSONDocument]).map {
        case Some(_) =>
          outgoingMessageActor ! ContactStatus(phoneNumber, "registered")
        case None =>
          outgoingMessageActor ! ContactStatus(phoneNumber, "unregistered")
      }

    case (_, msg: JsValue) =>
      Logger.debug("Unhandled socket event: " + Json.stringify(msg))
  }


  def postResponseEventToPeer(originatorPhoneNumber: String, recipientPhoneNumber: String, status: String): Unit = {
    Logger.debug(s"stickle $status received by source, origin: $originatorPhoneNumber, recipient: $recipientPhoneNumber")
    persistStickleEvent(originatorPhoneNumber, None, recipientPhoneNumber, status)
    val message = StickleStatusChangedEvent(phoneNumber, status)
    sendMessage(originatorPhoneNumber, message)
  }

  def postStickleEventToPeer(targetPhoneNumber: String, status: String, sourceDisplayName: Option[String], message: StickleEvent): Unit = {
    Logger.debug(s"stickle open received by source to: $targetPhoneNumber")
    persistStickleEvent(phoneNumber, sourceDisplayName, targetPhoneNumber, status)
    sendMessage(targetPhoneNumber, message)
  }

  def sendMessage(targetPhoneNumber: String, message: StickleEvent): Unit = {
    context.actorSelection(s"../../$targetPhoneNumber/out") ! message
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