package actors

import akka.actor._
import akka.pattern.ask
import akka.util.Timeout
import org.jasypt.digest.StandardStringDigester
import play.api.Logger
import play.api.libs.json.{JsValue, Json}
import play.api.libs.ws.WSClient
import reactivemongo.bson.{BSONDocument, _}
import services.{StickleConsts, StickleDb}

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future
import scala.concurrent.duration._

object StickleWebSocketActor {
  def props(out: ActorRef)(implicit system: ActorSystem, ws: WSClient) = Props(new StickleWebSocketActor(out))
}

class StickleWebSocketActor(out: ActorRef)(implicit system: ActorSystem, ws: WSClient) extends Actor with StickleDb with StickleConsts {

  var userMessageHandler: Future[Option[ActorRef]] = Future.successful(None)

  def receive = {
    case msg: JsValue =>
      (msg \ "event").as[String] match {
        case "authenticate" =>
          userMessageHandler = authenticate(msg, userMessageHandler)
        case event =>
          userMessageHandler foreach {
            case Some(messageHandler) => messageHandler !(event, msg)
            case None => // self ! PoisonPill ? seems a bit extreme
          }
      }
  }

  def authenticate(msg: JsValue, userOptFut: Future[Option[ActorRef]]): Future[Option[ActorRef]] = {
    userOptFut.flatMap {
      case Some(_) => ackAuthentication()
        userOptFut
      case None => fuserCollection.flatMap {
        val authId: String = (msg \ "data" \ "authId").as[String]
        val digester = new StandardStringDigester()
        digester.setSaltSizeBytes(0)
        val authIdDigest: String = digester.digest(authId)
        _.find(BSONDocument("authId" -> authIdDigest))
          .one[BSONDocument].flatMap {
          case Some(result) =>
            Logger.debug(s"found user: ${result.getAs[String]("phoneNumber")}, pushRegId: ${(msg \ "data" \ pushRegistrationId).as[String]}")
            fuserCollection.foreach {
              _.update[BSONDocument, BSONDocument](
                BSONDocument("authId" -> authIdDigest),
                BSONDocument("$set" -> BSONDocument(pushRegistrationId -> (msg \ "data" \ pushRegistrationId).as[String])))
            }
            findOrCreateIncomingMessageActor(result.getAs[String]("phoneNumber").get, result.getAs[String]("displayName").get)
          case _ =>
            Logger.debug(s"no user found")
            ackAuthenticationFailure()
            self ! PoisonPill
            Future.successful(None) //this would actually be the same as returning 'myUser' but is more clear
        }
      }
    }
  }

  def findOrCreateIncomingMessageActor(phoneNumber: String, displayName: String): Future[Some[ActorRef]] = {

    implicit val timeout = Timeout(5 seconds)

    system.actorSelection(s"user/$phoneNumber").resolveOne
      .recover { case e =>
      Logger.debug("creating new UserActor")
      system.actorOf(UserActor.props(phoneNumber, displayName), phoneNumber)
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