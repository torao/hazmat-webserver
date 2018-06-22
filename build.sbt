organization := "at.hazm"

name := "hazmat-webserver"

version := "1.1.0"

scalaVersion := "2.12.6"

scalacOptions ++= Seq("-unchecked", "-deprecation", "-feature")

scalacOptions in Test ++= Seq("-Yrangepos" /* for Specs2 */)

javacOptions ++= Seq("-source", "1.8", "-target", "1.8")

libraryDependencies ++= Seq(
  "org.scala-lang.modules" %% "scala-xml" % "1.0.+",
  "com.twitter"  %% "finagle-http"   % "6.43.+",
  "com.typesafe" % "config" % "1.3.+" exclude("com.google.guava", "guava"),
  "com.typesafe.play" % "play-json_2.12" % "2.6.0-M6",
  "com.vaadin" % "vaadin-sass-compiler" % "0.9.13",
  "com.mangofactory" % "typescript4j" % "0.4.0",
  "org.codehaus.janino" % "janino" % "3.+",                 // Dynamic Java Compiler
  "org.apache.xmlgraphics" % "batik-transcoder" % "1.9.1",  // transform SVG to PNG
  "org.slf4j"     % "slf4j-log4j12"  % "1.7.+"
)

// Docker image and executable shell archive settings
// ClasspathJarPlugin is for long-classpath on windows
enablePlugins(JavaServerAppPackaging, UniversalPlugin /*, DockerPlugin*/, ClasspathJarPlugin)

// Java の Alpine Linux で apk が使えないようなので Debian 版に変更
// dockerBaseImage in Docker := "java:8-jdk-alpine"
// dockerBaseImage in Docker := "java:8"

// version in Docker := new java.text.SimpleDateFormat("yyyyMMddHHmm").format(new java.util.Date())
// version in Docker := "latest"
// version in Docker := version.value

// maintainer in Docker := "TAKAMI Torao <koiroha@mail.com>"

// packageName in Docker := "torao/hazmat-webserver"

// dockerExposedPorts in Docker := Seq(8089, 8089)

// dockerUpdateLatest in Docker := true

// dockerRepository in Docker := Some("torao/hazmat-webserver")

// import com.typesafe.sbt.packager.docker._
//dockerCommands ++= Seq(
//  Cmd("RUN", "curl", "-sL", "https://deb.nodesource.com/setup_10.x", "|", "bash", "-"),
//  ExecCmd("RUN", "apt-get", "update"),
//  ExecCmd("RUN", "apt-get", "install", "-y", "nodejs", "build-essential", "python3-pip", "libssl-dev", "libffi-dev", "python-dev"),
//  ExecCmd("RUN", "apt-get", "clean")
//)
