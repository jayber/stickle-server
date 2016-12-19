package actors

import java.util.Date

import actors.UserActor._
import akka.actor.{Actor, ActorRef, Props}
import play.api.Logger
import play.api.libs.iteratee.Iteratee
import play.api.libs.ws.WSClient
import reactivemongo.bson._
import services.StickleDb

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future

object UserActor {
  def props(phoneNumber: String, displayName: String)(implicit ws: WSClient) = Props(new UserActor(phoneNumber, displayName))

  case class CheckState(target: String, inbound: Boolean)

  val closed: String = "closed"
  val accepted: String = "accepted"
  val unaccepted: String = "un-accepted"
  val rejected: String = "rejected"
  val open: String = "open"
  val completed: String = "completed"

  case class StickleState(id: Option[String], originator: String, originatorDisplayName: String, recipient: String, createdDate: Date, state: String)

  implicit val stickleStateDocumentReader: BSONDocumentReader[StickleState] = new BSONDocumentReader[StickleState] {
    override def read(bson: BSONDocument): StickleState = {
      Logger(this.getClass).trace(s"reading from db: ${bson.getAs[BSONObjectID]("_id").map(_.stringify)}")
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

abstract class StashedStickleStates
case class NothingNewer() extends StashedStickleStates
case class DeleteAnythingOlder() extends StashedStickleStates
case class StashedResponse(stickleState: StickleState) extends StashedStickleStates

class UserActor(phoneNumber: String, displayName: String)(implicit ws: WSClient) extends Actor with StickleDb {

  Logger(this.getClass).trace(s"UserActor - phoneNumber: $phoneNumber, path: ${self.path.toString}")

  val outgoingMessageActor = context.actorOf(OutgoingMessageActor.props(), "out")
  val incomingMessageActor = context.actorOf(IncomingMessageActor.props(phoneNumber, displayName, outgoingMessageActor), "in")

  override def receive = {

    case socket: ActorRef =>
      outgoingMessageActor ! socket
      sender() ! incomingMessageActor

    case "sync" =>
      Logger(this.getClass).trace(s"sync phoneNumber: $phoneNumber")
      syncStateFromDb()

    case message@CheckState(targetPhoneNumber, inbound) =>
      Logger(this.getClass).trace(s"check-state message: $message")

      val query: BSONDocument = inbound match {
        case true =>
          BSONDocument("originator" -> targetPhoneNumber, "recipient" -> phoneNumber)
        case _ =>
          BSONDocument("originator" -> phoneNumber, "recipient" -> targetPhoneNumber)
      }

      findSendAndDeleteStickleEvents(query, broadcastClosedAndRejected = true).onSuccess {
        case true =>
          Logger(this.getClass).trace(s"doing an empty for $phoneNumber, target=$targetPhoneNumber, inbound=$inbound")
          inbound match {
            case true =>
              outgoingMessageActor ! StickleState(None, targetPhoneNumber, "", phoneNumber, new Date(), closed)
            case _ =>
              outgoingMessageActor ! StickleState(None, phoneNumber, "", targetPhoneNumber, new Date(), closed)
          }
      }
  }

  def syncStateFromDb(): Unit = {
    val query: BSONDocument = BSONDocument("$or" -> BSONArray(BSONDocument("originator" -> phoneNumber), BSONDocument("recipient" -> phoneNumber)))
    findSendAndDeleteStickleEvents(query, broadcastClosedAndRejected = false)
  }

  def findSendAndDeleteStickleEvents(query: BSONDocument, broadcastClosedAndRejected: Boolean): Future[Boolean] = {
    var empty = true
    var eventMap = Map[(String, String), StashedStickleStates]()
    fstickleCollection flatMap { coll =>
      coll.find(query)
        .sort(BSONDocument("createdDate" -> -1)).cursor[StickleState]().enumerate().run(Iteratee.foreach { row =>
        eventMap = handleStickleState(broadcastClosedAndRejected, row, eventMap)
        empty = false
      })
    } map { nothing =>
      empty
    }
  }

  private def handleStickleState(broadcastClosedAndRejected: Boolean, currentStickleEvent: StickleState, newerEvents: Map[(String, String), StashedStickleStates]): Map[(String, String), StashedStickleStates] = {
    Logger(this.getClass).trace(s"processing stickle event: ${currentStickleEvent.toString} broadcastClosedAndRejected: $broadcastClosedAndRejected")

    val finishedStates: List[String] = List(completed, closed, rejected)
    val respondedStates: List[String] = List(unaccepted, accepted)
    val currentEventState = currentStickleEvent.state

    val newerEvent: StashedStickleStates = newerEvents.getOrElse((currentStickleEvent.originator, currentStickleEvent.recipient), NothingNewer())
    newerEvent match {

      case NothingNewer() if finishedStates.contains(currentEventState) =>
        Logger(this.getClass).trace("prev=NothingNewer, current=finishedStates.contains(currentState)")
        if (broadcastClosedAndRejected) {
          outgoingMessageActor ! currentStickleEvent
        }
        newerEvents + ((currentStickleEvent.originator -> currentStickleEvent.recipient) -> DeleteAnythingOlder())

      case NothingNewer() if respondedStates.contains(currentEventState) =>
        Logger(this.getClass).trace("prev=NothingNewer, current=respondedStates.contains(currentState)")
        newerEvents + ((currentStickleEvent.originator -> currentStickleEvent.recipient) -> StashedResponse(currentStickleEvent))

      case NothingNewer() if currentEventState == open =>
        Logger(this.getClass).trace("prev=NothingNewer, current=open")
        outgoingMessageActor ! currentStickleEvent
        newerEvents + ((currentStickleEvent.originator -> currentStickleEvent.recipient) -> DeleteAnythingOlder())

      case StashedResponse(response) if currentEventState == open =>
        Logger(this.getClass).trace("prev=StashedResponse, currentEvent == open")
        outgoingMessageActor ! StickleState(None, currentStickleEvent.originator, currentStickleEvent.originatorDisplayName,
          currentStickleEvent.recipient, response.createdDate, response.state)
        newerEvents + ((currentStickleEvent.originator -> currentStickleEvent.recipient) -> DeleteAnythingOlder())

      case DeleteAnythingOlder() =>
        Logger(this.getClass).trace("prev=DeleteAnythingOlder")
        deleteFromDB(currentStickleEvent)
        newerEvents

      case _ =>
        newerEvents
    }
  }

  def deleteFromDB(result: StickleState): Unit = {
    Logger(this.getClass).trace(s"removing $result")
    fstickleCollection foreach (coll => coll.remove(BSONDocument("_id" -> BSONObjectID(result.id.get))))
  }
}



