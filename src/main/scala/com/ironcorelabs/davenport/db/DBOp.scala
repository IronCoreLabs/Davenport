//
// Copyright (c) 2015 IronCore Labs
//
package com.ironcorelabs.davenport.db

import scalaz.\/
import scalaz.concurrent.Task
import scalaz.stream.Process

/** Any database operation must be represented by a `DBOp` */
sealed trait DBOp[A]
final case class GetDoc(key: Key) extends DBOp[DBError \/ DBValue]
final case class CreateDoc(key: Key, doc: RawJsonString) extends DBOp[DBError \/ DBValue]
final case class UpdateDoc(key: Key, doc: RawJsonString, commitVersion: CommitVersion) extends DBOp[DBError \/ DBValue]
final case class RemoveKey(key: Key) extends DBOp[DBError \/ Unit]
final case class GetCounter(key: Key) extends DBOp[DBError \/ Long]
final case class IncrementCounter(key: Key, delta: Long = 1) extends DBOp[DBError \/ Long]
final case class ScanKeys(op: Comparison, value: String) extends DBOp[DBError \/ Process[Task, DBValue]]
