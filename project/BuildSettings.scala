/**
 * Licensed to the Minutemen Group under one or more contributor license
 * agreements. See the COPYRIGHT file distributed with this work for
 * additional information regarding copyright ownership.
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you
 * may not use this file except in compliance with the License. You may
 * obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
import sbt.Keys._
import sbt._

////*******************************
//// Basic settings
////*******************************
object BasicSettings extends AutoPlugin {
  override def trigger: PluginTrigger = allRequirements

  override def projectSettings: Seq[Setting[_]] = Seq(
    organization := "group.minutemen",
    resolvers ++= Dependencies.resolvers,
    scalaVersion := crossScalaVersions.value.head,
    crossScalaVersions := Seq("2.12.0", "2.11.8"),
    scalacOptions ++= Seq(
      "-deprecation", // Emit warning and location for usages of deprecated APIs.
      "-feature", // Emit warning and location for usages of features that should be imported explicitly.
      "-unchecked", // Enable additional warnings where generated code depends on assumptions.
      "-Xfatal-warnings", // Fail the compilation if there are any warnings.
      "-Xlint", // Enable recommended additional warnings.
      "-Ywarn-adapted-args", // Warn if an argument list is modified to match the receiver.
      "-Ywarn-dead-code", // Warn when dead code is identified.
      "-Ywarn-inaccessible", // Warn about inaccessible types in method signatures.
      "-Ywarn-nullary-override", // Warn when non-nullary overrides nullary, e.g. def foo() over def foo.
      "-Ywarn-numeric-widen" // Warn when numerics are widened.
    ),
    scalacOptions in Test ~= { (options: Seq[String]) =>
      options filterNot (_ == "-Ywarn-dead-code") // Allow dead code in tests (to support using mockito).
    },
    parallelExecution in Test := false,
    fork in Test := true,
    // Needed to avoid https://github.com/travis-ci/travis-ci/issues/3775 in forked tests
    // in Travis with `sudo: false`.
    // See https://github.com/sbt/sbt/issues/653
    // and https://github.com/travis-ci/travis-ci/issues/3775
    javaOptions += "-Xmx1G"
  )
}

////*******************************
//// Scalariform settings
////*******************************
object CodeFormatter extends AutoPlugin {

  import com.typesafe.sbt.SbtScalariform._

  import scalariform.formatter.preferences._

  lazy val BuildConfig = config("build") extend Compile
  lazy val BuildSbtConfig = config("buildsbt") extend Compile

  lazy val prefs = Seq(
    ScalariformKeys.preferences := ScalariformKeys.preferences.value
      .setPreference(FormatXml, false)
      .setPreference(DoubleIndentClassDeclaration, false)
      .setPreference(AlignSingleLineCaseStatements, true)
      .setPreference(DanglingCloseParenthesis, Preserve)
  )

  override def trigger: PluginTrigger = allRequirements

  override def projectSettings: Seq[Setting[_]] = defaultScalariformSettings ++ prefs ++
    inConfig(BuildConfig)(configScalariformSettings) ++
    inConfig(BuildSbtConfig)(configScalariformSettings) ++
    Seq(
      scalaSource in BuildConfig := baseDirectory.value / "project",
      scalaSource in BuildSbtConfig := baseDirectory.value / "project",
      includeFilter in (BuildConfig, ScalariformKeys.format) := ("*.scala": FileFilter),
      includeFilter in (BuildSbtConfig, ScalariformKeys.format) := ("*.sbt": FileFilter),
      ScalariformKeys.format in Compile := {
        (ScalariformKeys.format in BuildSbtConfig).value
        (ScalariformKeys.format in BuildConfig).value
        (ScalariformKeys.format in Compile).value
      }
    )
}

////*******************************
//// ScalaDoc settings
////*******************************
object Doc extends AutoPlugin {

  override def projectSettings: Seq[Setting[_]] = Seq(
    autoAPIMappings := true,
    apiURL := Some(url(s"http://api.akka-http.silhouette.rocks/${version.value}/")),
    apiMappings ++= {
      implicit val cp = (fullClasspath in Compile).value
      Map(
        jarFor("com.typesafe.akka", "akka-http-core") ->
          url(s"http://doc.akka.io/api/akka//${Dependencies.Version.akka}/"),
        jarFor("group.minutemen", "silhouette") ->
          url(s"http://api.silhouette.rocks/${Dependencies.Version.silhouette}/"),
        scalaInstance.value.libraryJar -> url(s"http://www.scala-lang.org/api/${scalaVersion.value}/")
      )
    }
  )

  /**
   * Gets the JAR file for a package.
   *
   * @param organization The organization name.
   * @param name The name of the package.
   * @param cp The class path.
   * @return The file which points to the JAR.
   * @see http://stackoverflow.com/a/20919304/2153190
   */
  private def jarFor(organization: String, name: String)(implicit cp: Seq[Attributed[File]]): File = {
    (for {
      entry <- cp
      module <- entry.get(moduleID.key)
      if module.organization == organization
      if module.name.startsWith(name)
      jarFile = entry.data
    } yield jarFile).head
  }
}

