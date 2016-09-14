//
// Copyright (c) 2015 IronCore Labs
//
package com.ironcorelabs.davenport
package internal

import com.couchbase.client.deps.io.netty.buffer.{ ByteBuf, Unpooled }
import scalaz.concurrent.Task
import scalaz.\/
import com.couchbase.client.core.message.kv._
import com.couchbase.client.core.{ CouchbaseException, CouchbaseCore }
import com.couchbase.client.core.message.ResponseStatus
import rx.lang.scala.Observable
import rx.lang.scala.JavaConversions._
import db._
import error._
import scodec.bits.ByteVector
import scala.concurrent.duration.Duration
import codec.{ ByteVectorDecoder, ByteVectorEncoder }

/**
 * A reference to a "Bucket" in couchbase. This bucket will be communicating with core.
 * Note that the core must be initialized and this should really only be created via CouchConnection.
 */
final class Bucket(core: CouchbaseCore, val name: String, password: Option[String]) {

  /**
   * Get a value associated with key and attempt to decode it into the type A.
   */
  final def get[A](key: Key)(implicit decoder: ByteVectorDecoder[A]): Task[DBDocument[A]] =
    decodeDBDocument(Bucket.get(core, name, key.value))

  /**
   * Create a document at key with a value of A, which will be encoded into bytes using encoder.
   */
  final def create[A: ByteVectorEncoder](key: Key, value: A): Task[DBDocument[A]] =
    Bucket.create(core, name, key.value, encodeToByteVector(value), None).map(_.map(_ => value))

  /**
   * Remove the value at key.
   */
  final def remove(key: Key): Task[DBDocument[Unit]] = Bucket.remove(core, name, key.value, 0)

  /**
   * Get the counter at key.
   */
  final def getCounter(key: Key): Task[DBDocument[Long]] = Bucket.counter(core, name, key.value, 0, 0, None)

  /**
   * Increment the counter at key by delta.
   */
  final def incrementCounter(key: Key, delta: Long): Task[DBDocument[Long]] = Bucket.counter(core, name, key.value, delta, delta, None)

  /**
   * Update (or replace) the document that is stored at key with the encoded value. Note that this will only succeed if the cas matches
   * the one stored in couchbase.
   */
  final def update[A: ByteVectorEncoder](key: Key, value: A, cas: Long): Task[DBDocument[A]] =
    Bucket.replace(core, name, key.value, encodeToByteVector(value), cas, None).map(_.map(_ => value))

  private final def decodeDBDocument[A](t: Task[DBDocument[ByteVector]])(implicit decoder: ByteVectorDecoder[A]): Task[DBDocument[A]] = {
    t.flatMap { document =>
      val errorOrA = Task.fromDisjunction(decoder.decode(document.data).leftMap(DocumentDecodeFailedException(_)))
      errorOrA.map(a => document.copy(data = a)) //Discard the value, coulde
    }
  }

  private final def encodeToByteVector[A](a: A)(implicit encoder: ByteVectorEncoder[A]): ByteVector = encoder.encode(a)
}

/**
 * Object to store helper (state agnostic) functions for the Bucket class.
 */
private object Bucket {
  import util.observable.toSingleItemTask

  def apply(core: CouchbaseCore, name: String): Bucket = apply(core, name, None)
  def apply(core: CouchbaseCore, name: String, password: Option[String]): Bucket = new Bucket(core, name, password)

  def get(core: CouchbaseCore, bucket: String, id: String): Task[DBDocument[ByteVector]] = {
    toSingleItemTask(core.send[GetResponse](new GetRequest(id, bucket))).flatMap { res =>
      toByteVector(id, bucket, res).map(DBDocument(Key(id), CommitVersion(res.cas), _))
    }
  }

  def create(core: CouchbaseCore, bucket: String,
    id: String, content: ByteVector, expires: Option[Duration]): Task[DBDocument[ByteVector]] = {
    val expiresSeconds = expires.map(_.toSeconds.toInt).getOrElse(0)
    val request = new InsertRequest(id, Unpooled.wrappedBuffer(content.toArray), expiresSeconds, 0, bucket)
    toSingleItemTask(core.send[InsertResponse](request)).flatMap { res =>
      val bytesOrError = toByteVectorWithCustomErrorHandling(id, bucket, res) {
        case ResponseStatus.EXISTS => DocumentAlreadyExistsException(id)
      }
      bytesOrError.map(DBDocument(Key(id), CommitVersion(res.cas), _))
    }
  }

  def replace(core: CouchbaseCore, bucket: String, id: String,
    content: ByteVector, cas: Long, expires: Option[Duration]): Task[DBDocument[ByteVector]] = {
    val expiresSeconds = expires.map(_.toSeconds.toInt).getOrElse(0)
    val request = new ReplaceRequest(id, Unpooled.wrappedBuffer(content.toArray), cas, expiresSeconds, 0, bucket)
    toSingleItemTask(core.send[ReplaceResponse](request)).flatMap { res =>
      toByteVector(id, bucket, res).map(DBDocument(Key(id), CommitVersion(res.cas), _))
    }
  }

