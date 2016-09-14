//
// Copyright (c) 2015 IronCore Labs
//
package com.ironcorelabs.davenport
package datastore

import syntax._
import scalaz._, Scalaz._, scalaz.concurrent.Task
import db._
import scalaz.stream.Process

/**
 * All datastore tests should inherit from this test. In order to do so the derived classes must provide a way to get an
 * "emptyDatastore". For datastores that have external state, see CouchDatastoreSpec for an example of a way to clear
 * the state between tests.
 */
abstract class DatastoreSpec extends TestBase {
  def datastoreName: String
  def emptyDatastore: Datastore

  //Variables used in many tests
  val k = Key("test")
  val v = RawJsonString("value")
  val newvalue = RawJsonString("some other value")
  val tenrows = (1 to 10).map { i =>
    (Key("key" + i) -> RawJsonString("val" + i))
  }.toList
  val fiveMoreRows = (20 until 25).map { i =>
    (Key("key" + i) -> RawJsonString("val" + i))
  }.toList

  def getDocString(k: Key): DBProg[RawJsonString] = getDoc(k).map(_.data)

  def createAndGet(k: Key, v: RawJsonString): DBProg[RawJsonString] = for {
    _ <- createDoc(k, v)
    newV <- getDocString(k)
  } yield newV

  //Couple helper functions
  def run[A](prog: DBProg[A]): DBError \/ A = emptyDatastore.execute(prog).run

  def runProcess[A](process: Process[DBOps, A]): Throwable \/ IndexedSeq[A] =
    process.execute(emptyDatastore).runLog.attemptRun

  def createTask[A](prog: DBProg[A]): Task[DBError \/ A] = prog.execute(emptyDatastore)

