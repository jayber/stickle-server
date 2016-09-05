package controllers

import java.util
import javax.inject._

import com.amazonaws.services.sns.AmazonSNSClient
import com.amazonaws.services.sns.model.{MessageAttributeValue, PublishRequest}
import play.api.Logger
import play.api.libs.json.Json
import play.api.mvc._
import reactivemongo.bson.{BSONDocument, BSONObjectID}
import services.StickleDb

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future


@Singleton
class UserController @Inject() extends Controller with StickleDb {

  def register(phoneNum: String) = Action.async { request =>
    val displayName = (request.body.asJson.get \ "displayName").as[String]
    Logger.debug(s"register $phoneNum, $displayName")
    sendSMS(phoneNum)
    val userId = BSONObjectID.generate().stringify
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

  def messageAttributes(): util.Map[String, MessageAttributeValue] = {
    val smsAttributes = new util.HashMap[String, MessageAttributeValue]()
    smsAttributes.put("AWS.SNS.SMS.SenderID", new MessageAttributeValue()
      .withStringValue("Stickle") //The sender ID shown on the device.
      .withDataType("String"))
    smsAttributes
  }

  def sendSMS(phoneNum: String) = {
    val snsClient = new AmazonSNSClient()
    val message =
      """Stickle registration verification""" +
        """<a href="stickle://">Stickle</a>"""

    val result = snsClient.publish(new PublishRequest()
      .withMessage(message)
      .withMessageAttributes(messageAttributes())
      .withPhoneNumber(phoneNum))
    Logger.debug(s"SMS sent messageId:${result.toString}")
  }
}
