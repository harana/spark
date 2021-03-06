/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.spark.sql.sources.v2

import java.util

import scala.collection.JavaConverters._
import scala.collection.mutable

import org.scalatest.BeforeAndAfter

import org.apache.spark.sql.{DataFrame, QueryTest, Row, SparkSession}
import org.apache.spark.sql.catalog.v2.expressions.{FieldReference, IdentityTransform, Transform}
import org.apache.spark.sql.sources.{DataSourceRegister, Filter, InsertableRelation}
import org.apache.spark.sql.sources.v2.utils.TestV2SessionCatalogBase
import org.apache.spark.sql.sources.v2.writer.{SupportsOverwrite, SupportsTruncate, V1WriteBuilder, WriteBuilder}
import org.apache.spark.sql.test.SharedSparkSession
import org.apache.spark.sql.types.{IntegerType, StringType, StructType}
import org.apache.spark.sql.util.CaseInsensitiveStringMap

class V1WriteFallbackSuite extends QueryTest with SharedSparkSession with BeforeAndAfter {

  import testImplicits._

  private val v2Format = classOf[InMemoryV1Provider].getName

  override def beforeAll(): Unit = {
    super.beforeAll()
    InMemoryV1Provider.clear()
  }

  override def afterEach(): Unit = {
    super.afterEach()
    InMemoryV1Provider.clear()
  }

  test("append fallback") {
    val df = Seq((1, "x"), (2, "y"), (3, "z")).toDF("a", "b")
    df.write.mode("append").option("name", "t1").format(v2Format).save()
    checkAnswer(InMemoryV1Provider.getTableData(spark, "t1"), df)
    df.write.mode("append").option("name", "t1").format(v2Format).save()
    checkAnswer(InMemoryV1Provider.getTableData(spark, "t1"), df.union(df))
  }

  test("overwrite by truncate fallback") {
    val df = Seq((1, "x"), (2, "y"), (3, "z")).toDF("a", "b")
    df.write.mode("append").option("name", "t1").format(v2Format).save()

    val df2 = Seq((10, "k"), (20, "l"), (30, "m")).toDF("a", "b")
    df2.write.mode("overwrite").option("name", "t1").format(v2Format).save()
    checkAnswer(InMemoryV1Provider.getTableData(spark, "t1"), df2)
  }
}

class V1WriteFallbackSessionCatalogSuite
  extends SessionCatalogTest[InMemoryTableWithV1Fallback, V1FallbackTableCatalog] {
  override protected val v2Format = classOf[InMemoryV1Provider].getName
  override protected val catalogClassName: String = classOf[V1FallbackTableCatalog].getName

  override protected def verifyTable(tableName: String, expected: DataFrame): Unit = {
    checkAnswer(InMemoryV1Provider.getTableData(spark, s"default.$tableName"), expected)
  }
}

class V1FallbackTableCatalog extends TestV2SessionCatalogBase[InMemoryTableWithV1Fallback] {
  override def newTable(
      name: String,
      schema: StructType,
      partitions: Array[Transform],
      properties: util.Map[String, String]): InMemoryTableWithV1Fallback = {
    val t = new InMemoryTableWithV1Fallback(name, schema, partitions, properties)
    InMemoryV1Provider.tables.put(name, t)
    t
  }
}

private object InMemoryV1Provider {
  val tables: mutable.Map[String, InMemoryTableWithV1Fallback] = mutable.Map.empty

  def getTableData(spark: SparkSession, name: String): DataFrame = {
    val t = tables.getOrElse(name, throw new IllegalArgumentException(s"Table $name doesn't exist"))
    spark.createDataFrame(t.getData.asJava, t.schema)
  }

  def clear(): Unit = {
    tables.clear()
  }
}

class InMemoryV1Provider extends TableProvider with DataSourceRegister {
  override def getTable(options: CaseInsensitiveStringMap): Table = {
    InMemoryV1Provider.tables.getOrElseUpdate(options.get("name"), {
      new InMemoryTableWithV1Fallback(
        "InMemoryTableWithV1Fallback",
        new StructType().add("a", IntegerType).add("b", StringType),
        Array(IdentityTransform(FieldReference(Seq("a")))),
        options.asCaseSensitiveMap()
      )
    })
  }

  override def shortName(): String = "in-memory"
}

class InMemoryTableWithV1Fallback(
    override val name: String,
    override val schema: StructType,
    override val partitioning: Array[Transform],
    override val properties: util.Map[String, String]) extends Table with SupportsWrite {

  partitioning.foreach { t =>
    if (!t.isInstanceOf[IdentityTransform]) {
      throw new IllegalArgumentException(s"Transform $t must be IdentityTransform")
    }
  }

  override def capabilities: util.Set[TableCapability] = Set(
    TableCapability.BATCH_WRITE,
    TableCapability.V1_BATCH_WRITE,
    TableCapability.OVERWRITE_BY_FILTER,
    TableCapability.TRUNCATE).asJava

  @volatile private var dataMap: mutable.Map[Seq[Any], Seq[Row]] = mutable.Map.empty
  private val partFieldNames = partitioning.flatMap(_.references).toSeq.flatMap(_.fieldNames)
  private val partIndexes = partFieldNames.map(schema.fieldIndex(_))

  def getData: Seq[Row] = dataMap.values.flatten.toSeq

  override def newWriteBuilder(options: CaseInsensitiveStringMap): WriteBuilder = {
    new FallbackWriteBuilder(options)
  }

  private class FallbackWriteBuilder(options: CaseInsensitiveStringMap)
    extends WriteBuilder
    with V1WriteBuilder
    with SupportsTruncate
    with SupportsOverwrite {

    private var mode = "append"

    override def truncate(): WriteBuilder = {
      dataMap.clear()
      mode = "truncate"
      this
    }

    override def overwrite(filters: Array[Filter]): WriteBuilder = {
      val keys = InMemoryTable.filtersToKeys(dataMap.keys, partFieldNames, filters)
      dataMap --= keys
      mode = "overwrite"
      this
    }

    private def getPartitionValues(row: Row): Seq[Any] = {
      partIndexes.map(row.get)
    }

    override def buildForV1Write(): InsertableRelation = {
      new InsertableRelation {
        override def insert(data: DataFrame, overwrite: Boolean): Unit = {
          assert(!overwrite, "V1 write fallbacks cannot be called with overwrite=true")
          val rows = data.collect()
          rows.groupBy(getPartitionValues).foreach { case (partition, elements) =>
            if (dataMap.contains(partition) && mode == "append") {
              dataMap.put(partition, dataMap(partition) ++ elements)
            } else if (dataMap.contains(partition)) {
              throw new IllegalStateException("Partition was not removed properly")
            } else {
              dataMap.put(partition, elements)
            }
          }
        }
      }
    }
  }
}
