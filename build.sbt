ThisBuild / scalaVersion := "2.13.8"

lazy val root = project
  .in(file("."))
  .settings(
    libraryDependencies ++= Seq(
      "io.d11" %% "zhttp" % "2.0.0-RC10"
    ),
    fork := true
  )
