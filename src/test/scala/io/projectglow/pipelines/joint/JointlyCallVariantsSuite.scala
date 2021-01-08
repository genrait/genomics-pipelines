/*
 * Copyright 2019 The Glow Authors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package io.projectglow.pipelines.joint

import java.nio.file.{Files, Path, Paths}
import java.util.stream.Collectors

import scala.collection.JavaConverters._
import htsjdk.samtools.ValidationStringency
import org.apache.commons.lang3.exception.ExceptionUtils
import org.apache.spark.{SparkContext, SparkException}
import org.apache.spark.sql.functions.col
import org.apache.spark.sql.internal.SQLConf
import org.apache.spark.sql.SparkSession
import org.bdgenomics.adam.converters.VariantContextConverter
import org.bdgenomics.adam.rdd.ADAMContext._
import org.bdgenomics.adam.rdd.variant.GenotypeDataset
import io.projectglow.pipelines.PipelineBaseTest
import io.projectglow.pipelines.PipelineBaseTest
import io.projectglow.pipelines.common.{DeltaHelper, VersionTracker}
import io.projectglow.pipelines.sql.HLSConf

class JointlyCallVariantsSuite extends PipelineBaseTest {

  lazy val sess = spark

  private def getManifest(locus: String): String = {
    s"$testDataHome/joint/manifest.$locus.csv".toString
  }

  private def createJointCaller(
      locus: String,
      outputToDelta: Boolean = true,
      exportVcfAsSingleFile: Boolean = false): JointlyCallVariants = {

    val outputDir = Files.createTempDirectory("jvc").toString
    val deltaOutputDir = if (outputToDelta) {
      Files.createTempDirectory("jvc-scratch").toString
    } else {
      ""
    }
    val manifestFile = getManifest(locus)

    SQLConf
      .get
      .setConf(
        HLSConf.JOINT_GENOTYPING_NUM_BIN_PARTITIONS,
        spark.sessionState.conf.numShufflePartitions
      )
    SQLConf
      .get
      .setConfString(
        HLSConf.JOINT_GENOTYPING_NUM_SHUFFLE_PARTITIONS.toString,
        spark.sessionState.conf.numShufflePartitions.toString
      )

    val variantIngester = new IngestVariants()
      .setVcfManifest(manifestFile)
      .setGvcfDeltaOutput(deltaOutputDir)
    variantIngester.init()
    variantIngester.execute(spark)

    new JointlyCallVariants()
      .setReferenceGenomeFastaPath(grch38Chr20to21FastaPath)
      .setOutput(outputDir)
      .setGvcfDeltaOutput(deltaOutputDir)
      .setExportVCF(true)
      .setExportVCFAsSingleFile(exportVcfAsSingleFile)
      .setVcfManifest(manifestFile)
  }

  private def sort(rdd: GenotypeDataset): GenotypeDataset = {
    rdd.transformDataset(_.orderBy("sampleId", "start", "variant.alternateAllele", "alleles"))
  }

  private def makeTestCalls(locus: String): JointlyCallVariants = {
    val jointCaller = createJointCaller(locus)
    jointCaller.init()
    jointCaller.execute(spark)
    jointCaller.cleanup(spark)

    jointCaller
  }

  private def getTestCalls(
      spark: SparkSession,
      jointCaller: JointlyCallVariants): GenotypeDataset = {
    val sc = spark.sparkContext
    sort(
      sc.loadGenotypes(
        jointCaller.getCalledVariantOutputVCF(spark.sparkContext.hadoopConfiguration)
      )
    )
  }

  // Generated by calling CombineGVCFs, GenotypeGVCFs using GATK 4.1.4.1
  private def getTruthCalls(sc: SparkContext, locus: String): GenotypeDataset = {
    sort(sc.loadGenotypes(s"$testDataHome/joint/genotyped.$locus.vcf"))
  }

  private def compareCalls(spark: SparkSession, truthLocus: String, testLocus: String): Unit = {
    val truthCalls = getTruthCalls(spark.sparkContext, truthLocus)

    val jointCaller = makeTestCalls(testLocus)
    val testCalls = getTestCalls(spark, jointCaller)

    // check that the Parquet variant rows exported correctly
    val testRows = spark.read.format("delta").load(jointCaller.getCalledVariantOutput)
    import spark.implicits._
    val testSamples =
      testRows.limit(1).select("genotypes.sampleId").as[Seq[String]].collect.head
    assert(testSamples == truthCalls.samples.map(_.getId))

    assert(truthCalls.dataset.count === testCalls.dataset.count)
    val pairedCalls = truthCalls.rdd.collect.zip(testCalls.rdd.collect)
    pairedCalls.foreach(p => compareGenotypes(p._1, p._2))
  }

  private def compareCalls(spark: SparkSession, locus: String): Unit = {
    compareCalls(spark, locus, locus)
  }

  test("Header lines contain expected lines") {
    val jointCaller = createJointCaller("chr20_17960111", exportVcfAsSingleFile = true)
    jointCaller.init()
    jointCaller.execute(spark)

    import sess.implicits._
    val headerLines = spark
      .read
      .format("text")
      .load(jointCaller.getCalledVariantOutputVCF(spark.sparkContext.hadoopConfiguration))
      .filter(col("value").startsWith("##"))
      .as[String]
      .collect()
      .toSeq

    // Contains tool info
    val jointGenotyper = s"GATK${VersionTracker.toolVersion("GATK")}/GenotypeGVCFs"
    assert(
      headerLines.contains(
        s"##DatabricksParams=jointGenotyper=$jointGenotyper " +
        "refGenomeName=Homo_sapiens_assembly38.20.21"
      )
    )

    // Does not contain GVCF blocks
    assert(!headerLines.exists(_.startsWith("##GVCF_BLOCK")))

    // Contains FILTER lines
    val filterLines = headerLines.filter(_.startsWith("##FILTER"))
    assert(filterLines == Seq("##FILTER=<ID=LowQual,Description=\"Low quality\">"))

    // Contains contig lines
    val contigLines = headerLines.filter(_.startsWith("##contig"))
    assert(
      contigLines == Seq(
        "##contig=<ID=chr20,length=64444167>",
        "##contig=<ID=chr21,length=46709983>"
      )
    )
  }

  test("Biallelic SNP") {
    compareCalls(spark, "chr20_17960111")
  }

  test("Multi-allelic indel") {
    compareCalls(spark, "chr20_17983267")
  }

  test("Multiple sites") {
    compareCalls(spark, "chr20_18034651_18034655")
  }

  test("Do not output low quality singleton variants") {
    val locus = "chr20_18034651_18034655"
    val jointCaller = makeTestCalls(locus)
    val rows = spark.read.format("delta").load(jointCaller.getCalledVariantOutput)

    assert(rows.count === 3)
    assert(rows.filter("start = 18034653").isEmpty)
  }

  test("Multiple sites with small bins") {
    SQLConf.get.setConf(HLSConf.JOINT_GENOTYPING_BIN_SIZE, 1)
    compareCalls(spark, "chr20_18034651_18034655")
  }

  test("Multi-allelic indel site") {
    compareCalls(spark, "chr20_419")
  }

  test(s"Overlapping spanning deletions") {
    compareCalls(spark, "chr21_575")
  }

  Seq(("bgzf", ".bgz"), ("gzip", ".gz")).foreach {
    case (codecName, extension) =>
      test(s"Writes $codecName compressed VCF when set") {
        val jointCaller = createJointCaller("chr20_17960111").setVcfCompressionCodec(codecName)

        jointCaller.init()
        jointCaller.execute(spark)
        jointCaller.cleanup(spark)

        val filesWritten = Files
          .list(
            Paths.get(jointCaller.getCalledVariantOutputVCF(spark.sparkContext.hadoopConfiguration))
          )
          .collect(Collectors.toList[Path])
          .asScala
          .map(_.toString)
        assert(filesWritten.exists(s => s.endsWith(extension)))
      }
  }

  test("Writes compressed VCF by default") {
    val jointCaller = createJointCaller("chr20_17960111")

    jointCaller.init()
    jointCaller.execute(spark)
    jointCaller.cleanup(spark)

    val filesWritten = Files
      .list(
        Paths.get(jointCaller.getCalledVariantOutputVCF(spark.sparkContext.hadoopConfiguration))
      )
      .collect(Collectors.toList[Path])
      .asScala
      .map(_.toString)
    assert(filesWritten.exists(s => s.endsWith(".vcf.bgz")))
  }

  test("replay mode") {
    val dir = Files.createTempDirectory("replay")
    val stage = new JointlyCallVariants()
      .setOutput(dir.toString)
    stage.init()
    assert(!stage.outputExists(spark))
    DeltaHelper.save(spark.range(1), stage.getCalledVariantOutput)
    assert(stage.outputExists(spark))
  }

  test("Reads from GVCF directly if no Delta output path provided") {
    val baseJointCaller = new JointlyCallVariants()
    assert(!baseJointCaller.exportGVCFToDelta)
  }

  test("Apply targeted region filter if do not export to Delta") {
    val jointCaller = createJointCaller("chr20_18034651_18034655", outputToDelta = false)
      .setTargetedRegions(s"$testDataHome/joint/chr20_18034651.bed")
    jointCaller.init()
    jointCaller.execute(spark)
    jointCaller.cleanup(spark)

    val ds = spark.read.format("delta").load(jointCaller.getCalledVariantOutput)
    assert(ds.count == 1)
  }

  test("Do not apply targeted region filter if reading from Delta") {
    // Filtering will be done during IngestVariants; re-filtering would be redundant

    val jointCaller = createJointCaller("chr20_18034651_18034655")
      .setTargetedRegions(s"$testDataHome/joint/chr20_18034651.bed")
    jointCaller.init()
    jointCaller.execute(spark)
    jointCaller.cleanup(spark)

    val ds = spark.read.format("delta").load(jointCaller.getCalledVariantOutput)
    assert(ds.count == 3)
  }

  test("Throws error if strict validation and do not export to Delta") {
    val jointCaller = createJointCaller("malformed", outputToDelta = false)
      .setPerformValidation(true)
      .setValidationStringency(ValidationStringency.STRICT)
    jointCaller.init()
    val caught = intercept[SparkException] {
      jointCaller.execute(spark)
    }
    assert(
      ExceptionUtils.getRootCauseMessage(caught).contains("Found VCF record with invalid PL length")
    )
  }

  Seq(ValidationStringency.LENIENT, ValidationStringency.SILENT).foreach { vs =>
    test(s"Apply filter quietly if do not export to Delta and stringency $vs") {
      val jointCaller = createJointCaller("malformed", outputToDelta = false)
        .setPerformValidation(true)
        .setValidationStringency(vs)
      jointCaller.init()
      jointCaller.execute(spark)
      jointCaller.cleanup(spark)

      val ds = spark.read.format("delta").load(jointCaller.getCalledVariantOutput)
      assert(ds.count == 1)
    }
  }

  test("Do not apply PL count filter if told not to validate") {
    val jointCaller = createJointCaller("malformed", outputToDelta = false)
      .setPerformValidation(false)
    jointCaller.init()
    val caught = intercept[SparkException] {
      jointCaller.execute(spark)
    }
    assert(caught.getCause.getCause.isInstanceOf[ArrayIndexOutOfBoundsException])
  }

  test("Do not apply PL count filter if reading from Delta") {
    val jointCaller = createJointCaller("malformed")
      .setPerformValidation(true)
    jointCaller.init()
    val caught = intercept[SparkException] {
      jointCaller.execute(spark)
    }
    assert(caught.getCause.getCause.isInstanceOf[ArrayIndexOutOfBoundsException])
  }

  test("Do not perform validation, validation stringency strict by default") {
    val jointCaller = new JointlyCallVariants()
    assert(!jointCaller.getPerformValidation)
    assert(jointCaller.getValidationStringency == ValidationStringency.STRICT)
  }

  test("Load glob") {
    compareCalls(spark, "chr21_575", "chr21_575_split_glob")
  }

  test("Must provide manifest if no output to Delta") {
    val jointCaller = createJointCaller("chr20_18034651_18034655", outputToDelta = false)
      .setVcfManifest("")
    jointCaller.init()
    assertThrows[IllegalArgumentException](jointCaller.execute(spark))
  }

  test("Does not require manifest if output to Delta") {
    val jointCaller = createJointCaller("chr21_575").setVcfManifest("")
    jointCaller.init()
    jointCaller.execute(spark)

    val outputRows = spark.read.format("delta").load(jointCaller.getCalledVariantOutput)
    assert(outputRows.count == 1)
    import sess.implicits._
    val outputSamples =
      outputRows.limit(1).select("genotypes.sampleId").as[Seq[String]].collect.head
    assert(outputSamples == Seq("HG002", "HG003", "HG004"))
  }

  test("Prefers manifest over inference") {
    val jointCaller =
      createJointCaller("chr21_575").setVcfManifest(getManifest("chr20_17960111"))
    jointCaller.init()
    val e = intercept[SparkException](jointCaller.execute(spark))
    assert(
      e.getCause.getMessage.contains("Variant row does not contain samples provided in manifest")
    )
  }

  test("Missing overlapping GVCF band") {
    val jointCaller = createJointCaller("non_overlapping")
    jointCaller.init()
    jointCaller.execute(spark)

    val outputRows = spark.read.format("delta").load(jointCaller.getCalledVariantOutput)
    assert(outputRows.count == 2)
    import sess.implicits._
    val outputSamples =
      outputRows.limit(1).select("genotypes.sampleId").as[Seq[String]].collect.head
    assert(outputSamples == Seq("HG002", "HG003", "HG004"))
  }

  override def beforeEach(): Unit = {
    super.beforeEach()
    val sc = spark.sparkContext
    VariantContextConverter.setNestAnnotationInGenotypesProperty(
      sc.hadoopConfiguration,
      populateNestedAnn = true
    )
  }
}
