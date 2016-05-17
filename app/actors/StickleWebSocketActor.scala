package actors

import akka.actor._
import play.api.Logger
import play.api.libs.json.{JsValue, Json}
import reactivemongo.bson.{BSONDocument, _}

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future
import scala.concurrent.duration._

object StickleWebSocketActor {
  def props(out: ActorRef)(implicit system: ActorSystem) = Props(new StickleWebSocketActor(out))
}

class StickleWebSocketActor(out: ActorRef)(implicit system: ActorSystem) extends Actor with StickleDb {

  var myUser: Future[Option[ActorRef]] = Future.successful(None)

  def receive = {
    case msg: JsValue =>
      Logger.debug("received: " + Json.stringify(msg))
      (msg \ "event").as[String] match {
        case "authenticate" =>
          myUser = authenticate(msg, myUser)
        case event =>
          myUser foreach {
            case Some(user) => user ! (event, msg)
            case None => // self ! PoisonPill ? seems a bit extreme
          }
      }
  }

  def authenticate(msg: JsValue, userOptFut: Future[Option[ActorRef]]) = {
    userOptFut.flatMap {
      case Some(_) => ackAuthentication()
        userOptFut
      case None => fuserCollection.flatMap {
        Logger.debug(s"finding user: ${(msg \ "data" \ "userId").as[String]}")
        _.find(BSONDocument("_id" -> (msg \ "data" \ "userId").as[String]))
          .one[BSONDocument].flatMap {
          case Some(result) =>
            Logger.debug(s"found user: ${result.getAs[String]("phoneNumber")}")
            findOrCreateIncomingMessageActor(result.getAs[String]("phoneNumber"), result.getAs[String]("displayName"))
          case _ =>
            self ! PoisonPill
            Future.successful(None) //this would actually be the same as returning 'myUser' but is more clear
        }
      }
    }
  }

  def findOrCreateIncomingMessageActor(phoneNumber: Option[String], displayName: Option[String]): Future[Some[ActorRef]] = {
    system.actorSelection(s"user/${phoneNumber.get}").resolveOne(5 seconds)
      .recover { case e =>
      Logger.debug("creating new UserActor")
      system.actorOf(IncomingMessageActor.props(out, phoneNumber.get, displayName.get), phoneNumber.get)
    }
      .map { user =>
      user ! out
      ackAuthentication()
      Some(user)
    }
  }

  def ackAuthentication() = {
    out ! Json.obj("event" -> "authenticated")
  }
}