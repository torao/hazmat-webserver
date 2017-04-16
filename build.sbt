name := "webserver"

organization := "at.hazm"

version := "1.0"

scalaVersion := "2.12.1"

scalacOptions ++= Seq("-unchecked", "-deprecation", "-feature")

scalacOptions in Test ++= Seq("-Yrangepos" /* for Specs2 */)

javacOptions ++= Seq("-source", "1.8", "-target", "1.8")

libraryDependencies ++= Seq(
  "org.scala-lang.modules" %% "scala-xml" % "1.0.+",
  "com.twitter"  %% "finagle-http"   % "6.43.+",
  "com.typesafe" % "config" % "1.3.+",
  "com.typesafe.play" % "play-json_2.12" % "2.6.0-M6",
  "com.vaadin" % "vaadin-sass-compiler" % "0.9.13",
  "org.slf4j"     % "slf4j-log4j12"  % "1.7.+"
)