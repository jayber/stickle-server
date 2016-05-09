package controllers

import akka.actor.{Actor, ActorRef, Props}
import controllers.UserActor.{ StickleOffTarget, StickleOnTarget}
import play.api.Logger
import play.api.libs.json.{JsValue, Json}
import reactivemongo.bson.BSONDocument

object UserActor {
  def props(mySocket: ActorRef, phoneNumber: String) = Props(new UserActor(mySocket, phoneNumber))

  trait StickleUserMessage
  case class StickleOnTarget(sourcePhoneNumber: String) extends StickleUserMessage
  case class StickleOffTarget(sourcePhoneNumber: String) extends StickleUserMessage

}

class UserActor(var mySocket: ActorRef, phoneNumber: String) extends Actor with StickleDb {

  import scala.concurrent.ExecutionContext.Implicits.global

  Logger.debug(s"UserActor - phoneNumber: $phoneNumber, path: ${self.path.toString}, out: ${mySocket.path.toString}")

  override def receive = {

    case socket: ActorRef => mySocket = socket

    case StickleOnTarget(sourcePhoneNumber) =>
      Logger.debug(s"stickle open received by target from: $sourcePhoneNumber")
      mySocket ! Json.obj("event" -> "stickled", "data" -> Json.obj("from" -> sourcePhoneNumber, "status" -> "open"))
    case StickleOffTarget(sourcePhoneNumber) =>
      Logger.debug(s"stickle closed received by target from: $sourcePhoneNumber")
      mySocket ! Json.obj("event" -> "stickled", "data" -> Json.obj("from" -> sourcePhoneNumber, "status" -> "closed"))

    case ("checkContactStatus", msg: JsValue) =>
      Logger.trace("checkContactStatus ")
      val phoneNumber = (msg \ "data" \ "phoneNum").as[String]
      val query = BSONDocument("phoneNumber" -> phoneNumber)
      fuserCollection.flatMap(_.find(query).one[BSONDocument]).map {
        case Some(_) =>
          Logger.trace(s"contactStatus $phoneNumber: registered ")
          mySocket ! Json.obj("event" -> "contactStatus", "data" -> Json.obj("phoneNum" -> phoneNumber, "status" -> "registered"))
        case None =>
          Logger.trace(s"contactStatus $phoneNumber: unregistered ")
          mySocket ! Json.obj("event" -> "contactStatus", "data" -> Json.obj("phoneNum" -> phoneNumber, "status" -> "unregistered"))
      }

    case ("stickle", msg: JsValue) =>
      Logger.debug("stickle: " + Json.stringify(msg))
      val targetPhoneNumber = (msg \ "data" \ "to").as[String]
      (msg \ "data" \ "status").as[String] match {
        case "open" =>
          Logger.debug(s"stickle open received by source to: $targetPhoneNumber")
          context.actorSelection(s"../$targetPhoneNumber") ! StickleOnTarget(phoneNumber)
          updateDb(phoneNumber, targetPhoneNumber, "open")
        case "closed" =>
          Logger.debug(s"stickle closed received by source to: $targetPhoneNumber")
          context.actorSelection(s"../$targetPhoneNumber") ! StickleOffTarget(phoneNumber)
          updateDb(phoneNumber, targetPhoneNumber, "closed")
      }

    case msg: JsValue =>
      Logger.debug("Unhandled socket event: " + Json.stringify(msg))
  }

  def updateDb(from: String, to: String, status: String) = {
    fstickleCollection foreach {
      _.insert(
        BSONDocument("phoneNumber" -> from, "stickleNum" -> to, "status" -> status))}
  }
}
