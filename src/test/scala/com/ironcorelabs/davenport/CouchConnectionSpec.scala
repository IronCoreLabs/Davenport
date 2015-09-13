//
// com.ironcorelabs.davenport.CouchConnectionSpec
//
// Copyright (c) 2015 IronCore Labs
//
package com.ironcorelabs.davenport

import scalaz._, Scalaz._, scalaz.concurrent.Task
import org.scalatest.{ WordSpec, Matchers, BeforeAndAfterAll, OptionValues, Tag }
import org.scalatest.concurrent.AsyncAssertions
import org.typelevel.scalatest._
import DisjunctionValues._
import scala.language.postfixOps
import DB._
import DB.Batch._
import com.ironcorelabs.davenport.tags.RequiresCouch
import scala.concurrent.duration._

@RequiresCouch
class CouchConnectionSpec extends WordSpec with Matchers with BeforeAndAfterAll with DisjunctionMatchers with OptionValues with AsyncAssertions {
  val k = Key("test")
  val k404 = Key("test404")
  val v = RawJsonString("value")
  val newvalue = RawJsonString("some other value")
  val tenrows = (1 to 10).map { i =>
    (Key("key" + i) -> RawJsonString("val" + i))
  }.toList

  override def beforeAll() = {
    // Connect and make sure test key is not in db in case of
    // bad cleanup on last run
    println(CouchConnection.connect)
    cleanup
    ()
  }

  override def afterAll() = {
    cleanup
    CouchConnection.disconnect;
    ()
  }

  def cleanup() = {
    CouchConnection(removeKey(k))
    tenrows.foreach(kv => CouchConnection(removeKey(kv._1)))
  }