////*******************************
//// APIDoc settings
////*******************************
// @see https://github.com/paypal/horizon/blob/develop/src/main/scala/com/paypal/horizon/BuildUtilities.scala
object APIDoc {

  import com.typesafe.sbt.SbtGhPages.GhPagesKeys._
  import com.typesafe.sbt.SbtGhPages.ghpages
  import com.typesafe.sbt.SbtGit.GitKeys._
  import com.typesafe.sbt.SbtGit.git
  import com.typesafe.sbt.SbtSite.SiteKeys._
  import com.typesafe.sbt.SbtSite.site
  import sbtunidoc.Plugin._

  lazy val files = Seq(file("CNAME"))

  lazy val settings = unidocSettings ++
    site.settings ++
    ghpages.settings ++
    Seq(
      // Create version
      siteMappings <++= (mappings in (ScalaUnidoc, packageDoc), version).map { (mapping, ver) =>
        for ((file, path) <- mapping) yield (file, s"$ver/$path")
      },
      // Add custom files from site directory
      siteMappings <++= baseDirectory.map { dir =>
        for (file <- files) yield (new File(dir.getAbsolutePath + "/site/" + file), file.name)
      },
      // Do not delete old versions
      synchLocal <<= (privateMappings, updatedRepository, gitRunner, streams).map { (mappings, repo, git, s) =>
        val betterMappings = mappings.map { case (file, tgt) => (file, repo / tgt) }
        IO.copy(betterMappings)
        repo
      },
      git.remoteRepo := "git@github.com:minutemen/silhouette-akka-http.git"
    )
}

////*******************************
//// Maven settings
////*******************************
object Publish extends AutoPlugin {

  import xerial.sbt.Sonatype._

  override def trigger: PluginTrigger = allRequirements

  private val pom = {
    <scm>
      <url>git@github.com:minutemen/silhouette-akka-http.git</url>
      <connection>scm:git:git@github.com:minutemen/silhouette-akka-http.git</connection>
    </scm>
      <developers>
        <developer>
          <id>akkie</id>
          <name>Christian Kaps</name>
          <url>http://mohiva.com</url>
        </developer>
        <developer>
          <id>datalek</id>
          <name>Alessandro Ferlin</name>
          <url>https://github.com/datalek</url>
        </developer>
      </developers>
  }

  override def projectSettings: Seq[Setting[_]] = sonatypeSettings ++ Seq(
    description := "Akka HTTP bindings for the Silhouette authentication library",
    homepage := Some(url("http://www.silhouette.rocks/")),
    licenses := Seq("Apache License" -> url("https://github.com/minutemen/silhouette-akka-http/blob/master/LICENSE")),
    publishMavenStyle := true,
    publishArtifact in Test := false,
    pomIncludeRepository := { _ => false },
    pomExtra := pom,
    credentials ++= (for {
      username <- Option(System.getenv().get("SONATYPE_USERNAME"))
      password <- Option(System.getenv().get("SONATYPE_PASSWORD"))
    } yield Credentials("Sonatype Nexus Repository Manager", "oss.sonatype.org", username, password)).toSeq
  )
}

////*******************************
//// Release settings
////*******************************
object Release extends AutoPlugin {

  import sbtrelease.ReleasePlugin.autoImport._
  import ReleaseTransformations._

  override def trigger: PluginTrigger = allRequirements

  override def projectSettings: Seq[Setting[_]] = Seq(
    releaseProcess := Seq[ReleaseStep](
      checkSnapshotDependencies,
      inquireVersions,
      runClean,
      runTest,
      setReleaseVersion,
      commitReleaseVersion,
      tagRelease,
      ReleaseStep(
        action = Command.process("publishSigned", _),
        enableCrossBuild = crossScalaVersions.value.length > 1
      ),
      setNextVersion,
      commitNextVersion,
      ReleaseStep(
        action = Command.process("sonatypeReleaseAll", _),
        enableCrossBuild = crossScalaVersions.value.length > 1
      ),
      pushChanges
    )
  )
}
