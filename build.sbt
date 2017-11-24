name := "webserver"

organization := "at.hazm"

version := "1.0.1"

scalaVersion := "2.12.4"

scalacOptions ++= Seq("-unchecked", "-deprecation", "-feature")

scalacOptions in Test ++= Seq("-Yrangepos" /* for Specs2 */)

javacOptions ++= Seq("-source", "1.8", "-target", "1.8")

libraryDependencies ++= Seq(
  "org.scala-lang.modules" %% "scala-xml" % "1.0.+",
  "com.twitter"  %% "finagle-http"   % "6.43.+",
  "com.typesafe" % "config" % "1.3.+",
  "com.typesafe.play" % "play-json_2.12" % "2.6.0-M6",
  "com.vaadin" % "vaadin-sass-compiler" % "0.9.13",
  "com.mangofactory" % "typescript4j" % "0.4.0",
  "org.codehaus.janino" % "janino" % "3.+",             // Dynamic Java Compiler
  "org.slf4j"     % "slf4j-log4j12"  % "1.7.+"
)

// Docker image settings

enablePlugins(JavaServerAppPackaging, UniversalPlugin, DockerPlugin)

dockerBaseImage in Docker := "java:8-jdk-alpine"

// version in Docker := new java.text.SimpleDateFormat("yyyyMMddHHmm").format(new java.util.Date())
//version in Docker := "latest"
version in Docker := version.value

maintainer in Docker := "TAKAMI Torao <koiroha@mail.com>"

packageName in Docker := "torao/hazmat-webserver"

dockerExposedPorts in Docker := Seq(8089, 80)

dockerUpdateLatest in Docker := true

// dockerRepository in Docker := Some("torao/hazmat-webserver")
