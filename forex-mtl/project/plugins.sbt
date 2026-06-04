addSbtPlugin("com.lucidchart"    % "sbt-scalafmt-coursier" % "1.16")
addSbtPlugin("com.timushev.sbt" % "sbt-updates" % "0.5.3")
addSbtPlugin("io.spray" % "sbt-revolver" % "0.9.1")
addSbtPlugin("org.scoverage" % "sbt-scoverage" % "2.3.1")
addSbtPlugin("com.github.sbt" % "sbt-native-packager" % "1.9.16")

libraryDependencySchemes += "org.scala-lang.modules" %% "scala-xml" % VersionScheme.Always
