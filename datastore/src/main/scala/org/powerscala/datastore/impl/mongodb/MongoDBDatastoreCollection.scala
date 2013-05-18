package org.powerscala.datastore.impl.mongodb

import org.powerscala.datastore._
import org.powerscala.datastore.converter.DataObjectConverter
import com.mongodb.{QueryBuilder, BasicDBList, BasicDBObject}

import query._
import query.DatastoreQuery
import scala.collection.JavaConversions._
import java.util.regex.Pattern
import java.util.UUID

/**
 * @author Matt Hicks <mhicks@powerscala.org>
 */
class MongoDBDatastoreCollection[T <: Identifiable](val session: MongoDBDatastoreSession, val name: String)
                                                   (implicit val manifest: Manifest[T])
                                                   extends DatastoreCollection[T] {
  lazy val collection = session.database.getCollection(name)

  def removeField(field: Field[T, _]) = {
    val query = new BasicDBObject(field.name, new BasicDBObject("$exists", true))
    val update = new BasicDBObject("$unset", new BasicDBObject(field.name, 1))
    val upsert = false
    val multi = true
    collection.update(query, update, upsert, multi)
  }

  def renameField(currentName: String, newName: String) = {
    val query = new BasicDBObject(currentName, new BasicDBObject("$exists", true))
    val update = new BasicDBObject("$rename", new BasicDBObject(currentName, newName))
    val upsert = false
    val multi = true
    collection.update(query, update, upsert, multi)
  }

  protected def persistNew(ref: T) = collection.insert(DataObjectConverter.toDBObject(ref, this))

  protected def persistModified(ref: T) = {
    collection.findAndModify(new BasicDBObject("_id", ref.id), DataObjectConverter.toDBObject(ref, this))
  }

  protected def deleteInternal(ref: T) = {
    collection.findAndRemove(new BasicDBObject("_id", ref.id))
  }

  override def size = query.size

  private def addFilter(qb: QueryBuilder, filter: Filter[_], prepend: String = ""): Unit = filter match {
    case ff: FieldFilter[_] => {
      val name = prepend + ff.field.name
      lazy val value = DataObjectConverter.toDBValue(ff.value, this)
      ff.operator match {
        case Operator.equal => qb.put(name).is(value)
        case Operator.nequal => qb.put(name).notEquals(value)
        case Operator.exists => qb.put(name).exists(value)
        case Operator.< => qb.put(name).lessThan(value)
        case Operator.> => qb.put(name).greaterThan(value)
        case Operator.<= => qb.put(name).lessThanEquals(value)
        case Operator.>= => qb.put(name).greaterThanEquals(value)
        case Operator.regex => qb.put(name).regex(value.asInstanceOf[Pattern])
        case Operator.subfilter => addFilter(qb, ff.value.asInstanceOf[Filter[_]], "%s.".format(name))
        case Operator.in => {
          val list = new BasicDBList()
          ff.value.asInstanceOf[Seq[_]].foreach {
            case v => list.add(DataObjectConverter.toDBValue(v, this).asInstanceOf[AnyRef])
          }
          qb.put(name).in(list)
        }
        case _ => throw new RuntimeException("Unsupported operator: %s for FieldFilter!".format(ff.operator))
      }
    }
    case sf: SubFilter[_] => {
      val filters = sf.filters.map(subfilter => {
        val sqb = new QueryBuilder
        addFilter(sqb, subfilter)
        sqb.get()
      })
      sf.operator match {
        case Operator.or => qb.or(filters: _*)
        case Operator.and => qb.and(filters: _*)
        case _ => throw new RuntimeException("Unsupported operator: %s for SubFilter!".format(sf.operator))
      }
    }
    case _ => throw new RuntimeException("Unknown filter type: %s".format(filter.getClass.getName))
  }

  private def queryCursor(query: DatastoreQuery[T]) = {
    val qb = new QueryBuilder

    // Add filters
    val filters = query._filters.reverse
    filters.foreach(f => addFilter(qb, f))

    val dbo = qb.get()
    var cursor = if (query._fields.nonEmpty) {
      val keys = new BasicDBObject(query._fields.size)
      query._fields.foreach {
        case f => keys.put(f.name, true)
      }
      keys.put("class", true)
      collection.find(dbo, keys)
    } else {
      collection.find(dbo)
    }
    if (query._skip > 0) {
      cursor = cursor.skip(query._skip)
    }
    if (query._limit > 0) {
      cursor = cursor.limit(query._limit)
    }
    debug("Executing Query (skip: %s, limit: %s): %s".format(query._skip, query._limit, cursor.getQuery))
    val sort = query._sort.reverse
    if (sort.nonEmpty) {
      val sdbo = new BasicDBObject()
      sort.foreach {
        case s => {
          val direction = s.direction match {
            case SortDirection.Ascending => 1
            case SortDirection.Descending => -1
          }
          sdbo.put(s.field.name, direction)
        }
      }
      debug("Sorting: %s".format(sdbo))
      cursor = cursor.sort(sdbo)
    }
    cursor
  }

  def executeQuery(query: DatastoreQuery[T]) = {
    val cursor = queryCursor(query)
    asScalaIterator(cursor).map(entry => {
      val v = DataObjectConverter.fromDBValue(entry, this)
      v match {
        case identifiable: Identifiable => ids += identifiable.id
        case _ =>
      }
      v
    }).asInstanceOf[Iterator[T]]
  }

  def executeQueryIds(query: DatastoreQuery[T]) = {
    val cursor = queryCursor(query)
    asScalaIterator(cursor).map(entry => entry.get("_id").asInstanceOf[UUID])
  }

  def executeQuerySize(query: DatastoreQuery[T]) = {
    val cursor = queryCursor(query)
    try {
      cursor.size()
    } finally {
      cursor.close()
    }
  }

  def drop() = collection.drop()

  def createIndexes(fields: List[Field[T, _]]) = {
    fields.foreach {
      case f => {
        collection.ensureIndex(new BasicDBObject(f.name, 1))
      }
    }
  }

  def replaceRevisionClass(revision: Int, newClass: String) = {
    val query = new BasicDBObject("revision", revision)
    val update = new BasicDBObject("$set", new BasicDBObject("class", newClass))
    val result = collection.update(query, update, false, true)
    result.getN
  }
}
