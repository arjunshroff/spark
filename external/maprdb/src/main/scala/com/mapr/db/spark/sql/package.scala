/* Copyright (c) 2015 & onwards. MapR Tech, Inc., All rights reserved */
package com.mapr.db.spark

import scala.language.implicitConversions

import org.apache.spark.sql._

package object sql {

  implicit def toSparkSessionFunctions(sqlContext: SQLContext): SparkSessionFunctions = {
    toSparkSessionFunctions(sqlContext.sparkSession)
  }

  implicit def toSparkSessionFunctions(sparkSession: SparkSession): SparkSessionFunctions = {
    SparkSessionFunctions(sparkSession)
  }

  implicit def toMaprdbReaderFunctions(dfr: DataFrameReader): MapRDBDataFrameReaderFunctions = {
    MapRDBDataFrameReaderFunctions(dfr)
  }

  implicit def toMaprdbWriterFunctions(dfw: DataFrameWriter[Any]):
  MapRDBDataFrameWriterFunctions[Any] = {
    MapRDBDataFrameWriterFunctions(dfw)
  }

  implicit def toMapRDBDataFrame(ds: Dataset[_]): MapRDBDataFrameFunctions = {
    MapRDBDataFrameFunctions(ds.toDF())
  }
}
