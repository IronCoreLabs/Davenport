//
// Copyright (c) 2015 IronCore Labs
//
package com.ironcorelabs.davenport

import scalaz._, Scalaz._, scalaz.concurrent.Task
import db._
import tags.RequiresCouch
import syntax.dbprog._
import scala.concurrent.duration._

@RequiresCouch
class CouchConnectionSpec extends TestBase with DavenportTestConfiguration {
  var connection: CouchConnection = null

  override def beforeAll() = {

    connection = CouchConnection(davenportConfig)
    ()
  }

  override def afterAll() = {
    connection.disconnect.attemptRun.valueOr(throw _)
    ()
  }

  "CouchConnection" should {
    "handle a failed connection" in {
      //Disconnect our connection.
      connection.disconnect.attemptRun.value
      // Prove that the connection fails
      val connectionfail = db.getDoc(Key("a")).execute(connection.openDatastore(BucketNameAndPassword("default", None)))
      connectionfail.attemptRun.leftValue shouldBe a[DisconnectedException]
      //Reconnect so the next test has a connection.
      connection = CouchConnection(davenportConfig)
    }
    "be able to open and close bucket" in {
      val b = BucketNameAndPassword("default", None)
      val openedBucket = connection.openBucket(b).attemptRun.value
      openedBucket.name shouldBe b.name
      connection.openBuckets.get(b).value shouldBe openedBucket
      val closeTask = connection.closeBucket(b)
      //Close should succeed
      closeTask.attemptRun should beRight(true)
      //The bucket shouldn't be there anymore
      connection.openBuckets.get(b) shouldBe None
    }
    "return false for closeBucket which isn't open" in {
      val b = BucketNameAndPassword("myuknownbucket", None)
      connection.closeBucket(b).attemptRun.value shouldBe false
    }
  }
}
