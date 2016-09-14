scalaVersion := "2.11.8"

val ScalazVersion = "7.1.9"

// crossScalaVersions := Seq("2.10.4")

resolvers ++= Seq(
  "Typesafe repository" at "http://repo.typesafe.com/typesafe/release/",
  "Scalaz Bintray Repo" at "http://dl.bintray.com/scalaz/releases",
  "Oncue Bintray Repo" at "http://dl.bintray.com/oncue/releases"
)

// Production
libraryDependencies ++= Seq(
  "org.scalaz" %% "scalaz-core" % ScalazVersion, // for type awesomeness
  "org.scalaz" %% "scalaz-concurrent" % ScalazVersion, // for type awesomeness
  "com.couchbase.client" % "core-io" % "1.2.3",
  "io.reactivex" %% "rxscala" % "0.25.0", // to better work with the couchbase java client
  "io.argonaut" %% "argonaut" % "6.1", // json (de)serialization scalaz style
  "org.scalaz.stream" %% "scalaz-stream" % "0.8.4",
  "org.scodec" %% "scodec-bits" % "1.1.1"
)

// Test
libraryDependencies ++= Seq(
  "org.scalatest" % "scalatest_2.11" % "2.2.1" % "test",
  "org.typelevel" %% "scalaz-scalatest" % "0.2.2" % "test",
  "org.scalacheck" %% "scalacheck" % "1.12.5" % "test",
  "org.scalaz" %% "scalaz-scalacheck-binding" % ScalazVersion,
  "com.github.melrief" %% "pureconfig" % "0.2.2" % "test"
)

addCompilerPlugin("org.spire-math" %% "kind-projector" % "0.6.3")

// Code coverage checks
coverageMinimum := 70
coverageFailOnMinimum := true
coverageHighlighting := scalaBinaryVersion.value == "2.11"

tutSettings
unidocSettings
site.settings
ghpages.settings
Lint.settings
site.includeScaladoc()
releaseVersionBump := sbtrelease.Version.Bump.Bugfix
com.typesafe.sbt.site.JekyllSupport.requiredGems := Map(
  "jekyll" -> "2.4.0",
  "kramdown" -> "1.5.0",
  "jemoji" -> "0.4.0",
  "jekyll-sass-converter" -> "1.2.0",
  "jekyll-mentions" -> "0.2.1"
)
site.jekyllSupport()
// Enable this if you're convinced every publish should update docs
// site.publishSite

tutSourceDirectory := sourceDirectory.value / "tutsrc"
tutTargetDirectory := sourceDirectory.value / "jekyll" / "_tutorials"

git.remoteRepo := "git@github.com:IronCoreLabs/davenport.git"

releasePublishArtifactsAction := PgpKeys.publishSigned.value

// Apply default Scalariform formatting.
// Reformat at every compile.
// c.f. https://github.com/sbt/sbt-scalariform#advanced-configuration for more options.
scalariformSettings

scalacOptions ++= Seq(
  "-deprecation",
  "-encoding", "UTF-8", // yes, this is 2 args
  "-feature",
  "-unchecked",
  "-language:higherKinds",
  "-Xfatal-warnings",
  // "-Xlog-implicits",
  "-Xlint",
  "-Yno-adapted-args",
  "-Ywarn-numeric-widen",
  "-Ywarn-value-discard",
  "-Xfuture"
)
