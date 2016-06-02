package controllers

import javax.inject.{Inject, Singleton}

import play.api.Logger
import play.api.libs.json.{JsValue, Json}
import play.api.mvc.{Action, Controller}
import reactivemongo.bson.{BSONDateTime, BSONDocument}
import reactivemongo.play.json._
import services.StickleDb

import scala.concurrent.ExecutionContext.Implicits.global

@Singleton
class FeedbackController @Inject() extends Controller with StickleDb {

  def feedback = Action.async { request =>
    val msg: JsValue = request.body.asJson.get
    Logger.trace("feedback: " + Json.stringify(msg))
    saveFeedback(msg).map { wresult =>
      Logger.debug("Saved feedback")
      Ok("")
    }.recover { case e: Throwable =>
      Logger.error("Feedback error", e)
      InternalServerError("error:" + e.getMessage)
    }
  }

  def saveFeedback(msg: JsValue) = {
    ffeedbackCollection map {
      _.insert(
        BSONDocument("title" -> (msg \ "title").as[String],
          "content" -> (msg \ "content").as[String],
          "displayName" -> (msg \ "displayName").as[String],
          "phoneNumber" -> (msg \ "phoneNumber").as[String],
          "userId" -> (msg \ "userId").as[String],
          "log" -> (msg \ "log").as[String],
          "browserInfo" -> (msg \ "browserInfo").get.as[BSONDocument],
          "createdDate" -> BSONDateTime(System.currentTimeMillis)
        )
      )
    }
  }
}
