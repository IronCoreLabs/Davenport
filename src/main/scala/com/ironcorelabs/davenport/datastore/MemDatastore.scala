//
// Copyright (c) 2015 IronCore Labs
//
package com.ironcorelabs.davenport
package datastore

import scalaz.concurrent.Task
import scalaz.stream.Process
import scalaz._
import scalaz.syntax.either._
import scalaz.syntax.order._
import db._

abstract class MemDatastore extends Datastore {
  import MemDatastore._
  protected var map: KVMap

  def execute: (DBOps ~> Task) = new (DBOps ~> Task) {
    def apply[A](prog: DBOps[A]): Task[A] = {
      //Note that the Task.delay captures the current state when this op is run, which is important
      //if you rerun a Task.
      Task.delay(map).flatMap(executeKVState(prog)(_)).map {
        case (newM, value) =>
          //In order to provide the same semantics as Couch, once a value has been "computed" it will be 
          //committed to the DB. Note that this might overwrite someone elses changes in a multi-thread environment.
          map = newM
          value
      }
    }
  }
}

object MemDatastore {
  /** Backend of the memory store is a Map from Key -> RawJsonString */
  type KVMap = Map[Key, RawJsonString]
  type KVState[A] = StateT[Task, KVMap, A]

  def apply(m: KVMap): MemDatastore = new MemDatastore {
    protected var map = m
  }

  def empty: MemDatastore = apply(Map.empty)

  val executeKVState: DBOps ~> KVState = new (DBOps ~> KVState) {
    def apply[A](db: DBOps[A]): KVState[A] = {
      Free.runFC[DBOp, KVState, A](db)(toKVState)
    }
  }

  /** Arbitrary implementation of the commitVersion for records in the DB */
  private[davenport] def genCommitVersion(s: RawJsonString): CommitVersion =
    CommitVersion(scala.util.hashing.MurmurHash3.stringHash(s.value).toLong)

  private def modifyState(s: KVMap): (KVMap, DBError \/ Unit) = s -> ().right
  private def modifyStateDbv(s: KVMap, dbv: DBValue): (KVMap, DBError \/ DBValue) = s -> dbv.right
  private def notFoundError[A](key: Key): DBError \/ A = ValueNotFound(key).left
  //Convienience method to lift f into KVState.
  private def state[A](f: KVMap => (KVMap, A)): KVState[A] = StateT[Task, KVMap, A] { map =>
    Task.delay(f(map))
  }

  private def getDoc(k: Key): KVState[DBError \/ DBValue] = {
    state { m: KVMap =>
      m.get(k).map(json => m -> DBDocument(k, genCommitVersion(json), json).right)
        .getOrElse(m -> notFoundError[DBValue](k))
    }
  }

  private def updateDoc(k: Key, doc: RawJsonString, commitVersion: CommitVersion): KVState[DBError \/ DBValue] = {
    state { m: KVMap =>
      m.get(k).map { json =>
        val storedCommitVersion = genCommitVersion(json)
        if (commitVersion == storedCommitVersion) {
          modifyStateDbv(m + (k -> doc), DBDocument(k, genCommitVersion(doc), doc))
        } else {
          m -> (CommitVersionMismatch(k).left)
        }
      }.getOrElse(m -> notFoundError(k))
    }
  }

  private def getCounter(k: Key): KVState[DBError \/ Long] = {
    state { m: KVMap =>
      m.get(k).map { json =>
        m -> \/.fromTryCatchNonFatal(json.value.toLong).leftMap(GeneralError(_))
      } getOrElse {
        (m + (k -> RawJsonString("0")) -> 0L.right)
      }
    }
  }

  private def incrementCounter(k: Key, delta: Long): KVState[DBError \/ Long] = {
    state { m: KVMap =>
      m.get(k).map { json =>
        // convert to long and increment by delta
        val newval = \/.fromTryCatchNonFatal(json.value.toLong + delta).leftMap(GeneralError(_))
        val newMap = newval.fold(_ => m, v => m + (k -> RawJsonString(v.toString)))
        newMap -> newval
      }.getOrElse {
        // save delta to db
        (m + (k -> RawJsonString(delta.toString)), delta.right)
      }
    }
  }

  private def findRecords(comparison: Comparison, value: String): KVMap => List[DBValue] = { map: KVMap =>
    val filterFunc = comparisonToFilter(comparison, value)
    map.toList.collect { case (key, value) if (filterFunc(key)) => DBDocument(key, genCommitVersion(value), value) }
  }

  private def comparisonToFilter(comparison: Comparison, value: String): Key => Boolean = {
    val comparisonKey = Key(value)
    comparison match {
      case GTE => _ >= comparisonKey
      case LTE => _ <= comparisonKey
      case EQ => _ == comparisonKey
      case LT => _ < comparisonKey
      case GT => _ > comparisonKey
    }
  }

  private def toKVState: DBOp ~> KVState = new (DBOp ~> KVState) {
    def apply[A](op: DBOp[A]): KVState[A] = {
      op match {
        case GetDoc(k: Key) => getDoc(k)
        case UpdateDoc(k, doc, commitVersion) => updateDoc(k, doc, commitVersion)
        case CreateDoc(k, doc) => state { m: KVMap =>
          m.get(k).map(_ => m -> ValueExists(k).left).
            getOrElse(modifyStateDbv(m + (k -> doc), DBDocument(k, genCommitVersion(doc), doc)))
        }
        case RemoveKey(k) => state { m: KVMap =>
          val keyOrError = m.get(k).map(_ => k.right).getOrElse(ValueNotFound(k).left)
          keyOrError.fold(t => m -> t.left, key => modifyState(m - k))
        }
        case GetCounter(k) => getCounter(k)
        case IncrementCounter(k, delta) => incrementCounter(k, delta)
        case ScanKeys(comparison, value) => state { map =>
          map -> scalaz.stream.Process.emitAll(findRecords(comparison, value)(map)).right
        }
      }
    }
  }
}