  def remove(core: CouchbaseCore, bucket: String, id: String, cas: Long): Task[DBDocument[Unit]] = {
    val request = new RemoveRequest(id, cas, bucket)
    toSingleItemTask(core.send[RemoveResponse](request)).flatMap { res =>
      toByteVector(id, bucket, res).map(_ => DBDocument(Key(id), CommitVersion(res.cas), ()))
    }
  }

  def counter(core: CouchbaseCore, bucket: String, id: String,
    initial: Long, delta: Long, expires: Option[Duration]): Task[DBDocument[Long]] = {
    val expiresSeconds = expires.map(_.toSeconds.toInt).getOrElse(0)
    val request = new CounterRequest(id, initial, delta, expiresSeconds, bucket)
    toSingleItemTask(core.send[CounterResponse](request)).flatMap { res =>
      processResponse(id, bucket, res)(Function.const(())(_))(PartialFunction.empty[ResponseStatus, CouchbaseError]).map { _ =>
        DBDocument(Key(id), CommitVersion(res.cas), res.value)
      }
    }
  }

  private def toByteVectorWithCustomErrorHandling(
    id: String,
    bucket: String,
    res: AbstractKeyValueResponse
  )(specialErrorHandler: PartialFunction[ResponseStatus, CouchbaseError]): Task[ByteVector] =
    processResponse(id, bucket, res) { res => readBytes(res.content) }(specialErrorHandler)

  private def toByteVector(id: String, bucket: String, res: AbstractKeyValueResponse): Task[ByteVector] =
    toByteVectorWithCustomErrorHandling(id, bucket, res)(PartialFunction.empty[ResponseStatus, CouchbaseError])

  /**
   * Process the response and free the ByteBuf associated with it. We do both of these things in an
   * eager way to avoid doing it more than once.
   */
  private def processResponse[A](
    id: String,
    bucket: String,
    res: AbstractKeyValueResponse
  )(onSuccess: AbstractKeyValueResponse => A)(specialErrorHandler: PartialFunction[ResponseStatus, CouchbaseError]): Task[A] = { //scalastyle:ignore
    //Important that all of these eagerly consume res.content if they need it. It's freed immediately after this match.
    val status = res.status()
    val maybeHandledError = specialErrorHandler.lift(status).map(Task.fail(_))

    val result = maybeHandledError.getOrElse {
      res.status() match {
        case ResponseStatus.SUCCESS => Task.now(onSuccess(res))
        case ResponseStatus.NOT_EXISTS => Task.fail(DocumentDoesNotExistException(id, bucket))
        case ResponseStatus.EXISTS => Task.fail(CASMismatchException(id))
        case ResponseStatus.TEMPORARY_FAILURE | ResponseStatus.SERVER_BUSY => Task.fail(TemporaryFailureException())
        case ResponseStatus.COMMAND_UNAVAILABLE | ResponseStatus.FAILURE | ResponseStatus.INTERNAL_ERROR |
          ResponseStatus.INVALID_ARGUMENTS | ResponseStatus.NOT_STORED | ResponseStatus.OUT_OF_MEMORY |
          ResponseStatus.RETRY | ResponseStatus.TOO_BIG | ResponseStatus.RANGE_ERROR | ResponseStatus.ROLLBACK |
          ResponseStatus.SUBDOC_DELTA_RANGE | ResponseStatus.SUBDOC_DOC_NOT_JSON | ResponseStatus.SUBDOC_DOC_TOO_DEEP |
          ResponseStatus.SUBDOC_INVALID_COMBO | ResponseStatus.SUBDOC_MULTI_PATH_FAILURE | ResponseStatus.SUBDOC_NUM_RANGE |
          ResponseStatus.SUBDOC_PATH_EXISTS | ResponseStatus.SUBDOC_PATH_INVALID | ResponseStatus.SUBDOC_PATH_MISMATCH |
          ResponseStatus.SUBDOC_PATH_NOT_FOUND | ResponseStatus.SUBDOC_PATH_TOO_BIG | ResponseStatus.SUBDOC_VALUE_CANTINSERT |
          ResponseStatus.SUBDOC_VALUE_TOO_DEEP =>
          Task.fail(new CouchbaseException(s"Error '${res.status().toString}' returned from the couchbase server."))
      }
    }
    //Free the bytebuf.
    Option(res.content()).filter(_.refCnt > 0).foreach(_.release)
    result
  }

  /**
   * Copy the bytes to a byte array and create a ByteVector view on them
   */
  private def readBytes(b: ByteBuf): ByteVector = {
    val bytes = new Array[Byte](b.readableBytes)
    b.readBytes(bytes)
    ByteVector.view(bytes)
  }
}
