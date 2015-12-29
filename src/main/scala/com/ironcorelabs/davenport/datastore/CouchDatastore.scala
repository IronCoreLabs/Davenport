//
// com.ironcorelabs.davenport.CouchDatastore
//
// Copyright (c) 2015 IronCore Labs
//
package com.ironcorelabs.davenport
package datastore

import scalaz.{ \/, Kleisli, ~>, Free }
import scalaz.concurrent.Task
import scalaz.Scalaz._
import db._

// Couchbase
import com.couchbase.client.java.{ Bucket, AsyncBucket }
import com.couchbase.client.java.document.{ AbstractDocument, JsonLongDocument, RawJsonDocument }
import com.couchbase.client.java.error._
import com.couchbase.client.java.query.{ N1qlQuery, N1qlParams }
import com.couchbase.client.java.document.json._ //Need all the Json types
import com.couchbase.client.java.query.consistency.{ ScanConsistency => CScanConsistency }

// RxScala (Observables) used in Couchbase client lib async calls
import rx.lang.scala.Observable
import rx.lang.scala.JavaConversions._
import scalaz.stream.async
import util.observable.{ toSingleItemTask, toListTask }

/**
 * Create a CouchDatastore which operates on the bucket provided. Note that the primary way this should be used is through
 * [[CouchConnection.openDatastore]].
 */
final case class CouchDatastore(bucket: Task[Bucket]) extends Datastore {
  import CouchDatastore._
  /**
   * A function from DBOps[A] => Task[A] which operates on the bucket provided.
   */
  def execute: (DBOps ~> Task) = new (DBOps ~> Task) {
    def apply[A](prog: DBOps[A]): Task[A] = bucket.flatMap(executeK(prog).run(_))
  }
}

/**
 * Things related to translating DBOp to Kleisli[Task, Bucket, A] and some helpers for translating to Task[A].
 *
 * This object contains couchbase specific things such as CAS, which is modeled as CommitVersion.
 *
 * For details about how this translation is done, look at couchRunner which routes each DBOp to its couchbase
 * counterpart.
 */
final object CouchDatastore extends com.typesafe.scalalogging.StrictLogging {
  type CouchK[A] = Kleisli[Task, Bucket, A]

  final val MetaString = "meta"
  final val RecordString = "record"
  //These are constants that match couchbase fields
  final val IdString = "id"
  final val CasString = "cas"
  final val TypeString = "type"

  /**
   * Interpret the program into a Kleisli that will take a Bucket as its argument. Useful if you want to do
   * Kleisli arrow composition before running it.
   */
  def executeK[A](prog: DBProg[A]): CouchK[DBError \/ A] = executeK(prog.run)

  /**
   * Basic building block. Turns the DbOps into a Kleisli which takes a Bucket, used by execute above.
   */
  def executeK[A](prog: DBOps[A]): CouchK[A] = Free.runFC[DBOp, CouchK, A](prog)(couchRunner)

