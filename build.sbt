name := "http-akka"

version := "1.0"

scalaVersion := "2.11.7"


libraryDependencies ++= Seq(
  "com.typesafe.akka" %% "akka-http-core-experimental" % "1.0-M2",
  "org.reactivemongo" %% "reactivemongo" % "0.10.5.0.akka23",
  "org.reactivemongo" %% "play2-reactivemongo" % "0.10.5.0.akka23",
  "com.typesafe.play" % "play-json_2.11" % "2.4.0-M2",
  "ch.qos.logback" % "logback-classic" % "1.1.2"
)

resolvers += "Sonatype Snapshots" at "https://oss.sonatype.org/content/repositories/snapshots/"

resolvers += "Typesafe" at "https://repo.typesafe.com/typesafe/releases/"

mainClass in (Compile, run) := Some("Boot")