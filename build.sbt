name := """stickle-server"""

version := "1.0-SNAPSHOT"

lazy val root = (project in file(".")).enablePlugins(PlayScala)

scalaVersion := "2.11.8"

libraryDependencies ++= Seq(
  filters,
  jdbc,
  cache,
  ws,
  "org.scalatestplus.play" %% "scalatestplus-play" % "1.5.0-RC1" % Test
)

libraryDependencies ++= Seq(
  "org.reactivemongo" %% "play2-reactivemongo" % "0.11.11",
  "com.amazonaws" % "aws-java-sdk-sns" % "1.11.22",
  "commons-codec" % "commons-codec" % "1.9",
  "com.twilio.sdk" % "twilio" % "7.3.0",
  "org.jasypt" % "jasypt" % "1.9.2"
)

resolvers += "scalaz-bintray" at "http://dl.bintray.com/scalaz/releases"


fork in run := false