//
// Copyright (c) 2015 IronCore Labs
//
package com.ironcorelabs.davenport

import scalaz.NonEmptyList

/**
 * The configuration class to tell Davenport how to configure the [[CouchConnection]].
 * @param ioPoolSize - Number of threads to use for IO in the underlying couchbase connection.
 * @param computationPoolSize - Number of threads to use for computation in underlying couchbase connection.
 * @param kvEndpoints - Number of sockets to keep open per node. Should be left at 1 unless you're sure the socket is saturated.
 * @param hosts - ip addresses or hostnames of the couchbase cluster nodes.
 */
final case class DavenportConfig(
    ioPoolSize: Int,
    computationPoolSize: Int,
    kvEndpoints: Int,
    hosts: NonEmptyList[String]
) {
  private def setConfig[A](opt: Option[A], copy: A => DavenportConfig): DavenportConfig = {
    opt.map(copy(_)).getOrElse(this)
  }

  /**
   * Create a new DavenportConfig with maybeHosts as hosts. If maybeHosts is Nil return `this`.
   */
  def setHosts(maybeHosts: List[String]): DavenportConfig = maybeHosts match {
    case Nil => this
    case hd :: tail => copy(hosts = NonEmptyList.nel(hd, tail))
  }

  /**
   * Set [[ioPoolSize]] to maybePool if it is Some, otherwise return this
   */
  def setIOPoolSize(maybePool: Option[Int]): DavenportConfig = setConfig(maybePool, { i: Int => copy(ioPoolSize = i) })
  /**
   * Set [[computationPoolSize]] to maybePool if it is Some, otherwise return this
   */
  def setComputationPoolSize(maybePool: Option[Int]): DavenportConfig = setConfig(maybePool, { i: Int => copy(computationPoolSize = i) })
  /**
   * Set [[kvEndpoints]] to maybeEndpoints if it is Some, otherwise return this
   */
  def setKVEndpoints(maybeEndpoints: Option[Int]): DavenportConfig = setConfig(maybeEndpoints, { i: Int => copy(kvEndpoints = i) })
}

final object DavenportConfig {
  /**
   * Produce a DavenportConfig object with default configuration.
   */
  final def withDefaults(
    poolSize: Int = DefaultIOPoolSize,
    computationPoolSize: Int = DefaultComputationPoolSize,
    kvEndpoints: Int = DefaultKeyValueEndpoints,
    hosts: NonEmptyList[String] = NonEmptyList.nels("127.0.0.1")
  ): DavenportConfig =
    DavenportConfig(poolSize, computationPoolSize, kvEndpoints, hosts)
  private final val DefaultIOPoolSize: Int = 4
  private final val DefaultComputationPoolSize: Int = 4
  private final val DefaultKeyValueEndpoints: Int = 1
}
