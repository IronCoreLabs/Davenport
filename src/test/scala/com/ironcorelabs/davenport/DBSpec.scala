//
// com.ironcorelabs.davenport.DBSpec
//
// Copyright (c) 2015 IronCore Labs
//
package com.ironcorelabs.davenport

import scalaz._, Scalaz._, scalaz.concurrent.Task
import org.scalatest.{ WordSpec, Matchers, BeforeAndAfterAll }
import org.typelevel.scalatest._
import scala.language.postfixOps
import DB._

class DBSpec extends WordSpec with Matchers with BeforeAndAfterAll with DisjunctionMatchers {
  "DB" should {
    "fail lifting none into dbprog" in {
      MemConnection(liftIntoDBProg(None)) should be(left)
    }
    "fail using DBProgFail" in {
      MemConnection(dbProgFail(new Exception("boom"))) should be(left)
    }
  }
}
