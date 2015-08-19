[![Build Status](https://travis-ci.org/IronCoreLabs/davenport.svg)](https://travis-ci.org/IronCoreLabs/davenport)
[ ![Download](https://api.bintray.com/packages/ironcorelabs/releases/davenport/images/download.svg) ](https://bintray.com/ironcorelabs/releases/davenport/_latestVersion)

# Davenport - A principled CouchBase library for Scala

First, there are other libraries for interfacing with CouchBase from scala that we're aware of:

* [Reactive CouchBase](http://reactivecouchbase.org)
* [CouchBase-Scala](https://github.com/giabao/couchbase-scala)

These are perfectly good libraries and you should feel free to evaluate them.  This library takes a different approach, which has its pluses and minuses.

## Benefits and Drawbacks

### Benefits of Davenport

* Uses a Free Grammar abstraction based on [scalaz](https://github.com/scalaz/scalaz)'s `Free` monads.This means that you string together a bunch of database instructions, but delay executing them until you're ready.  When you do execute them, you get to choose your interpreter.  All interpreters must handle all instructions.  Consequently, you can choose to execute your program against multiple backends.  In Davenport, we provide an in-memory local option as well as CouchBase.  The advantage here is testing: fast unit tests that don't require a CouchBase server or any cleanup.  For example:

```scala
import com.ironcorelabs.davenport.DB._
import com.ironcorelabs.davenport.{ MemConnection, CouchConnection }

// Some definitions that should help understand the code below
//   case class Key(value: String)
//   case class RawJsonString(value: String)
//   case class HashVer(value: String)
//   case class DbValue(jsonString: RawJsonString, hashVer: HashVer)

// Write something to the DB, then fetch it (we're ignoring the fact that we return
// the written value from the update command to make a point)
val k = Key("Record1")
val v = RawJsonString("""{ "field1": "value1", "field2": "value2" }""")
val operations = for {
  newDoc <- createDoc(k, v)
  fetchedDoc <- getDoc(k)
} yield fetchedDoc

// Now we can execute those operations using Couch or Mem.  Either:
val finalResult = MemConnection.exec(operations)
// or:
val finalResult = CouchConnection.exec(operations)

// and in either case the result will be the same except for the hashVer
```

* This also has some nice short-circuiting properties. If you have a DB error early on, continued DB operations will halt (unless you prefer otherwise).
* You can map over the DB and inject whatever other functions you like into the process.  As a more complex example, you can make an operation that copies a json field from one stored document to another:

```scala
import com.ironcorelabs.davenport.DB._
import argonaut._, Argonaut._

def copyFieldInDb(field: String, srcKey: Key, dstKey: Key): DBProg[DbValue] = for {
  src <- getDoc(srcKey)
  dst <- getDoc(dstKey)
  newjson <- liftIntoDBProg(copyFieldJson(field, src.jsonString, dst.jsonString))
  updatedDst <- updateDoc(dstKey, newjson, dst.hashVer)
} yield updatedDst

// This function is just argonaut magic and not really important for our use case
// We parse the strings, and if the source is a json object and the field exists
// and if the destination is a json object, then rework the json to copy in the
// new field and value from src to dst.
def copyFieldJson(field: String, srcJson: RawJsonString, dstJson: RawJsonString): Option[RawJsonString] = for {
  jsd <- Parse.parseOption(dstJson.value)
  jss <- Parse.parseOption(srcJson.value)
  lens = jObjectPL >=> jsonObjectPL(field)
  fieldval <- lens.get(jss)
} yield RawJsonString(((field, fieldval) ->: jsd).nospaces)

// in this case, the result will be an error since docA and docB have not been created
val finalResult = MemConnection.exec(copyFieldInDb("a", Key("docA"), Key("docB")))

// in this case, the result will be a successful new docB with a:1, c: 2, d: 2
val finalResult = MemConnection.exec(for {
  docA <- createDoc(Key("docA"), RawJsonString("""{ "a": 1, "b": 1, "c": 1 }"""))
  docB <- createDoc(Key("docB"), RawJsonString("""{ "c": 2, "d": 2 }"""))
  newB <- copyFieldInDb("a", Key("docA"), Key("docB"))
} yield newB)
```
* Besides testability, you get portability. If you decide later to use HBase or Riak or something, you only need to create an interpreter for that backend.
* Uses scalaz Disjunctions (`\/`) and Tasks. You can alternately call `execTask` on any interpreter and use that result to combine with other tasks, spin out into asynchronous execution, etc.  All errors are nicely modeled with returned exceptions (as opposed to thrown exceptions) to give insight into any issues without blowing up via uncaught errors.
* This is not meant to be used with raw json and keys, instead, this solution was built to work with thin DB wrappers around case classes that manage all persistance to any place.  See the examples directory to better understand how this works.

### Drawbacks of Davenport

CouchBase 4 has some powerful features including the N1QL query stuff, the ability to specify demands for quorum on a particular write, the ability to add an index, etc.  We will add capabilities for this sort of thing as we go and as we have need of them, but we don't currently support any advanced features.  We do, however, welcome enhancements.  These should be easy to make with one caveat: if you implement an operation to fetch a list of documents, then you will need to implement that functionality in both the CouchConnection and the MemConnection.  This probably isn't as bad as it seems.  You'd add some operation to add an index for some nested JSON value.  In Couch this would call the DB and ask for the index.  In Mem, it would generate some kind of function for future filtering of the Map.  You'd have a separate operation for fetching lists of documents based on that list and would implement that in both places.  This does mean that you can't assume the index is already in place on the couchbase server, but for sane unit tests and production, that's just as well.

We presently only support a single bucket, although multiple-bucket support should be trivial to add.  Again, please submit pull requests.

## Getting Started

### sbt

First, in your `build.sbt` file, you will want to reference davenport like so:

    resolvers += "IronCore BinTray Repo" at "http://dl.bintray.com/ironcorelabs/releases"
    libraryDependencies ++= Seq(
        "com.ironcorelabs" %% "davenport" % "0.0.5-alpha",
        "org.scalaz" %% "scalaz-core" % "7.1.2", // for type awesomeness
        "org.scalaz" %% "scalaz-concurrent" % "7.1.2", // for type awesomeness
        "io.argonaut" %% "argonaut" % "6.1" // json (de)serialization scalaz style
    )

**Note:** argonaut is not actually required.  You may use any json serialization that you prefer, but you will need argonaut to follow along with our example and getting started code.

### Configuration

We only support a few configuration parameters at the moment.  Please do submit pull requests for enhancements to `CouchConnection.scala` to add more powerful configuration options.  Within your repo, you can add a `couchbase.cfg` file into your classpath.  We recommend using the `./src/main/resources` path if you're unsure.  This config file will be checked in and used by all developers of your repo.  If developers want to override the default configuration stored in the repo, they should add `couchbase-dev.cfg` to their `.gitignore` file and add a `couchbase-dev.cfg` file to the root of the project.  Any values in that dev file will overwrite the values in the config file in the classpath.  Here is an example config file:

    cdb {
      host = "couchbase.local"
      bucketName = "default"
      queryEnabled = false
      ioPoolSize = 4
      computationPoolSize = 4
      kvEndpoints = 2
    }

### Storing and Retrieving Case Classes

Suppose you have a User class that has some basic fields:

    case class User(firstName: String, lastName: String, email: String, createdDate: Long)

We prefer not to adulterate the underlying class, but instead to wrap it in a class that can persist it to/from the database.  We do so by creating an interface that by convention we call `DBUser`:

```scala
case class DBUser(val key: Key, val data: User, val cas: Long) extends DBDocument[User] {
  // ...
}
object DBUser extends DBDocumentCompanion[User] {
  // ...
}
```

In wrapping the User class, we put the concerns such as the key that is used, the check-and-store (cas) value (used to make sure someone else hasn't updated the database since we last fetched a record) and implementations for serializing/deserializing outside of the core concerns for the class itself.

In terms of implementation, the document needs to support one function:

```scala
def dataJson: Throwable \/ RawJsonString
```

And the companion object needs to support a few more:

```scala
implicit def codec: CodecJson[T]
def genKey(d: T): DBProg[Key]
def fromJson(s: RawJsonString): Throwable \/ T
def create(d: T): DBProg[_]
def get(k: Key): DBProg[_]
```

Generally speaking, these are pretty boilerplate.  One thing to note is that the `genKey` method returns a `DBProg`.  This is in case the key itself depends on the database, as in the case where you are using an incrementing index as the key.  If you are deriving the key from the underlying class, you just wrap the result into a `DBProg`.  In our case, our key for a `User` will be `user::<email>`, so statically derived.  Here's a fuller implementation:

```scala
import com.ironcorelabs.davenport._, DB._
import scalaz._, Scalaz._, scalaz.concurrent.Task
import argonaut._, Argonaut._

case class User(firstName: String, lastName: String, email: String, createdDate: Long)

case class DBUser(val key: Key, val data: User, val cas: Long) extends DBDocument[User] {
  def dataJson: Throwable \/ RawJsonString =
    \/.fromTryCatchNonFatal(DBUser.toJsonString(data)(DBUser.codec))
}
object DBUser extends DBDocumentCompanion[User] {
  implicit def codec: CodecJson[User] = casecodec4(User.apply, User.unapply)(
    "firstName", "lastName", "email", "createdDate"
  )
  def genKey(u: User): DBProg[Key] = liftIntoDBProg(Key(s"user::${u.email}").right[Throwable])
  def fromJson(s: RawJsonString): Throwable \/ User =
    fromJsonString(s.value) \/> new Exception("Failed to decode json to User")
  def create(u: User): DBProg[DBUser] = for {
    json <- liftIntoDBProg(\/.fromTryCatchNonFatal(toJsonString(u)))
    key <- genKey(u)
    newdoc <- createDoc(key, json)
    cas = newdoc.hashVer.value
  } yield DBUser(key, u, cas)
  def get(key: Key): DBProg[DBUser] = for {
    doc <- getDoc(key)
    u <- liftIntoDBProg(fromJson(doc.jsonString))
    cas = doc.hashVer.value
  } yield DBUser(key, u, cas)
}

```

Anyone wanting to factor out some of the boilerplate -- please be my guest, we'd love the pull requests.

Now that we have a nice abstraction for persisting our user class, lets try it out:

```scala
val addTwoNewUsers = for {
  newu1 <- DBUser.create(User("User", "One", "readyplayerone@example.com", System.currentTimeMillis()))
  newu2 <- DBUser.create(User("User", "Two", "readyplayertwo@example.com", System.currentTimeMillis()))
} yield List(newu1, newu2)
val users: Throwable \/ List[DBUser] = MemConnection.exec(addTwoNewUsers)
// users: scalaz.\/[Throwable,List[DBUser]] = \/-(List(DBUser(Key(user::readyplayerone@example.com),User(User,One,readyplayerone@example.com,1439940334529),2111310807), DBUser(Key(user::readyplayertwo@example.com),User(User,Two,readyplayertwo@example.com,1439940400410),-1262405529)))

```

Feel free to test against CouchBase as well.  We'll keep illustrating with the MemConnection for now to show how you can easily experiment and write unit tests.  As an alternative to calling `MemConnection.exec` you can call `MemConnection.run`.  This is not part of the common interface dictated by `AbstractConnection`, but is special to the memory implementation.  `run` takes an optional `Map` and returns a tuple with the `Map` and the results.  You can then use this `Map` as your state and as a starting point for a database with known values in it.  Building on our example above, we could instead do this:

```scala
val (db: MemConnection.KVMap, users: \/[Throwable, List[DBUser]]) = MemConnection.run(addTwoNewUsers)

// Fetch one of the users out of the database
val (db2, u1) = MemConnection.run(DBUser.get(Key("user::readyplayerone@example.com")), db)
// u1: scalaz.\/[Throwable,DBUser]] = \/-(DBUser(Key(user::readyplayerone@example.com),
» User(User,One,readyplayerone@example.com,1439940334529),2111310807))
```

We expect that the primitives with `RawJsonString` will generally not be used outside of the `DBDocument` classes.



---

Copyright (c) 2015 IronCore Labs

Licensed under the [MIT Open Source License](http://opensource.org/licenses/MIT)

