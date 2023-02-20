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

package io.glutenproject.execution

import java.util.concurrent.TimeUnit.NANOSECONDS
import scala.collection.mutable.HashMap
import io.glutenproject.GlutenConfig
import io.glutenproject.backendsapi.BackendsApiManager
import io.glutenproject.execution.FileSourceScanExecTransformer.isDynamicPruningFilter
import io.glutenproject.vectorized.OperatorMetrics
import org.apache.hadoop.fs.Path
import org.apache.spark.rdd.RDD
import org.apache.spark.sql.catalyst.TableIdentifier
import org.apache.spark.sql.catalyst.catalog.BucketSpec
import org.apache.spark.sql.catalyst.expressions.{And, Attribute, AttributeReference, BoundReference, DynamicPruningExpression, Expression, PlanExpression, Predicate}
import org.apache.spark.sql.connector.read.InputPartition
import org.apache.spark.sql.execution.{FileSourceScanExec, InSubqueryExec, PartitionedFileUtil, ScalarSubquery, SparkPlan, SQLExecution}
import org.apache.spark.sql.execution.datasources.{BucketingUtils, FilePartition, HadoopFsRelation, PartitionDirectory}
import org.apache.spark.sql.execution.metric.{SQLMetric, SQLMetrics}
import org.apache.spark.sql.types.StructType
import org.apache.spark.sql.vectorized.ColumnarBatch
import org.apache.spark.util.collection.BitSet

