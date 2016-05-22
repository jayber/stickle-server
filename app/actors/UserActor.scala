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

object UserActor {
  def props(phoneNumber: String, displayName: String) = Props(new UserActor(phoneNumber, displayName))

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

  implicit val stickleStateDocumentWriter: BSONDocumentWriter[StickleState] = new BSONDocumentWriter[StickleState] {
    override def write(state: StickleState): BSONDocument = BSONDocument("originator" -> state.originator,
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

  }

  def updateFromDb(): Unit = {
    val eventMap = mutable.Map[(String, String), Option[StickleState]]()
    fstickleCollection.map {
      _.find(BSONDocument("$or" -> BSONArray(BSONDocument("originator" -> phoneNumber), BSONDocument("recipient" -> phoneNumber))))
        .sort(BSONDocument("createdDate" -> -1)).cursor[StickleState]().enumerate()
    }
      .foreach { enumerator => enumerator.run(Iteratee.foreach {
      case result@StickleState(originator, _, recipient, _, `closed`) =>
        dealWithClosedOrRejected(eventMap, originator, recipient, result)
      case result@StickleState(originator, _, recipient, _, `rejected`) =>
        dealWithClosedOrRejected(eventMap, originator, recipient, result)
      case result@StickleState(originator, originatorDisplayName, recipient, _, `open`) =>
        eventMap.getOrElse((originator, recipient), null) match {
          case null => outgoingMessageActor ! result
            eventMap((originator, recipient)) = None
          case Some(previous) => outgoingMessageActor ! StickleState(originator, originatorDisplayName, recipient, previous.createdDate, previous.state)
            eventMap((originator, recipient)) = None
          case _ => deleteFromDB(result)
        }
      case result@StickleState(originator, originatorDisplayName, recipient, _, _) =>
        eventMap.getOrElse((originator, recipient), null) match {
          case null => eventMap((originator, recipient)) = Some(result)
          case _ => deleteFromDB(result)
        }
    })
    }
  }

  def dealWithClosedOrRejected(eventMap: mutable.Map[(String, String), Option[StickleState]], originator: String, recipient: String, result: StickleState): Unit = {
    eventMap.getOrElse((originator, recipient), null) match {
      case null => eventMap((originator, recipient)) = None
      case _ => deleteFromDB(result)
    }
  }

  def deleteFromDB(result: StickleState): Unit = {
    Logger.trace(s"removing $result")
    fstickleCollection foreach (coll => coll.remove(result))
  }
}
