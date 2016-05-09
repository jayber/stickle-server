package controllers

import play.api.mvc.Controller
import reactivemongo.api.collections.bson.BSONCollection

trait StickleDb {

  import reactivemongo.api._
  import scala.concurrent.ExecutionContext.Implicits.global

  val connection: MongoConnection = (new MongoDriver).connection(List("localhost"))
  val fdb = connection.database("stickle")
  val fuserCollection = fdb.map { ddb => ddb.collection[BSONCollection]("user") }
  val fstickleCollection = fdb.map { ddb => ddb.collection[BSONCollection]("stickle") }

}
