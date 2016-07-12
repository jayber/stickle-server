package services

import actors.OutgoingMessageActor.{StickleClosedEvent, StickleEvent, StickleOnEvent, StickleStatusChangedEvent}
import play.api.Logger
import play.api.libs.json.Json
import play.api.libs.ws.WSClient

import scala.concurrent.ExecutionContext.Implicits.global


object PushNotifications {

  def sendNotification(id: String, message: StickleEvent)(implicit ws: WSClient): Unit = {

    Logger.debug(s"sendNotification, id: $id")

    val request = ws.url("https://gcm-http.googleapis.com/gcm/send")
      .withHeaders("Content-Type" -> "application/json", "Authorization" -> "key=AIzaSyDEftdRdvgPLH-6OZE-Ds082rEUG-rCBOg")

    val response = request.post(Json.obj("to" -> id, "notification" -> Json.obj(
      "title" -> "Stickle",
      "icon" -> "myicon",
      "body" -> (message match {
        case StickleOnEvent(_, _) => "Stickled"
        case StickleClosedEvent(_) => "Closed"
        case StickleStatusChangedEvent(_, state) => state
      })
    )))

    response foreach { response =>
      Logger.debug(s"push-notification: ${response.statusText}, body: ${response.body}")
    }
  }

}
