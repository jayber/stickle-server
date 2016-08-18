package services

import actors.OutgoingMessageActor.{StickleClosedEvent, StickleEvent, StickleOnEvent, StickleStatusChangedEvent}
import play.api.Logger
import play.api.libs.json.Json
import play.api.libs.ws.WSClient

import scala.concurrent.ExecutionContext.Implicits.global


object PushNotifications {

  def sendNotification(id: String, nameOpt: Option[String], message: StickleEvent)(implicit ws: WSClient): Unit = {

    Logger.debug(s"sendNotification, id: $id")

    val request = ws.url("https://gcm-http.googleapis.com/gcm/send")
      .withHeaders("Content-Type" -> "application/json", "Authorization" -> "key=AIzaSyDEftdRdvgPLH-6OZE-Ds082rEUG-rCBOg")

    val response = request.post(Json.obj("to" -> id, "notification" -> (message match {
      case StickleOnEvent(_, name) => Json.obj(
        "title" -> s"$name wants to talk",
        "icon" -> "myicon",
        "sound" -> "default",
        "tag" -> name,
        "priority" -> "high",
        "body" -> s"$name stickled you. Tap to respond."
      )
      case StickleClosedEvent(_) => Json.obj(
        "title" -> s"${nameOpt.getOrElse("")} closed your stickle",
        "icon" -> "myicon",
        "priority" -> "high",
        "tag" -> nameOpt.getOrElse[String](""),
        "body" -> s"Tap to view"
      )
      case state@StickleStatusChangedEvent(_, _) => Json.obj(
        "title" -> s"${nameOpt.getOrElse("")} ${state.translateState}",
        "icon" -> "myicon",
        "priority" -> "high",
        "sound" -> "default",
        "tag" -> nameOpt.getOrElse[String](""),
        "body" -> s"Tap to view and respond."
      )
    })))

    response foreach { response =>
      Logger.debug(s"push-notification response: ${response.statusText}, body: ${response.body}")
    }
  }

}
