package controllers

import javax.inject._

import play.api.Logger
import play.api.libs.json.Json
import play.api.mvc._
import reactivemongo.api.collections.bson.BSONCollection
import reactivemongo.bson.{BSONDocument, BSONObjectID}

import scala.concurrent.Future

import scala.concurrent.ExecutionContext.Implicits.global


@Singleton
class UserController @Inject() extends Controller with StickleDb {

  def register(phoneNum: String) = Action.async { request =>
    val displayName = (request.body.asJson.get \ "displayName").as[String]
    Logger.debug(s"register $phoneNum, $displayName")
    val userId = BSONObjectID.generate.stringify
    val query = BSONDocument("phoneNumber" -> phoneNum)
    fuserCollection.flatMap(_.find(query).one[BSONDocument]).flatMap {
      case Some(_) => Future(BadRequest("Already registered"))
      case None =>
        fuserCollection.flatMap {
          _.insert(BSONDocument(
            "_id" -> userId,
            "phoneNumber" -> phoneNum,
            "displayName" -> displayName
          )).map(wresult => Ok(Json.obj(
            "userId" -> userId)))
            .recover { case e: Throwable => InternalServerError("error:" + e.getMessage) }
        }
    }
  }
}
