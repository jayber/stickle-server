package services

import reactivemongo.api.collections.bson.BSONCollection
import reactivemongo.api.{MongoConnection, MongoDriver}

import scala.concurrent.ExecutionContext.Implicits.global

object StickleDb {
  val connection: MongoConnection = (new MongoDriver).connection(List("localhost"))
  val fdb = connection.database("stickle")
  val fuserCollection = fdb.map { ddb => ddb.collection[BSONCollection]("user") }
  val fstickleCollection = fdb.map { ddb => ddb.collection[BSONCollection]("stickle") }
  val ffeedbackCollection = fdb.map { ddb => ddb.collection[BSONCollection]("feedback") }
}

trait StickleDb {
  val connection: MongoConnection = StickleDb.connection
  val fdb = StickleDb.fdb
  val fuserCollection = StickleDb.fuserCollection
  val fstickleCollection = StickleDb.fstickleCollection
  val ffeedbackCollection = StickleDb.ffeedbackCollection
}
