package controllers

import javax.inject.Inject

import actors.{StickleDb, StickleWebSocketActor}
import akka.actor.ActorSystem
import akka.stream.Materializer
import play.api.Logger
import play.api.libs.json.JsValue
import play.api.libs.streams.ActorFlow
import play.api.mvc.{Controller, WebSocket}

class SocketController @Inject()(implicit system: ActorSystem, materializer: Materializer) extends Controller {

  def get = WebSocket.accept[JsValue, JsValue] { request =>
    Logger.debug(s"socket get")
    ActorFlow.actorRef(out => {
      StickleWebSocketActor.props(out)
    })
  }
}
