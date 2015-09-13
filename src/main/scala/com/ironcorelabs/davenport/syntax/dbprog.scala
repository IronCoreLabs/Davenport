//
// com.ironcorelabs.davenport.AbstractConnection
//
// Copyright (c) 2015 IronCore Labs
//
package com.ironcorelabs.davenport
package syntax

import DB._

import scalaz.stream.Process
import scalaz.\/
import scalaz.concurrent.Task

object dbprog {
  implicit class DBProgOps[A](self: DBProg[A]) {
    def process: Process[DBOps, Throwable \/ A] = Batch.liftToProcess(self)
    def execTask[Connection <: AbstractConnection](c: Connection): Task[Throwable \/ A] = c.execTask(self)
  }
}