class FileSourceScanExecTransformer(@transient relation: HadoopFsRelation,
                                    output: Seq[Attribute],
                                    requiredSchema: StructType,
                                    partitionFilters: Seq[Expression],
                                    optionalBucketSet: Option[BitSet],
                                    optionalNumCoalescedBuckets: Option[Int],
                                    dataFilters: Seq[Expression],
                                    tableIdentifier: Option[TableIdentifier],
                                    disableBucketedScan: Boolean = false)
  extends FileSourceScanExec(
    relation,
    output,
    requiredSchema,
    partitionFilters,
    optionalBucketSet,
    optionalNumCoalescedBuckets,
    dataFilters,
    tableIdentifier,
    disableBucketedScan)
    with BasicScanExecTransformer {

  override lazy val metrics = Map(
    "inputRows" -> SQLMetrics.createMetric(sparkContext, "number of input rows"),
    "inputVectors" -> SQLMetrics.createMetric(sparkContext, "number of input vectors"),
    "inputBytes" -> SQLMetrics.createSizeMetric(sparkContext, "number of input bytes"),
    "rawInputRows" -> SQLMetrics.createMetric(sparkContext, "number of raw input rows"),
    "rawInputBytes" -> SQLMetrics.createSizeMetric(sparkContext, "number of raw input bytes"),
    "outputRows" -> SQLMetrics.createMetric(sparkContext, "number of output rows"),
    "outputVectors" -> SQLMetrics.createMetric(sparkContext, "number of output vectors"),
    "outputBytes" -> SQLMetrics.createSizeMetric(sparkContext, "number of output bytes"),
    "count" -> SQLMetrics.createMetric(sparkContext, "cpu wall time count"),
    "wallNanos" -> SQLMetrics.createNanoTimingMetric(sparkContext, "totaltime_filescan"),
    "scanTime" -> SQLMetrics.createTimingMetric(sparkContext, "total scan time"),
    "peakMemoryBytes" -> SQLMetrics.createSizeMetric(sparkContext, "peak memory bytes"),
    "numFiles" -> SQLMetrics.createMetric(sparkContext, "number of files read"),
    "metadataTime" -> SQLMetrics.createTimingMetric(sparkContext, "metadata time"),
    "filesSize" -> SQLMetrics.createSizeMetric(sparkContext, "size of files read"),
    "numPartitions" -> SQLMetrics.createMetric(sparkContext, "number of partitions read"),
    "pruningTime" ->
      SQLMetrics.createTimingMetric(sparkContext, "dynamic partition pruning time"),
    "numMemoryAllocations" -> SQLMetrics.createMetric(
      sparkContext, "number of memory allocations"),
    "numDynamicFiltersAccepted" -> SQLMetrics.createMetric(
      sparkContext, "number of dynamic filters accepted"),
    "numOutputRows" -> SQLMetrics.createMetric(sparkContext, "number of output rows")
  ) ++ staticMetrics

  /** SQL metrics generated only for scans using dynamic partition pruning. */
  private lazy val staticMetrics = if (partitionFilters.exists(FileSourceScanExecTransformer
    .isDynamicPruningFilter)) {
    Map("staticFilesNum" -> SQLMetrics.createMetric(sparkContext, "static number of files read"),
      "staticFilesSize" -> SQLMetrics.createSizeMetric(sparkContext, "static size of files read"))
  } else {
    Map.empty[String, SQLMetric]
  }

  object MetricsUpdaterImpl extends MetricsUpdater {
    val inputRows: SQLMetric = longMetric("inputRows")
    val inputVectors: SQLMetric = longMetric("inputVectors")
    val inputBytes: SQLMetric = longMetric("inputBytes")
    val rawInputRows: SQLMetric = longMetric("rawInputRows")
    val rawInputBytes: SQLMetric = longMetric("rawInputBytes")
    val outputRows: SQLMetric = longMetric("outputRows")
    val outputVectors: SQLMetric = longMetric("outputVectors")
    val outputBytes: SQLMetric = longMetric("outputBytes")
    val count: SQLMetric = longMetric("count")
    val wallNanos: SQLMetric = longMetric("wallNanos")
    val peakMemoryBytes: SQLMetric = longMetric("peakMemoryBytes")
    val numMemoryAllocations: SQLMetric = longMetric("numMemoryAllocations")

    // Number of dynamic filters received.
    val numDynamicFiltersAccepted: SQLMetric = longMetric("numDynamicFiltersAccepted")

    override def updateOutputMetrics(outNumBatches: Long, outNumRows: Long): Unit = {
      outputVectors += outNumBatches
      outputRows += outNumRows
    }

    override def updateNativeMetrics(operatorMetrics: OperatorMetrics): Unit = {
      if (operatorMetrics != null) {
        inputRows += operatorMetrics.inputRows
        inputVectors += operatorMetrics.inputVectors
        inputBytes += operatorMetrics.inputBytes
        rawInputRows += operatorMetrics.rawInputRows
        rawInputBytes += operatorMetrics.rawInputBytes
        outputRows += operatorMetrics.outputRows
        outputVectors += operatorMetrics.outputVectors
        outputBytes += operatorMetrics.outputBytes
        count += operatorMetrics.count
        wallNanos += operatorMetrics.wallNanos
        peakMemoryBytes += operatorMetrics.peakMemoryBytes
        numMemoryAllocations += operatorMetrics.numMemoryAllocations
        numDynamicFiltersAccepted += operatorMetrics.numDynamicFiltersAccepted
      }
    }
  }

  override lazy val supportsColumnar: Boolean = {
    /*
    relation.fileFormat
      .supportBatch(relation.sparkSession, schema) && GlutenConfig.getConf.enableColumnarIterator
    */
    GlutenConfig.getConf.enableColumnarIterator
  }

  override def filterExprs(): Seq[Expression] = dataFilters

  override def outputAttributes(): Seq[Attribute] = output

  override def getPartitions: Seq[Seq[InputPartition]] =
    getFilePartitions().map(Seq(_))

  override def getFlattenPartitions: Seq[InputPartition] = getFilePartitions()

  override def getPartitionSchemas: StructType = relation.partitionSchema

  override def equals(other: Any): Boolean = other match {
    case that: FileSourceScanExecTransformer =>
      (that canEqual this) && super.equals(that)
    case _ => false
  }

  override def canEqual(other: Any): Boolean = other.isInstanceOf[FileSourceScanExecTransformer]

  override def hashCode(): Int = super.hashCode()

  override def columnarInputRDDs: Seq[RDD[ColumnarBatch]] = {
    Seq()
  }

  override def getBuildPlans: Seq[(SparkPlan, SparkPlan)] = {
    Seq((this, null))
  }

  override def getStreamedLeafPlan: SparkPlan = {
    this
  }

  override def getChild: SparkPlan = {
    null
  }

  override def doValidate(): Boolean = {
    super.doValidate()
//
//    // Bucketing table has `bucketId` in filename, should apply this in backends
//    if (!bucketedScan) {
//      super.doValidate()
//    } else {
//      false
//    }
  }

  protected override def doExecuteColumnar(): RDD[ColumnarBatch] = {
    doExecuteColumnarInternal()
  }

  override def metricsUpdater(): MetricsUpdater = MetricsUpdaterImpl

  // The codes below are copied from FileSourceScanExec in Spark,
  // all of them are private.
  private lazy val driverMetrics: HashMap[String, Long] = HashMap.empty

  /**
   * Send the driver-side metrics. Before calling this function, selectedPartitions has
   * been initialized. See SPARK-26327 for more details.
   */
  private def sendDriverMetrics(): Unit = {
    driverMetrics.foreach(e => metrics(e._1).add(e._2))
    val executionId = sparkContext.getLocalProperty(SQLExecution.EXECUTION_ID_KEY)
    SQLMetrics.postDriverMetricUpdates(sparkContext, executionId,
      metrics.filter(e => driverMetrics.contains(e._1)).values.toSeq)
  }

  private def setFilesNumAndSizeMetric(
     partitions: Seq[PartitionDirectory],
     static: Boolean): Unit = {
    val filesNum = partitions.map(_.files.size.toLong).sum
    val filesSize = partitions.map(_.files.map(_.getLen).sum).sum
    if (!static || !partitionFilters.exists(FileSourceScanExecTransformer.isDynamicPruningFilter)) {
      driverMetrics("numFiles") = filesNum
      driverMetrics("filesSize") = filesSize
    } else {
      driverMetrics("staticFilesNum") = filesNum
      driverMetrics("staticFilesSize") = filesSize
    }
    if (relation.partitionSchema.nonEmpty) {
      driverMetrics("numPartitions") = partitions.length
    }
  }

  @transient override lazy val selectedPartitions: Array[PartitionDirectory] = {
    val optimizerMetadataTimeNs = relation.location.metadataOpsTimeNs.getOrElse(0L)
    val startTime = System.nanoTime()
    val ret =
      relation.location.listFiles(partitionFilters.filterNot(isDynamicPruningFilter), dataFilters)
    setFilesNumAndSizeMetric(ret, true)
    val timeTakenMs =
      NANOSECONDS.toMillis((System.nanoTime() - startTime) + optimizerMetadataTimeNs)
    driverMetrics("metadataTime") = timeTakenMs
    ret
  }.toArray

  // We can only determine the actual partitions at runtime when a dynamic partition filter is
  // present. This is because such a filter relies on information that is only available at run
  // time (for instance the keys used in the other side of a join).
  @transient private lazy val dynamicallySelectedPartitions: Array[PartitionDirectory] = {
    val dynamicPartitionFilters = partitionFilters.filter(isDynamicPruningFilter)

    if (dynamicPartitionFilters.nonEmpty) {
      val startTime = System.nanoTime()
      // call the file index for the files matching all filters except dynamic partition filters
      val predicate = dynamicPartitionFilters.reduce(And)
      val partitionColumns = relation.partitionSchema
      val boundPredicate = Predicate.create(predicate.transform {
        case a: AttributeReference =>
          val index = partitionColumns.indexWhere(a.name == _.name)
          BoundReference(index, partitionColumns(index).dataType, nullable = true)
      }, Nil)
      val ret = selectedPartitions.filter(p => boundPredicate.eval(p.values))
      setFilesNumAndSizeMetric(ret, false)
      val timeTakenMs = (System.nanoTime() - startTime) / 1000 / 1000
      driverMetrics("pruningTime") = timeTakenMs
      ret
    } else {
      sendDriverMetrics()
      selectedPartitions
    }
  }
  var filePartitions: Seq[FilePartition] = Nil

  def getFilePartitions(): Seq[FilePartition] = {
    if (filePartitions.isEmpty) {
      filePartitions = if (bucketedScan) {
        createBucketedReadRDD(relation.bucketSpec.get, dynamicallySelectedPartitions, relation)
      } else {
        createReadRDD(dynamicallySelectedPartitions, relation)
      }
    }
    filePartitions
  }

  /**
   * Create an RDD for bucketed reads.
   * The non-bucketed variant of this function is [[createReadRDD]].
   *
   * The algorithm is pretty simple: each RDD partition being returned should include all the files
   * with the same bucket id from all the given Hive partitions.
   *
   * @param bucketSpec         the bucketing spec.
   * @param readFile           a function to read each (part of a) file.
   * @param selectedPartitions Hive-style partition that are part of the read.
   * @param fsRelation         [[HadoopFsRelation]] associated with the read.
   */
  private def createBucketedReadRDD(
    bucketSpec: BucketSpec,
    selectedPartitions: Array[PartitionDirectory],
    fsRelation: HadoopFsRelation): Seq[FilePartition] = {
    logInfo(s"Planning with ${bucketSpec.numBuckets} buckets")
    val filesGroupedToBuckets =
      selectedPartitions
        .flatMap { p =>
          p.files.map { f =>
            PartitionedFileUtil.getPartitionedFile(f, f.getPath, p.values)
          }
        }
        .groupBy { f =>
          BucketingUtils
            .getBucketId(new Path(f.filePath).getName)
            // TODO(SPARK-39163): Throw an exception w/ error class for an invalid bucket file
            .getOrElse(throw new IllegalStateException(s"Invalid bucket file ${f.filePath}"))
        }

    val prunedFilesGroupedToBuckets = if (optionalBucketSet.isDefined) {
      val bucketSet = optionalBucketSet.get
      filesGroupedToBuckets.filter { f =>
        bucketSet.get(f._1)
      }
    } else {
      filesGroupedToBuckets
    }

    val filePartitions = optionalNumCoalescedBuckets
      .map { numCoalescedBuckets =>
        logInfo(s"Coalescing to ${numCoalescedBuckets} buckets")
        val coalescedBuckets = prunedFilesGroupedToBuckets.groupBy(_._1 % numCoalescedBuckets)
        Seq.tabulate(numCoalescedBuckets) { bucketId =>
          val partitionedFiles = coalescedBuckets
            .get(bucketId)
            .map {
              _.values.flatten.toArray
            }
            .getOrElse(Array.empty)
          FilePartition(bucketId, partitionedFiles)
        }
      }
      .getOrElse {
        Seq.tabulate(bucketSpec.numBuckets) { bucketId =>
          FilePartition(bucketId, prunedFilesGroupedToBuckets.getOrElse(bucketId, Array.empty))
        }
      }

    filePartitions
  }

  /**
   * Create an RDD for non-bucketed reads.
   * The bucketed variant of this function is [[createBucketedReadRDD]].
   *
   * @param readFile           a function to read each (part of a) file.
   * @param selectedPartitions Hive-style partition that are part of the read.
   * @param fsRelation         [[HadoopFsRelation]] associated with the read.
   */
  private def createReadRDD(
    selectedPartitions: Array[PartitionDirectory],
    fsRelation: HadoopFsRelation): Seq[FilePartition] = {
    val openCostInBytes = fsRelation.sparkSession.sessionState.conf.filesOpenCostInBytes
    val maxSplitBytes =
      FilePartition.maxSplitBytes(fsRelation.sparkSession, selectedPartitions)
    logInfo(
      s"Planning scan with bin packing, max size: $maxSplitBytes bytes, " +
        s"open cost is considered as scanning $openCostInBytes bytes.")

    // Filter files with bucket pruning if possible
    val bucketingEnabled = fsRelation.sparkSession.sessionState.conf.bucketingEnabled
    val shouldProcess: Path => Boolean = optionalBucketSet match {
      case Some(bucketSet) if bucketingEnabled =>
        // Do not prune the file if bucket file name is invalid
        filePath =>
          BucketingUtils.getBucketId(filePath.getName).forall(bucketSet.get)
      case _ =>
        _ =>
          true
    }

    val splitFiles = selectedPartitions
      .flatMap { partition =>
        partition.files.flatMap { file =>
          // getPath() is very expensive so we only want to call it once in this block:
          val filePath = file.getPath

          if (shouldProcess(filePath)) {
            val isSplitable =
              relation.fileFormat.isSplitable(relation.sparkSession, relation.options, filePath)
            PartitionedFileUtil.splitFiles(
              sparkSession = relation.sparkSession,
              file = file,
              filePath = filePath,
              isSplitable = isSplitable,
              maxSplitBytes = maxSplitBytes,
              partitionValues = partition.values)
          } else {
            Seq.empty
          }
        }
      }
      .sortBy(_.length)(implicitly[Ordering[Long]].reverse)

    FilePartition.getFilePartitions(relation.sparkSession, splitFiles, maxSplitBytes)
  }

}

object FileSourceScanExecTransformer {
  def isDynamicPruningFilter(e: Expression): Boolean =
    e.find(_.isInstanceOf[PlanExpression[_]]).isDefined
}