  "CouchConnection" should {
    import java.util._
    import com.couchbase.client.java.error._
    "fail fetching a doc that doesn't exist" in {
      val res = CouchConnection(getDoc(k))
      res.leftValue.getClass should ===(classOf[DocumentDoesNotExistException])
    }
    "create a doc that doesn't exist" in {
      val res = CouchConnection(createDoc(k, v))
      res.value.jsonString should ===(v)
    }
    "get a doc that should now exist" in {
      val res = CouchConnection(getDoc(k))
      res.value.jsonString should ===(v)
    }
    "fail to create a doc if it already exists" in {
      val testCreate = createDoc(k, newvalue)
      val res = CouchConnection(testCreate)
      res.leftValue.getClass should ===(classOf[DocumentAlreadyExistsException])
    }
    "fail to get counter when counter is actually a string" in {
      import syntax.dbprog._ // bring in syntax for dbprog
      val steps = for {
        _ <- createDoc(k, v)
        c <- getCounter(k)
      } yield c
      steps.execTask(CouchConnection).attemptRun.value should be(left)
    }
    "fail to increment counter when counter is actually a string" in {
      val steps = for {
        _ <- createDoc(k, v)
        c <- incrementCounter(k)
      } yield c
      CouchConnection.execTask(steps).attemptRun.value should be(left)
    }
    "update a doc that exists with correct hashver" in {
      val testUpdate = for {
        t <- getDoc(k)
        res <- updateDoc(k, newvalue, t.hashVer)
      } yield res
      val res = CouchConnection(testUpdate)
      res should be(right)
      val res2 = CouchConnection(getDoc(k))
      res2.value.jsonString should ===(newvalue)
    }
    "fail updating a doc that doesn't exist" in {
      val testUpdate = updateDoc(k404, newvalue, HashVer(1234))
      val res = CouchConnection(testUpdate)
      res.leftValue.getClass should ===(classOf[DocumentDoesNotExistException])
    }
    "fail updating a doc when using incorrect hashver" in {
      val testUpdate = updateDoc(k, v, HashVer(1234))
      val res = CouchConnection(testUpdate)
      res.leftValue.getClass should ===(classOf[CASMismatchException])
    }
    "remove a key that exists" in {
      val testRemove = removeKey(k)
      val res = CouchConnection(removeKey(k))
      res should be(right)
    }
    "fail removing a key that doesn't exist" in {
      val testRemove = removeKey(k)
      val res = CouchConnection(removeKey(k))
      res.leftValue.getClass should ===(classOf[DocumentDoesNotExistException])
    }
    "modify map fails if key is not in db" in {
      val testModify = modifyDoc(k, j => newvalue)
      val res = CouchConnection(testModify)
      res should be(left)
    }
    "modify map after create" in {
      val testModify = for {
        _ <- createDoc(k, v)
        res <- modifyDoc(k, j => newvalue)
      } yield res
      val res = CouchConnection(testModify)
      res.value.jsonString should ===(newvalue)
    }
    "get zero when retrieving non-existent counter" in {
      val res = CouchConnection(for {
        _ <- removeKey(k) // make sure it is gone
        c <- getCounter(k)
        _ <- removeKey(k) // clean up
      } yield c)
      res.value should ===(0L)
    }
    "get 1 when incrementing non-existant counter with default delta" in {
      val res = CouchConnection(incrementCounter(k))
      res.value should ===(1L)
    }
    "get 10 when incrementing existing counter (at 1) by 9" in {
      val res = CouchConnection(incrementCounter(k, 9))
      res.value should ===(10L)
    }
    "still get 10 when fetching existing counter" in {
      val res = CouchConnection(getCounter(k))
      res.value should ===(10L)
    }
    "be happy doing initial batch import" in {
      val res: IndexedSeq[Throwable \/ DbValue] = CouchConnection.translateProcess(createDocs(tenrows)).runLog.attemptRun.value
      val (lefts, rights) = res.toList.separate
      lefts.length should ===(0)
      rights.length should ===(tenrows.length)

    }
    "error on single create after batch" in {
      val res = CouchConnection.execTask(tenrows.map { case (key, value) => createDoc(key, value) }.head).attemptRun.value
      res should be(left)
    }

    "return errors batch importing the same items again" in {
      val res = CouchConnection.translateProcess(createDocs(tenrows)).runLog.attemptRun.value
      val (lefts, rights) = res.toList.separate
      rights.length should ===(0)
      lefts.length should ===(tenrows.length)

      lefts.foldMap {
        case _: DocumentAlreadyExistsException => 1
        case _ => 0
      } should ===(tenrows.length)
    }
    "fail after first error if we pass in a halting function" in {
      val res = CouchConnection.translateProcess(createDocs(tenrows).takeWhile(_.isRight)).runLog.attemptRun.value
      val (lefts, rights) = res.toList.separate
      lefts.length should ===(0) //Check with Patrick if these semantics are OK.
      rights.length should ===(0)
    }

    "attempt to recover from a bad CAS error by refetching and retrying" in {
      // Setup
      CouchConnection(for {
        _ <- removeKey(k)
        _ <- createDoc(k, v)
      } yield ())

      // Update
      def upd(cas: Long) = updateDoc(k, newvalue, HashVer(cas))

      // Generate a task that will fail with a bad CAS and prove it
      val res: Task[Throwable \/ DbValue] = CouchConnection.execTask(upd(123))
      res.attemptRun.join should be(left)

      // Generate a task that handles CAS errors and run and verify
      val handled: Task[Throwable \/ DbValue] = res.map(_.handleError {
        case e: CASMismatchException =>
          println(s"Dealing with $e")
          CouchConnection.execTask(for {
            v <- getDoc(k)
            u <- upd(v.hashVer.value)
          } yield u).attemptRun.join
        case x => x.left

      })

      val finalres = handled.attemptRun.join.value
      finalres.jsonString should ===(newvalue)
    }

    "handle a failed connection" in {
      // Save off bucket and then ditch it
      CouchConnection.fakeDisconnect

      // Prove that the connection fails
      val connectionfail = CouchConnection.execTask(getDoc(k))
      connectionfail.attemptRun.join.leftValue.getMessage should ===("Not connected")

      CouchConnection.fakeDisconnectRevert
    }
    /*
     * TODO: No idea why this doesn't work. Punting until later.
     */
    "simulate a connection failure and recover from it using Task retry" ignore {
      // handle async fun
      val w = new Waiter

      // Save off bucket and then hide it
      CouchConnection.fakeDisconnect

      val connectionfail = CouchConnection.execTask(getDoc(k))

      // Setup a retry.  Within the retry, resolve the problem
      val retry = connectionfail.retryAccumulating(Seq(155.millis, 1025.millis), { t =>
        println("***** Retrying")
        CouchConnection.fakeDisconnectRevert
        t.getMessage == "Not connected" // return true to retry if not connected
      })
      val res = retry.attemptRun
      println(res)
      res should be(right)
    }
    "attempt connect to bad host and fail" in {
      // Store off good connection
      CouchConnection.fakeDisconnect

      // Attempt to connect with bogus config
      val res = CouchConnection.connectToHost("badhostnocookie.local")

      // Get error in return
      res should be(left)

      CouchConnection.fakeDisconnectRevert
    }
  }
}
