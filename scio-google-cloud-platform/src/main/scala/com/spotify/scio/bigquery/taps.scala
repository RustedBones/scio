/*
 * Copyright 2019 Spotify AB.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

package com.spotify.scio.bigquery

import com.google.cloud.bigquery.storage.v1.ReadSession.TableReadOptions
import com.google.api.services.bigquery.model.{TableReference, TableSchema}
import com.spotify.scio.ScioContext
import com.spotify.scio.avro._
import com.spotify.scio.bigquery.client.BigQuery
import com.spotify.scio.coders.Coder
import com.spotify.scio.io.{FileStorage, Tap, Taps}
import com.spotify.scio.values.SCollection
import org.apache.avro.generic.GenericRecord

import scala.jdk.CollectionConverters._
import scala.concurrent.Future
import scala.reflect.runtime.universe._
import com.spotify.scio.bigquery.BigQueryTypedTable.Format
import com.twitter.chill.Externalizer

/** Tap for BigQuery TableRow JSON files. */
final case class TableRowJsonTap(path: String, params: TableRowJsonIO.ReadParam)
    extends Tap[TableRow] {
  override def value: Iterator[TableRow] =
    FileStorage(path, params.suffix).tableRowJsonFile
  override def open(sc: ScioContext): SCollection[TableRow] =
    sc.read(TableRowJsonIO(path))(params)
}

final case class BigQueryTypedTap[T: Coder](table: Table, fn: (GenericRecord, TableSchema) => T)
    extends Tap[T] {
  lazy val client: BigQuery = BigQuery.defaultInstance()
  lazy val ts: TableSchema = client.tables.table(table.spec).getSchema

  override def value: Iterator[T] =
    client.tables.avroRows(table).map(gr => fn(gr, ts))

  override def open(sc: ScioContext): SCollection[T] = {
    val ser = Externalizer(ts)
    // TODO this is inefficient. Migrate to TableRow API ?
    val coder = avroGenericRecordCoder
    sc.read(BigQueryTypedTable(table, Format.GenericRecord)(coder)).map(gr => fn(gr, ser.get))
  }
}

/** Tap for BigQuery tables. */
final case class BigQueryTap(table: TableReference) extends Tap[TableRow] {
  override def value: Iterator[TableRow] =
    BigQuery.defaultInstance().tables.rows(Table.Ref(table))
  override def open(sc: ScioContext): SCollection[TableRow] =
    sc.read(BigQueryTypedTable(Table.Ref(table), Format.TableRow))
}

/** Tap for BigQuery tables using storage api. */
final case class BigQueryStorageTap(table: Table, readOptions: TableReadOptions)
    extends Tap[TableRow] {
  override def value: Iterator[TableRow] =
    BigQuery.defaultInstance().tables.storageRows(table, readOptions)
  override def open(sc: ScioContext): SCollection[TableRow] =
    sc.read(
      BigQueryStorage(
        table,
        readOptions.getSelectedFieldsList.asScala.toList,
        Option(readOptions.getRowRestriction)
      )
    )
}

final case class BigQueryTaps(self: Taps) {
  import com.spotify.scio.bigquery.types.BigQueryType
  import com.spotify.scio.bigquery.types.BigQueryType.HasAnnotation
  import org.apache.beam.sdk.io.gcp.bigquery.BigQueryHelpers
  import self.mkTap

  private lazy val bqc = BigQuery.defaultInstance()

  /** Get a `Future[Tap[TableRow]]` for BigQuery SELECT query. */
  def bigQuerySelect(sqlQuery: String, flattenResults: Boolean = false): Future[Tap[TableRow]] =
    mkTap(
      s"BigQuery SELECT: $sqlQuery",
      () => isQueryDone(sqlQuery),
      () => BigQuerySelect(Query(sqlQuery)).tap(BigQuerySelect.ReadParam(flattenResults))
    )

  /** Get a `Future[Tap[TableRow]]` for BigQuery table. */
  def bigQueryTable(table: TableReference): Future[Tap[TableRow]] =
    mkTap(
      s"BigQuery Table: $table",
      () => bqc.tables.exists(table),
      () => BigQueryTypedTable(Table.Ref(table), Format.TableRow).tap(())
    )

  /** Get a `Future[Tap[TableRow]]` for BigQuery table. */
  def bigQueryTable(tableSpec: String): Future[Tap[TableRow]] =
    bigQueryTable(BigQueryHelpers.parseTableSpec(tableSpec))

  /** Get a `Future[Tap[T]]` for typed BigQuery source. */
  def typedBigQuery[T <: HasAnnotation: TypeTag: Coder](
    newSource: String = null
  ): Future[Tap[T]] = {
    val bqt = BigQueryType[T]
    lazy val table =
      scala.util.Try(BigQueryHelpers.parseTableSpec(newSource)).toOption
    val rows =
      newSource match {
        // newSource is missing, T's companion object must have either table or query
        case null if bqt.isTable =>
          bigQueryTable(bqt.table.get)
        case null if bqt.isQuery =>
          bigQuerySelect(bqt.queryRaw.get)
        case null =>
          throw new IllegalArgumentException(s"Missing table or query field in companion object")
        case _ if table.isDefined =>
          bigQueryTable(table.get)
        case _ =>
          bigQuerySelect(newSource)
      }
    import scala.concurrent.ExecutionContext.Implicits.global
    rows.map(_.map(bqt.fromTableRow))
  }

  /** Get a `Future[Tap[TableRow]]` for a BigQuery TableRow JSON file. */
  def tableRowJsonFile(
    path: String,
    params: TableRowJsonIO.ReadParam = TableRowJsonIO.ReadParam()
  ): Future[Tap[TableRow]] =
    mkTap(
      s"TableRowJson: $path",
      () => self.isPathDone(path, params.suffix),
      () => TableRowJsonTap(path, params)
    )

  def bigQueryStorage(
    table: TableReference,
    readOptions: TableReadOptions
  ): Future[Tap[TableRow]] =
    mkTap(
      s"BigQuery direct read table: $table",
      () => bqc.tables.exists(table),
      () => {
        val selectedFields = readOptions.getSelectedFieldsList.asScala.toList
        val rowRestriction = Option(readOptions.getRowRestriction)
        BigQueryStorage(Table.Ref(table), selectedFields, rowRestriction).tap(())
      }
    )

  def typedBigQueryStorage[T: TypeTag: Coder](
    table: TableReference,
    readOptions: TableReadOptions
  ): Future[Tap[T]] = {
    val fn = BigQueryType[T].fromTableRow
    mkTap(
      s"BigQuery direct read table: $table",
      () => bqc.tables.exists(table),
      () => {
        val selectedFields = readOptions.getSelectedFieldsList.asScala.toList
        val rowRestriction = Option(readOptions.getRowRestriction)
        BigQueryStorage(Table.Ref(table), selectedFields, rowRestriction)
          .tap(())
          .map(fn)
      }
    )
  }

  private def isQueryDone(sqlQuery: String): Boolean =
    bqc.query.extractTables(sqlQuery).forall(bqc.tables.exists)
}

object BigQueryTaps {
  implicit def toBigQueryTaps(underlying: Taps): BigQueryTaps =
    BigQueryTaps(underlying)
}
