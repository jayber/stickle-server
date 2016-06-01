package actors

import akka.actor._
import akka.pattern.ask
import akka.util.Timeout
import play.api.Logger
import play.api.libs.json.{JsValue, Json}
import reactivemongo.bson.{BSONDocument, _}
import services.StickleDb

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future
import scala.concurrent.duration._

object StickleWebSocketActor {
  def props(out: ActorRef)(implicit system: ActorSystem) = Props(new StickleWebSocketActor(out))
}

class StickleWebSocketActor(out: ActorRef)(implicit system: ActorSystem) extends Actor with StickleDb {

  var userMessageHandler: Future[Option[ActorRef]] = Future.successful(None)

  def receive = {
    case msg: JsValue =>
      Logger.debug("received: " + Json.stringify(msg))
      (msg \ "event").as[String] match {
        case "feedback" =>
          saveFeedback(msg)
        case "authenticate" =>
          userMessageHandler = authenticate(msg, userMessageHandler)
        case event =>
          userMessageHandler foreach {
            case Some(messageHandler) => messageHandler !(event, msg)
            case None => // self ! PoisonPill ? seems a bit extreme
          }
      }
  }

  def saveFeedback(msg: JsValue): Unit = {
    ffeedbackCollection foreach {
      _.insert(
        BSONDocument("title" -> (msg \ "data" \ "title").as[String],
          "content" -> (msg \ "data" \ "content").as[String],
          "displayName" -> (msg \ "data" \ "displayName").as[String],
          "phoneNumber" -> (msg \ "data" \ "phoneNumber").as[String],
          "userId" -> (msg \ "data" \ "userId").as[String],
          "createdDate" -> BSONDateTime(System.currentTimeMillis)
        )
      )
    }
  }

  def authenticate(msg: JsValue, userOptFut: Future[Option[ActorRef]]): Future[Option[ActorRef]] = {
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
            Logger.debug(s"no user for ${(msg \ "data" \ "userId").as[String]}")
            ackAuthenticationFailure()
            self ! PoisonPill
            Future.successful(None) //this would actually be the same as returning 'myUser' but is more clear
        }
      }
    }
  }

  def findOrCreateIncomingMessageActor(phoneNumber: Option[String], displayName: Option[String]): Future[Some[ActorRef]] = {

    implicit val timeout = Timeout(5 seconds)

    system.actorSelection(s"user/${phoneNumber.get}").resolveOne
      .recover { case e =>
      Logger.debug("creating new UserActor")
      system.actorOf(UserActor.props(phoneNumber.get, displayName.get), phoneNumber.get)
    }
      .flatMap { user =>
      (user ? out).map { case incoming: ActorRef =>
        ackAuthentication()
        Some(incoming)
      }
    }
  }

  def ackAuthentication() = {
    out ! Json.obj("event" -> "authenticated")
  }

  def ackAuthenticationFailure() = {
    out ! Json.obj("event" -> "authentication-failed")
  }
}