  /**
   * In this case, the couchRunner object transforms [[DB.DBOp]] to
   * `scalaz.concurrent.Task`.
   * The only public method, apply, is what gets called as the grammar
   * is executed, calling it to transform [[DB.DBOps]] to functions.
   */
  private val couchRunner = new (DBOp ~> CouchK) {
    def apply[A](dbp: DBOp[A]): Kleisli[Task, Bucket, A] = dbp match {
      case GetDoc(k: Key) => getDoc(k)
      case CreateDoc(k: Key, v: RawJsonString) => createDoc(k, v)
      case GetCounter(k: Key) => getCounter(k)
      case IncrementCounter(k: Key, delta: Long) => incrementCounter(k, delta)
      case RemoveKey(k: Key) => removeKey(k)
      case UpdateDoc(k: Key, v: RawJsonString, cv: CommitVersion) => updateDoc(k, v, cv)
      case ScanKeys(op: Comparison, value: String, limit: Int, offset: Int, consistency: ScanConsistency) =>
        scanKeys(op, value, limit, offset, consistency)
    }

    private def processQuery(queryCreator: String => N1qlQuery): CouchK[DBError \/ List[DBValue]] = Kleisli.kleisli { bucket: Bucket =>
      toSingleItemTask(bucket.async.query(queryCreator(bucket.name))).flatMap {
        case None => Task.now(GeneralError(new Exception("missing result?")).left)
        case Some(result) =>
          //TODO zip errors 
          toListTask(result.rows).map(_.flatMap { row =>
            logger.debug("Recieved row" + row.value.toString)
            val maybeMetadata = Option(row.value.getObject(MetaString)).flatMap(extractFromMeta(_))
            val (keyString, cas, typ) = maybeMetadata.getOrElse(throw new Exception(s"${row.value.toString} was screwed up in a way we couldn't understand at all."))
            val maybeStringifiedValue = getJsonString(row.value.get(RecordString), typ)
            maybeStringifiedValue.map(result => DBDocument[RawJsonString](Key(keyString), CommitVersion(cas), RawJsonString(result)))
          }.right)
      }
    }

    /**
     * Extract the key, cas and type of record from the meta Json
     */
    private def extractFromMeta(meta: JsonObject): Option[(String, Long, String)] =
      for {
        key <- Option(meta.getString(IdString))
        cas <- Option(meta.getLong(CasString))
        typ <- Option(meta.getString(TypeString))
      } yield (key, cas, typ)

    private def getJsonString[A](couchbaseObject:A, fieldType: String): Option[String] = fieldType match {
      case "json" =>
        couchbaseObject match {
          //We have to surround the string with " to make it valid Json again.
          case v: String => ("\"" + v + "\"").some
          case v: Integer => v.toString.some
          case v: Long => v.toString.some
          case v: Double => v.toString.some
          case v: Boolean => v.toString.some
          case v: JsonObject => v.toString.some
          case v: JsonArray => v.toString.some
          case x => //This is a dev exception. If this happens you need to handle the case.
            throw new Exception(s"All Json types should be covered, but found '$x'")
        }
      case typ =>
        logger.warn(s"We only know how to decode json, but we were asked to decode $fieldType. Threw '$couchbaseObject' out.")
        None
    }

    private def scanField(field: String, op: Comparison, value: String, limit: Int, offset: Int, consistency: ScanConsistency) = {
      val queryCreator = createStatement(_: String, field, op, value, limit, offset, consistency)
      processQuery(queryCreator)
    }

    private def scanKeys(op: Comparison, value: String, limit: Int, offset: Int, consistency: ScanConsistency) =
      scanField(s"meta($RecordString).id", op, value, limit, offset, consistency)

    private def opToFunc(op: Comparison)(name: String): String = {
      val string = op match {
        case EQ => "="
        case GT => ">"
        case LT => "<"
        case LTE => "<="
        case GTE => ">="
      }
      name + string + "$1"
    }

    private def createStatement(bucketName: String, field: String, op: Comparison, value: String, limit: Int, offset: Int, consistency: ScanConsistency): N1qlQuery = {
      import scala.collection.JavaConverters._
      val queryString = s"SELECT $RecordString, meta($RecordString) as $MetaString FROM $bucketName $RecordString where ${opToFunc(op)(field)} order by $MetaString.id limit $limit offset $offset ;"
      logger.debug(s"Query created: '$queryString' with $value")
      N1qlQuery.parameterized(queryString, JsonArray.from(List(value).asJava), createN1qlParams(consistency))
    }

    private def createN1qlParams(c: ScanConsistency): N1qlParams = {
      val params = N1qlParams.build
      c match {
        case AllowStale => params.consistency(CScanConsistency.NOT_BOUNDED)
        case EnsureConsistency => params.consistency(CScanConsistency.REQUEST_PLUS)
      }
    }

    /*
     * Helpers for the datastore
     */
    private def getDoc(k: Key): CouchK[DBError \/ DBValue] =
      couchOpToDBValue(k)(_.get(k.value, classOf[RawJsonDocument]))

    private def createDoc(k: Key, v: RawJsonString): CouchK[DBError \/ DBValue] =
      couchOpToDBValue(k)(_.insert(
        RawJsonDocument.create(k.value, 0, v.value, 0)
      ))

    private def getCounter(k: Key): CouchK[DBError \/ Long] =
      couchOpToLong(k)(_.counter(k.value, 0, 0, 0))

    private def incrementCounter(k: Key, delta: Long): CouchK[DBError \/ Long] =
      couchOpToLong(k)(
        // here we use delta as the default, so if you want an increment
        // by one on a key that doesn't exist, we'll give you a 1 back
        // and if you want an increment by 10 on a key that doesn't exist,
        // we'll give you a 10 back
        _.counter(k.value, delta, delta, 0)
      )

    private def removeKey(k: Key): CouchK[DBError \/ Unit] =
      couchOpToA[Unit, String](
        _.remove(k.value, classOf[RawJsonDocument])
      )(_ => ()).map(_.leftMap(throwableToDBError(k, _)))

    private def updateDoc(k: Key, v: RawJsonString, cv: CommitVersion): CouchK[DBError \/ DBValue] = {
      val updateResult = couchOpToA(_.replace(
        RawJsonDocument.create(k.value, 0, v.value, cv.value)
      ))(doc => DBDocument(k, CommitVersion(doc.cas), RawJsonString(doc.content)))

      updateResult.map(_.leftMap(throwableToDBError(k, _)))
    }

    private def couchOpToLong(k: Key)(fetchOp: AsyncBucket => Observable[JsonLongDocument]): CouchK[DBError \/ Long] = {
      couchOpToA(fetchOp)(doc => Long2long(doc.content)).map(_.leftMap(throwableToDBError(k, _)))
    }

    private def couchOpToDBValue(k: Key)(
      fetchOp: AsyncBucket => Observable[RawJsonDocument]
    ): CouchK[DBError \/ DBValue] = {
      couchOpToA(fetchOp) { doc => DBDocument(k, CommitVersion(doc.cas), RawJsonString(doc.content)) }
        .map(_.leftMap(throwableToDBError(k, _)))
    }

    private def couchOpToA[A, B](fetchOp: AsyncBucket => Observable[AbstractDocument[B]])(f: AbstractDocument[B] => A): CouchK[Throwable \/ A] = { // scalastyle:ignore
      Kleisli.kleisli { bucket: Bucket =>
        obs2Task(fetchOp(bucket.async)).map(_.map(f))
      }
    }

    private def throwableToDBError(key: Key, t: Throwable): DBError = t match {
      case _: DocumentDoesNotExistException => ValueNotFound(key)
      case _: DocumentAlreadyExistsException => ValueExists(key)
      case _: CASMismatchException => CommitVersionMismatch(key)
      case t => GeneralError(t)
    }

    private def obs2Task[A](o: Observable[A]): Task[Throwable \/ A] = {
      val headOptionTask = util.observable.toSingleItemTask(o)
      //Map the Some to a value. None indicates that the observable was "completed" with no value, this 
      //is translated to a DocumentNotFoundException. 
      //We want to `attempt` to gather any error that might have happened in the task
      //and then we need to flatten the nested disjunctions.
      headOptionTask.map(_.toRightDisjunction(new DocumentDoesNotExistException())).attempt.map(_.join)
    }
  }
}
