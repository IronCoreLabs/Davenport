//
// Copyright (c) 2015 IronCore Labs
//
package com.ironcorelabs.davenport.db

sealed trait Comparison

case object EQ extends Comparison
case object GT extends Comparison
case object LT extends Comparison
case object LTE extends Comparison
case object GTE extends Comparison
