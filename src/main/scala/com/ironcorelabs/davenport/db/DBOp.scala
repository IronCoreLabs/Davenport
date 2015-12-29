//
// Copyright (c) 2015 IronCore Labs
//
package com.ironcorelabs.davenport.db

import scalaz.\/

/** Any database operation must be represented by a `DBOp` */
sealed trait DBOp[A]
final case class GetDoc(key: Key) extends DBOp[DBError \/ DBValue]
final case class CreateDoc(key: Key, doc: RawJsonString) extends DBOp[DBError \/ DBValue]
final case class UpdateDoc(key: Key, doc: RawJsonString, commitVersion: CommitVersion) extends DBOp[DBError \/ DBValue]
final case class RemoveKey(key: Key) extends DBOp[DBError \/ Unit]
final case class GetCounter(key: Key) extends DBOp[DBError \/ Long]
final case class IncrementCounter(key: Key, delta: Long = 1) extends DBOp[DBError \/ Long]
//Constuctor hidden for safety. Limit and offset have to >=0
final case class ScanKeys private[db] (op: Comparison, value: String, limit: Int, offset: Int, consistency: ScanConsistency) extends DBOp[DBError \/ List[DBValue]]