  datastoreName should {
    //
    // Test basic create/get/update operations
    //
    "get a doc that exists" in {
      val testRead = createAndGet(k, v)
      val res = run(testRead)
      res.value shouldBe v
    }
    "fail fetching a doc that doesn't exist" in {
      val testRead = getDocString(k)
      val res = run(testRead)
      res shouldBe left
    }
    "create a doc that doesn't exist" in {
      val result = run(createAndGet(k, v)).value
      result shouldBe v
    }
    "fail to create a doc if it already exists" in {
      val testCreate = createDoc(k, newvalue)
      val datastore = emptyDatastore
      val createTask = testCreate.execute(datastore)
      //This is split into 2 to ensure the first is successful.
      createTask.run.value
      createTask.run shouldBe left
    }
    "update a doc that exists with correct commitVersion" in {
      val testUpdate = for {
        dbv <- getDoc(k)
        newDbv <- updateDoc(k, newvalue, dbv.commitVersion)
      } yield (dbv, newDbv)
      val (oldDoc, newDoc) = run(createDoc(k, v) *> testUpdate).value
      oldDoc.commitVersion should not be (newDoc.commitVersion)
      oldDoc.data should not be (newDoc.commitVersion)
      oldDoc.key shouldBe newDoc.key
    }
    "fail to update a doc that doesn't exist" in {
      val testUpdate = for {
        newDbv <- updateDoc(k, newvalue, CommitVersion(0))
      } yield newDbv.data
      val res = run(testUpdate).leftValue
      res shouldBe ValueNotFound(k)
    }
    "fail updating a doc when using incorrect commitVersion" in {
      val testUpdate = updateDoc(k, newvalue, CommitVersion(100L))
      val res = run(createDoc(k, v) *> testUpdate).leftValue
      res shouldBe CommitVersionMismatch(k)
      res.message shouldBe s"The CommitVersion for '$k' was incorrect."
    }
    "remove a key that exists" in {
      val testRemoveAndGet = for {
        _ <- removeKey(k)
        v <- getDoc(k)
      } yield v
      val ex = run(createDoc(k, v) *> testRemoveAndGet).leftValue
      ex.message should include("No value found for key")
    }
    "fail removing a key that doesn't exist" in {
      val testRemove = removeKey(k)
      val res = run(testRemove)
      res shouldBe left
    }
    "modify map" in {
      val testModify = modifyDoc(k, j => newvalue).map(_.data)
      val res = run(createDoc(k, v) *> testModify).value
      res shouldBe newvalue
    }
    "modify map fails if key is not in db" in {
      val testModify = modifyDoc(k, j => newvalue)
      val res = run(testModify)
      res shouldBe left
    }

    //
    // Test counters
    //
    "get zero when retrieving non-existent counter" in {
      val res = run(getCounter(k))
      res.value shouldBe 0L
    }
    "get 1 when incrementing non-existant counter with default delta" in {
      val res = run(incrementCounter(k))
      res.value shouldBe 1L
    }
    "get 10 when incrementing existing counter (at 1) by 9" in {
      val res = run(for {
        _ <- incrementCounter(k)
        c <- incrementCounter(k, 9)
      } yield c)
      res.value shouldBe 10L
    }
    "fail to get counter when counter is actually a string" in {
      val steps = for {
        _ <- createDoc(k, v)
        c <- getCounter(k)
      } yield c
      run(steps) shouldBe left
    }
    "fail to increment counter when counter is actually a string" in {
      val steps = for {
        _ <- createDoc(k, v)
        c <- incrementCounter(k)
      } yield c
      run(steps) shouldBe left
    }

    "lift single create into process" in {
      val res = runProcess(createAndGet(k, v).process).value
      res should have length (1)
      res.head.value shouldBe v
    }

    "show Process[Task] coming together with Process[DBOps]" in {
      val dataStream = Process.eval(Task.now(k -> v))
      val task: Task[Vector[DBError \/ RawJsonString]] = dataStream.flatMap {
        case (key, value) =>
          createDoc(key, value).map(_.data).process.execute(emptyDatastore)
      }.runLog
      val result = task.run
      result should have size (1)
      result.toSeq.head.value shouldBe v
    }

    "show partial success will still change the backing store" in {
      val datastore = emptyDatastore
      val createOne = createAndGet(k, v)
      val k2 = Key("something.")
      val v2 = RawJsonString("thisIsValue")
      val createTwo = createAndGet(k2, v2)
      //Setup the backing store
      createOne.execute(datastore).run.value
      //create one should cause the whole thing to report error
      val error = (createTwo *> createOne).execute(datastore).run.leftValue
      error.message should include("already exists")

      //check to ensure that ever though the last operation failed, it still committed everything before `createOne`.
      val (r1, r2) = (getDocString(k) |@| getDocString(k2))(_ -> _).execute(datastore).run.value
      r1 shouldBe v //This is in there because of the setup
      r2 shouldBe v2 //This should be in there because createTwo succeeded.
    }

    //
    // Test batch import
    //
    "be happy doing initial batch import" in {
      val res = runProcess(batch.createDocs(tenrows)).value
      res.toList.separate._2.length shouldBe tenrows.length
    }
    "return errors batch importing the same items again" in {
      val datastore = emptyDatastore
      val initialInsertResult = batch.createDocs(tenrows).execute(datastore).runLog.run
      //Verified by another test, but sanity check.
      initialInsertResult.forall(_.isRight) shouldBe true
      val res = batch.createDocs(tenrows ++ fiveMoreRows).execute(datastore).runLog.run
      res.length shouldBe tenrows.length + fiveMoreRows.length
      val (lefts, rights) = res.toList.separate
      lefts.length shouldBe tenrows.length
      rights.length shouldBe fiveMoreRows.length
    }
    "fail after on first error if we pass in a halting function" in {
      val datastore = emptyDatastore
      val initialInsert = batch.createDocs(tenrows).execute(datastore).runLog.attemptRun.value
      val res = batch.createDocs(tenrows).takeThrough(_.isRight).execute(datastore).runLog.attemptRun.value
      res.length shouldBe 1
      res.head shouldBe left
    }
    "don't try and insert first 5 and return 5 errors" in {
      val res = runProcess(batch.createDocs(tenrows.drop(5))).value
      res.length shouldBe 5
      res.toList.separate._2.length shouldBe 5
    }
  }
}
