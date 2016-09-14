//
// com.ironcorelabs.davenport.CouchDatastoreSpec
//
// Copyright (c) 2015 IronCore Labs
//
package com.ironcorelabs.davenport
package datastore

import syntax._
import scalaz._, Scalaz._, scalaz.concurrent.Task, scalaz.stream.Process
import db._
import tags.RequiresCouch
import scala.concurrent.duration._
import org.scalatest.BeforeAndAfter

@RequiresCouch
class CouchDatastoreSpec extends DatastoreSpec with BeforeAndAfter with DavenportTestConfiguration {
  def datastoreName: String = "CouchDatastoreBasicTests"
  var connection: CouchConnection = null
  def emptyDatastore: Datastore = connection.openDatastore(BucketNameAndPassword("default", None))

  override def beforeAll() = {
    connection = CouchConnection(davenportConfig)
    ()
  }

  override def afterAll() = {
    connection.disconnect.run
    ()
  }

  /**
   * Before each test, cleanup all the test data we use for the tests. This means
   * every key that is used in the tests needs to be in the cleanup function.
   */
  before {
    cleanup
  }

  def cleanup() = {
    val keys = k :: (tenrows ++ fiveMoreRows).map { case (k, _) => k }
    keys.foreach { k =>
      run(removeKey(k))
    }
  }

  "CouchDatastore" should {
    "work without using datastore by instead using Kleisli" in {
      val createAndGet = for {
        _ <- createDoc(k, v)
        dbValue <- getDoc(k)
      } yield dbValue.data
      val bucket = connection.openBucket(BucketNameAndPassword("default", None))
      val task = bucket.flatMap(CouchDatastore.executeK(createAndGet).run(_))
      task.run.value shouldBe v
    }
  }
}

