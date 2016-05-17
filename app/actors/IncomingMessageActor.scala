package actors

import actors.IncomingMessageActor._
import actors.OutgoingMessageActor._
import akka.actor.{Actor, ActorRef, Props}
import play.api.Logger
import play.api.libs.json.{JsValue, Json}
import reactivemongo.bson.{BSONArray, BSONDateTime, BSONDocument}

object IncomingMessageActor {
  def props(phoneNumber: String, displayName: String) = Props(new IncomingMessageActor(phoneNumber, displayName))

  val closed: String = "closed"
  val accepted: String = "accepted"
  val unaccepted: String = "un-accepted"
  val rejected: String = "rejected"
  val open: String = "open"
}

class IncomingMessageActor(phoneNumber: String, displayName: String) extends Actor with StickleDb {

  import scala.concurrent.ExecutionContext.Implicits.global

  Logger.trace(s"IncomingMessageActor - phoneNumber: $phoneNumber, path: ${self.path.toString}")

  var outgoingMessageActor = context.actorOf(OutgoingMessageActor.props(), "out")

  override def receive = {

    case socket: ActorRef =>
      outgoingMessageActor ! socket
      updateFromDb()

    case ("stickle", msg: JsValue) =>
      val targetPhoneNumber = (msg \ "data" \ "to").as[String]
      (msg \ "data" \ "status").as[String] match {
        case `open` =>
          postEventToPeer(targetPhoneNumber, open, StickleOn(phoneNumber, displayName))
        case `closed` =>
          postEventToPeer(targetPhoneNumber, closed)
      }

    case ("stickle-response", msg: JsValue) =>
      postEventToPeer((msg \ "data" \ "origin").as[String], (msg \ "data" \ "status").as[String])

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


  def updateFromDb(): Unit = {
    fstickleCollection.flatMap({
      _.find(BSONDocument("$or" -> BSONArray(BSONDocument("to" -> phoneNumber), BSONDocument("from" -> phoneNumber))))
        .sort(BSONDocument("createdDate" -> -1)).cursor[BSONDocument]().collect[List]()
    }).foreach(_ foreach (outgoingMessageActor ! _))
  }

  def postEventToPeer(targetPhoneNumber: String, status: String): Unit = {
    val message = StickleStatusChanged(phoneNumber, status)
    postEventToPeer(targetPhoneNumber, status, message)
  }

  def postEventToPeer(targetPhoneNumber: String, status: String, message: StickleMessage): Unit = {
    Logger.debug(s"stickle $status received by source to: $targetPhoneNumber")
    persistStickleEvent(phoneNumber, targetPhoneNumber, status)
    context.actorSelection(s"../$targetPhoneNumber/out") ! message
  }

  def persistStickleEvent(from: String, to: String, status: String) = {
    fstickleCollection foreach {
      _.insert(
        BSONDocument("from" -> from, "to" -> to, "status" -> status, "createdDate" -> BSONDateTime(System.currentTimeMillis)))
    }
  }
}
