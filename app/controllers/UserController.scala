package controllers

import java.math.BigInteger
import java.security.SecureRandom
import java.util
import java.util.Date
import javax.inject._

import com.amazonaws.services.sns.AmazonSNSClient
import com.amazonaws.services.sns.model.{MessageAttributeValue, PublishRequest}
import org.apache.commons.codec.binary.Base64
import org.jasypt.digest.StandardStringDigester
import play.api.Logger
import play.api.libs.json.Json
import play.api.mvc._
import reactivemongo.bson.{BSONDateTime, BSONDocument, BSONObjectID}
import services.StickleDb

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future


@Singleton
class UserController @Inject() extends Controller with StickleDb {

  def register(phoneNum: String) = Action.async { request =>
    val displayName = (request.body.asJson.get \ "displayName").as[String]
    Logger.debug(s"register $phoneNum, $displayName")
    val userId = BSONObjectID.generate().stringify
    val query = BSONDocument("phoneNumber" -> phoneNum)
    fuserCollection.flatMap(_.find(query).one[BSONDocument]).flatMap {
      case Some(user) =>
        fuserCollection.flatMap {
          _.update[BSONDocument, BSONDocument](
            BSONDocument("_id" -> user.get("_id")),
            BSONDocument("$set" -> BSONDocument("verificationCode" -> sendSMS(phoneNum), "createdDate" -> BSONDateTime(System.currentTimeMillis()))))
        }.map { wresult => Ok("") }
      case None =>
        fuserCollection.flatMap {
          _.insert(BSONDocument(
            "_id" -> userId,
            "phoneNumber" -> phoneNum,
            "displayName" -> displayName,
            "verificationCode" -> sendSMS(phoneNum),
            "createdDate" -> BSONDateTime(System.currentTimeMillis())
          )).map(wresult => Ok(""))
            .recover { case e: Throwable => InternalServerError("error:" + e.getMessage) }
        }
    }
  }

  def verify(phoneNum: String) = Action.async { request =>
    val verificationCode = (request.body.asJson.get \ "verificationCode").as[String]
    Logger.debug(s"verify $phoneNum, $verificationCode")
    val query = BSONDocument("phoneNumber" -> phoneNum, "verificationCode" -> verificationCode.toUpperCase())
    fuserCollection.flatMap(_.find(query).one[BSONDocument]).flatMap {
      case Some(result) =>
        if ((new Date().getTime - result.getAs[Date]("createdDate").get.getTime()) < 1000 * 60 * 10) {

          val (authId, hashedId) = generateAuth
          fuserCollection.foreach(_.update[BSONDocument, BSONDocument](
            BSONDocument("_id" -> result.get("_id")),
            BSONDocument("$set" -> BSONDocument("authId" -> hashedId))))

          Future(Ok(Json.obj("authId" -> authId)))
        } else {
          Future(BadRequest("Verification failed"))
        }
      case None => Future(BadRequest("Verification failed"))
    }
  }

  def resend(phoneNum: String) = Action.async { request =>
    Logger.debug(s"resend code $phoneNum")

    val query = BSONDocument("phoneNumber" -> phoneNum)
    fuserCollection.flatMap(_.find(query).one[BSONDocument]).flatMap {
      case Some(user) =>
        fuserCollection.flatMap {
          _.update[BSONDocument, BSONDocument](
            BSONDocument("_id" -> user.get("_id")),
            BSONDocument("$set" -> BSONDocument("verificationCode" -> sendSMS(phoneNum))))
        }.map { wresult => Ok("") }
      case None => Future(BadRequest(""))
    }
  }

  def messageAttributes(): util.Map[String, MessageAttributeValue] = {
    val smsAttributes = new util.HashMap[String, MessageAttributeValue]()
    smsAttributes.put("AWS.SNS.SMS.SenderID", new MessageAttributeValue()
      .withStringValue("Stickle") //The sender ID shown on the device.
      .withDataType("String"))
    smsAttributes
  }

  val random = new SecureRandom()

  def generateAuth: (String, String) = {
    val code = Base64.encodeBase64URLSafeString(new BigInteger(512, random).toByteArray())

    val digester = new StandardStringDigester()
    digester.setSaltSizeBytes(0)
    val hashedCode = digester.digest(code)
    (code, hashedCode)
  }

  def sendSMS(phoneNum: String): String = {
    if (phoneNum.length() > 6) {
      val code = new BigInteger(56, random).toString(32).toUpperCase().substring(0, 6)

      val snsClient = new AmazonSNSClient()
      val message =
        s"""$code
            |Stickle verification code.
            |Please enter into Stickle SMS code field to verify.""".stripMargin
      /*        |Or click:
        |app.stickle.co/v/$code""".stripMargin*/
      val result = snsClient.publish(new PublishRequest()
        .withMessage(message)
        .withMessageAttributes(messageAttributes())
        .withPhoneNumber(phoneNum))
      Logger.debug(s"SMS sent messageId:${result.toString}")
      code
    } else {
      phoneNum
    }
  }
}
