//
// Copyright (c) 2015 IronCore Labs
//
package com.ironcorelabs.davenport

import java.io.File

import pureconfig._
import scalaz.NonEmptyList

/**
 * Mix this in if you need configuration for the test.
 */
trait DavenportTestConfiguration {
  private final def configFilePath = new java.io.File("src/test/resources/davenport-test.cfg").toPath
  //Unsafe string => NEL conversion for the StringConvert below.
  private def stringToNel(s: String): NonEmptyList[String] =
    s.split(",").toList match {
      case Nil => throw new Exception("Need 1 entry")
      case hd :: tail => NonEmptyList.nel(hd, tail)
    }
  private implicit val nelConfig = StringConvert.fromUnsafe[NonEmptyList[String]](stringToNel(_), nel => nel.list.mkString(","))
  lazy val davenportConfig: DavenportConfig = loadConfig[DavenportConfig](configFilePath).getOrElse(throw new Exception("No config found."))
}
