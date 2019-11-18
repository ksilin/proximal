lazy val root = (project in file(".")).
  settings(
    inThisBuild(List(
      organization := "com.example",
      scalaVersion := "2.13.0"
    )),
    name := "proximal"
  )
enablePlugins(DockerComposePlugin)
enablePlugins(DockerPlugin)

libraryDependencies += "ch.qos.logback" % "logback-classic" % "1.2.3"
libraryDependencies += "com.typesafe.scala-logging" %% "scala-logging" % "3.9.2"

libraryDependencies += "com.softwaremill.sttp.client" %% "core" % "2.0.0-RC1"
libraryDependencies += "com.softwaremill.sttp.client" %% "async-http-client-backend-monix" % "2.0.0-RC1"
libraryDependencies += "com.softwaremill.sttp.client" %% "circe" % "2.0.0-RC1"
libraryDependencies += "io.circe" %% "circe-generic" % "0.12.1"

libraryDependencies += "org.apache.kafka" % "kafka-clients" % "2.3.1"

libraryDependencies += "com.github.pathikrit" %% "better-files" % "3.8.0"
libraryDependencies += "commons-io" % "commons-io" % "2.6"

libraryDependencies += "org.scalatest" %% "scalatest" % "3.0.8" % Test
