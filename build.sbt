organization := "at.hazm"

name := "hazmat-webserver"

version := "1.2.7"

scalaVersion := "2.12.15"

scalacOptions ++= Seq("-unchecked", "-deprecation", "-feature")

scalacOptions in Test ++= Seq("-Yrangepos" /* for Specs2 */)

javacOptions ++= Seq("-source", "1.11", "-target", "1.11")

libraryDependencies ++= Seq(
  "org.scala-lang.modules" %% "scala-xml" % "2.0.1",
  "com.twitter" %% "finagle-http" % "22.1.0",
  "com.typesafe" % "config" % "1.3.+" exclude("com.google.guava", "guava"),
  "com.typesafe.play" %% "play-json" % "2.9.2",
  "com.vaadin" % "vaadin-sass-compiler" % "0.9.13",
  "com.mangofactory" % "typescript4j" % "0.4.0",
  "org.codehaus.janino" % "janino" % "3.1.6", // Dynamic Java Compiler
  "org.apache.xmlgraphics" % "batik-transcoder" % "1.9.1", // transform SVG to PNG
  "org.slf4j" % "slf4j-log4j12" % "1.7.35"
)

// Docker image and executable shell archive settings
// ClasspathJarPlugin is for long-classpath on windows
enablePlugins(JavaServerAppPackaging, UniversalPlugin, ClasspathJarPlugin)
