//
// Copyright (c) 2015 IronCore Labs
//
package com.ironcorelabs.davenport.db

sealed trait ScanConsistency

/**
 * If passed to [ScanKeys] possibly stale data will be returned, but the query can start immediately.
 */
case object AllowStale extends ScanConsistency

/**
 * If you need to guarantee the most up to date data from the index. The query will wait until all writes have been
 * completed and the index is caught up.
 */
case object EnsureConsistency extends ScanConsistency
