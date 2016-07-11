package actors

import java.util.Date

import actors.OutgoingMessageActor.StickleState
import actors.UserActor._
import akka.actor.{Actor, ActorRef, Props}
import play.api.Logger
import play.api.libs.iteratee._
import reactivemongo.bson._
import services.StickleDb

import scala.collection.mutable
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future

object UserActor {
  def props(phoneNumber: String, displayName: String) = Props(new UserActor(phoneNumber, displayName))

  case class CheckState(target: String, inbound: Boolean)

  val closed: String = "closed"
  val accepted: String = "accepted"
  val unaccepted: String = "un-accepted"
  val rejected: String = "rejected"
  val open: String = "open"
  val completed: String = "completed"

  implicit val stickleStateDocumentReader: BSONDocumentReader[StickleState] = new BSONDocumentReader[StickleState] {
    override def read(bson: BSONDocument): StickleState = {
      Logger.trace(s"reading: ${bson.getAs[BSONObjectID]("_id").map(_.stringify)}")
      StickleState(
        bson.getAs[BSONObjectID]("_id").map(_.stringify),
        bson.getAs[String]("originator").get,
        bson.getAs[String]("originatorDisplayName").getOrElse(""),
        bson.getAs[String]("recipient").get,
        new Date(bson.getAs[BSONDateTime]("createdDate").get.value),
        bson.getAs[String]("status").get
      )
    }
  }

  implicit val stickleStateDocumentWriter: BSONDocumentWriter[StickleState] = new BSONDocumentWriter[StickleState] {
    override def write(state: StickleState): BSONDocument = BSONDocument(
      "_id" -> state.id,
      "originator" -> state.originator,
      "originatorDisplayName" -> state.originatorDisplayName,
      "recipient" -> state.recipient,
      "createdDate" -> BSONDateTime(state.createdDate.getTime),
      "status" -> state.state
    )
  }
}

class UserActor(phoneNumber: String, displayName: String) extends Actor with StickleDb {

  Logger.trace(s"UserActor - phoneNumber: $phoneNumber, path: ${self.path.toString}")

  val outgoingMessageActor = context.actorOf(OutgoingMessageActor.props(), "out")
  val incomingMessageActor = context.actorOf(IncomingMessageActor.props(phoneNumber, displayName, outgoingMessageActor), "in")

  override def receive = {

    case socket: ActorRef =>
      outgoingMessageActor ! socket
      updateFromDb()
      sender() ! incomingMessageActor

    case message@CheckState(targetPhoneNumber, inbound) =>
      Logger.trace(s"check-state message: $message")

      val query: BSONDocument = inbound match {
        case true =>
          BSONDocument("originator" -> targetPhoneNumber, "recipient" -> phoneNumber)
        case _ =>
          BSONDocument("originator" -> phoneNumber, "recipient" -> targetPhoneNumber)
      }

      querySendAndDelete(query, broadcastClosedAndRejected = true).foreach { empty =>
        if (empty) {
          Logger.trace(s"doing an empty for $phoneNumber, target=$targetPhoneNumber, inbound=$inbound")
          inbound match {
            case true =>
              outgoingMessageActor ! StickleState(None, targetPhoneNumber, "", phoneNumber, new Date(), closed)
            case _ =>
              outgoingMessageActor ! StickleState(None, phoneNumber, "", targetPhoneNumber, new Date(), closed)
          }
        }
      }
  }

  def updateFromDb(): Unit = {
    val query: BSONDocument = BSONDocument("$or" -> BSONArray(BSONDocument("originator" -> phoneNumber), BSONDocument("recipient" -> phoneNumber)))
    querySendAndDelete(query, broadcastClosedAndRejected = false)
  }

  def querySendAndDelete(query: BSONDocument, broadcastClosedAndRejected: Boolean): Future[Boolean] = {
    val eventMap = mutable.Map[(String, String), Option[StickleState]]()
    fstickleCollection.map {
      _.find(query)
        .sort(BSONDocument("createdDate" -> -1)).cursor[StickleState]().enumerate()
    }
      .flatMap({ enumerator =>
      var empty = true
      enumerator.run(Iteratee.foreach { row =>
        empty = false
        row match {
          case result@StickleState(_, originator, _, recipient, _, `completed`) =>
            dealWithClosedOrRejected(eventMap, originator, recipient, result, broadcastClosedAndRejected = broadcastClosedAndRejected)
          case result@StickleState(_, originator, _, recipient, _, `closed`) =>
            dealWithClosedOrRejected(eventMap, originator, recipient, result, broadcastClosedAndRejected = broadcastClosedAndRejected)
          case result@StickleState(_, originator, _, recipient, _, `rejected`) =>
            dealWithClosedOrRejected(eventMap, originator, recipient, result, broadcastClosedAndRejected = broadcastClosedAndRejected)
          case result@StickleState(_, originator, originatorDisplayName, recipient, _, `open`) =>
            eventMap.getOrElse((originator, recipient), null) match {
              case null => outgoingMessageActor ! result
                eventMap((originator, recipient)) = None
              case Some(previous) => outgoingMessageActor ! StickleState(None, originator, originatorDisplayName, recipient, previous.createdDate, previous.state)
                eventMap((originator, recipient)) = None
              case _ => deleteFromDB(result)
            }
          case result@StickleState(_, originator, originatorDisplayName, recipient, _, _) =>
            eventMap.getOrElse((originator, recipient), null) match {
              case null => eventMap((originator, recipient)) = Some(result)
              case _ => deleteFromDB(result)
            }
        }
      }).map { _ =>
        empty
      }
    })
  }

  def dealWithClosedOrRejected(eventMap: mutable.Map[(String, String), Option[StickleState]], originator: String, recipient: String, result: StickleState, broadcastClosedAndRejected: Boolean): Unit = {
    eventMap.getOrElse((originator, recipient), null) match {
      case null => eventMap((originator, recipient)) = None
        if (broadcastClosedAndRejected) {
          outgoingMessageActor ! result
        }
      case _ => deleteFromDB(result)
    }
  }

  def deleteFromDB(result: StickleState): Unit = {
    Logger.trace(s"removing $result")
    fstickleCollection foreach (coll => coll.remove(BSONDocument("_id" -> BSONObjectID(result.id.get))))
  }
}
