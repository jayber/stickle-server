package controllers

import javax.inject.Inject

import akka.actor.{ActorSystem, _}
import akka.stream.Materializer
import play.api.Logger
import play.api.libs.json.{JsValue, Json}
import play.api.libs.streams.ActorFlow
import play.api.mvc.{Controller, WebSocket}
import reactivemongo.bson.{BSONDocument, _}

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future
import scala.concurrent.duration._

class SocketController @Inject()(implicit system: ActorSystem, materializer: Materializer) extends Controller with StickleDb {

  object StickleWebSocketActor {
    def props(out: ActorRef) = Props(new StickleWebSocketActor(out))
  }

  class StickleWebSocketActor(out: ActorRef) extends Actor {

    var myUser: Future[Option[ActorRef]] = Future.successful(None)

    def receive = {
      case msg: JsValue =>
        Logger.debug("received: " + Json.stringify(msg))
        (msg \ "event").as[String] match {
          case "authenticate" =>
            myUser = authenticate(msg, myUser)
          case event =>
            myUser foreach {
              case Some(user) => user !(event, msg)
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
              findOrCreateUserActor(result.getAs[String]("phoneNumber"))
            case _ =>
              self ! PoisonPill
              Future.successful(None) //this would actually be the same as returning 'myUser' but is more clear
          }
        }
      }
    }

    def findOrCreateUserActor(phoneNumber: Option[String]): Future[Some[ActorRef]] = {
      system.actorSelection(s"user/${phoneNumber.get}").resolveOne(5 seconds)
        .recover { case e =>
          Logger.debug("creating new UserActor")
          system.actorOf(UserActor.props(out, phoneNumber.get), phoneNumber.get)
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

  def get = WebSocket.accept[JsValue, JsValue] { request =>
    Logger.debug(s"socket get")
    ActorFlow.actorRef(out => {
      StickleWebSocketActor.props(out)
    })
  }
}
