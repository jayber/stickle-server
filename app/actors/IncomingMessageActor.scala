package actors

import java.util.Date

import actors.IncomingMessageActor._
import actors.OutgoingMessageActor._
import akka.actor.{Actor, ActorRef, Props}
import play.api.Logger
import play.api.libs.json.{JsValue, Json}
import reactivemongo.bson.{BSONArray, BSONDateTime, BSONDocument, BSONDocumentReader}

import scala.collection.mutable

object IncomingMessageActor {
  def props(phoneNumber: String, displayName: String) = Props(new IncomingMessageActor(phoneNumber, displayName))

  val closed: String = "closed"
  val accepted: String = "accepted"
  val unaccepted: String = "un-accepted"
  val rejected: String = "rejected"
  val open: String = "open"


  implicit val stickleStateDocumentReader: BSONDocumentReader[StickleState] = new BSONDocumentReader[StickleState] {
    override def read(bson: BSONDocument): StickleState = {
      StickleState(
        bson.getAs[String]("originator").get,
        bson.getAs[String]("originatorDisplayName").getOrElse(""),
        bson.getAs[String]("recipient").get,
        new Date(bson.getAs[BSONDateTime]("createdDate").get.value),
        bson.getAs[String]("status").get
      )
    }
  }
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

  def updateFromDb(): Unit = {
    fstickleCollection.flatMap({
      _.find(BSONDocument("$or" -> BSONArray(BSONDocument("originator" -> phoneNumber), BSONDocument("recipient" -> phoneNumber))))
        .sort(BSONDocument("createdDate" -> -1)).cursor[StickleState]().collect[List]()
    }).foreach { results =>
      val eventMap = mutable.Map[(String, String), Option[StickleState]]()
      results foreach {
        case StickleState(originator, _, recipient, _, `closed`) =>
          dealWithClosedOrRejected(eventMap, originator, recipient)
        case StickleState(originator, _, recipient, _, `rejected`) =>
          dealWithClosedOrRejected(eventMap, originator, recipient)
        case result@StickleState(originator, originatorDisplayName, recipient, _, `open`) =>
          eventMap.getOrElse((originator, recipient), null) match {
            case null => eventMap((originator, recipient)) = None
              outgoingMessageActor ! result
            case Some(previous) => eventMap((originator, recipient)) = None
              outgoingMessageActor ! StickleState(originator, originatorDisplayName, recipient, previous.createdDate, previous.state)
            case _ =>
          }
        case result@StickleState(originator, originatorDisplayName, recipient, _, _) =>
          eventMap.getOrElse((originator, recipient), null) match {
            case null => eventMap((originator, recipient)) = Some(result)
            case _ =>
          }
      }
    }
  }

  def dealWithClosedOrRejected(eventMap: mutable.Map[(String, String), Option[StickleState]], originator: String, recipient: String): Unit = {
    eventMap.getOrElse((originator, recipient), null) match {
      case null => eventMap((originator, recipient)) = None
      case _ =>
    }
  }

  def postResponseEventToPeer(originatorPhoneNumber: String, recipientPhoneNumber: String, status: String): Unit = {
    Logger.debug(s"stickle $status received by source, origin: $originatorPhoneNumber, recipient: $recipientPhoneNumber")
    persistStickleEvent(originatorPhoneNumber, None, recipientPhoneNumber, status)
    val message = StickleStatusChangedEvent(phoneNumber, status)
    context.actorSelection(s"../$originatorPhoneNumber/out") ! message
  }

  def postStickleEventToPeer(targetPhoneNumber: String, status: String, sourceDisplayName: Option[String], message: StickleEvent): Unit = {
    Logger.debug(s"stickle open received by source to: $targetPhoneNumber")
    persistStickleEvent(phoneNumber, sourceDisplayName, targetPhoneNumber, status)
    context.actorSelection(s"../$targetPhoneNumber/out") ! message
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
