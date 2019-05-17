[![Build Status](https://travis-ci.org/IronCoreLabs/davenport.svg)](https://travis-ci.org/IronCoreLabs/davenport)
[![Maven Central](https://maven-badges.herokuapp.com/maven-central/com.ironcorelabs/davenport_2.11/badge.svg)](https://maven-badges.herokuapp.com/maven-central/com.ironcorelabs/davenport_2.11)
[![codecov.io](http://codecov.io/github/IronCoreLabs/davenport/coverage.svg?branch=master)](http://codecov.io/github/IronCoreLabs/davenport?branch=master)
[![Codacy Badge](https://www.codacy.com/project/badge/f9ad4d48e42d49fc851af5d9697753b8)](https://www.codacy.com/app/github-zmre/davenport)
[![MIT Open Source License](https://img.shields.io/badge/license-MIT-blue.svg)](http://opensource.org/licenses/MIT)
[![Stories In Progress](https://badge.waffle.io/IronCoreLabs/davenport.svg?label=in%20progress&title=In%20Progress)](http://waffle.io/IronCoreLabs/davenport)
[![scaladoc](https://javadoc-badge.appspot.com/com.ironcorelabs/davenport_2.11.svg?label=scaladoc)](https://javadoc-badge.appspot.com/com.ironcorelabs/davenport_2.11)


# Davenport - A principled Couchbase library for Scala

Davenport brings Free Monads to interactions with Couchbase. You don't need to know or understand what these are as they can remain under the hood.  The key takeaway is that you assemble together a set of functions and database operations, but you delay execution of this.  When you're ready, you pass the set of instructions to a connection.  We currently support an in-memory Map and Couchbase as connections.  This makes testing and reasoning about your database operations far simpler.

There are other libraries for interfacing with Couchbase from scala that we're aware of:

* [Reactive Couchbase](http://reactivecouchbase.org)
* [Couchbase-Scala](https://github.com/giabao/couchbase-scala)

These are perfectly good libraries and you should evaluate them.  This library takes a different approach, which has its pluses and minuses, as described in the Benefits and Drawbacks section of this README.  In summary, this is a new project and light on advanced Couchbase features, but strong on composability and testability.  You can parallelize things as well using composable scalaz Tasks as desired.


## More Information

* [Davenport](https://ironcorelabs.com/davenport) project site
* [Scaladocs](https://ironcorelabs.com/davenport/latest/api)
* [Getting Started](https://ironcorelabs.com/davenport/tutorials/getting-started.html) instructions and examples

---

Copyright (c) 2015-present IronCore Labs
All rights reserved.

Licensed under the [MIT Open Source License](http://opensource.org/licenses/MIT)

