package org.template.classification

import io.prediction.controller.{PAlgorithm, Params}

import org.apache.spark.mllib.linalg.Vectors
import org.apache.spark.{SparkConf, SparkContext}
import org.apache.spark.mllib.linalg.Vector
import grizzled.slf4j.Logger

import java.nio.file.{Files, Paths}

import org.apache.spark.mllib.clustering.{DistributedLDAModel, LDAModel, LDA}
import org.apache.spark.rdd.RDD

case class LDAModelWithCorpusAndVocab(
                               ldaModel: DistributedLDAModel,
                               corpus: RDD[(String, (Long,Vector))],
                               vocab : Map[String,Int]
                               )

case class AlgorithmParams(
  numTopics: Int,
  maxIter: Int,
  docConcentration: Double,
  topicConcentration: Double
) extends Params

// extends PAlgorithm because contains RDD.
// Does not implement save and load, because DistributedLDAModel doesn't support it yet
class LDAAlgorithm(val ap: AlgorithmParams)
  extends PAlgorithm[PreparedData, LDAModelWithCorpusAndVocab, Query, PredictedResult] {

  @transient lazy val logger = Logger[this.type]

  def train(sc: SparkContext, data: PreparedData): LDAModelWithCorpusAndVocab = {
   
    require(!data.points.take(1).isEmpty,
      s"RDD[labeldPoints] in PreparedData cannot be empty." +
      " Please check if DataSource generates TrainingData" +
      " and Preprator generates PreparedData correctly.")

    val dataStrings = data.points.map(s => s.text)
    val (corpus, vocab) = makeDocuments(dataStrings)
    val ldaModel = new LDA().setSeed(13457).setK(ap.numTopics).setMaxIterations(ap.maxIter).run(corpus)
      .asInstanceOf[DistributedLDAModel]

    LDAModelWithCorpusAndVocab(ldaModel, dataStrings zip corpus, vocab)
  }

  //For now we can only return topic distributions for documents that appeared in the training set
  def predict(ldaModelAndCorpus: LDAModelWithCorpusAndVocab, query: Query): PredictedResult = {
    val topics = ldaModelAndCorpus.ldaModel.describeTopics(10)
    val topicDists = ldaModelAndCorpus.ldaModel.topicDistributions
    val corpusMap =ldaModelAndCorpus.corpus.collect().toMap

    val maxTopicIndex: Int = getMaxTopicIndex(query, topicDists, corpusMap)
    val swappedMap = ldaModelAndCorpus.vocab.map(_.swap)
    val topicResults = for( ((indices, weights), outerIndex) <- topics zipWithIndex)
                       yield {outerIndex -> (indices map (x => swappedMap(x)) zip weights).sortWith((e1, e2) => (e1._2 > e2._2))}

    val topTopic = topicResults.toMap.getOrElse(maxTopicIndex, throw new scala.Exception("Cannot find topic"))

    new PredictedResult(topTopic, topicResults)
  }

  def getMaxTopicIndex(query: Query, topicDists: RDD[(Long, Vector)], corpusMap: Map[String, (Long, Vector)]): Int = {
    val (queryDocId, queryVec) = corpusMap.getOrElse(query.text.trim, throw new scala.Exception
    ("Can't find document"))
    logger.error(query.text + " " + queryVec)

    val queryTopics = for ((index, vec) <- topicDists if index.equals(queryDocId)) yield vec

    for (thisTopic <- queryTopics) {
      logger.error(thisTopic)
    }

    val queryTopicsArray = queryTopics.first().toArray
    val maxTopic = queryTopicsArray.max
    val maxTopicIndex = queryTopicsArray.indexOf(maxTopic)
    maxTopicIndex
  }


  //See https://gist.github.com/jkbradley/ab8ae22a8282b2c8ce33
  def makeDocuments(data: RDD[String]): (RDD[(Long, Vector)], Map[String, Int]) = {
    // Split each document into a sequence of terms (words)
    val tokenized: RDD[Seq[String]] =
      data.map(_.toLowerCase.split("\\s")).map(_.filter(_.length > 3).filter(_.forall(java.lang.Character.isLetter)))

    // Choose the vocabulary.
    //   termCounts: Sorted list of (term, termCount) pairs
    val termCounts: Array[(String, Long)] =
      tokenized.flatMap(_.map(_ -> 1L)).reduceByKey(_ + _).collect().sortBy(-_._2)
    //   vocabArray: Chosen vocab (removing common terms)
    val numStopwords = 50
    val vocabArray: Array[String] =
      termCounts.takeRight(termCounts.size - numStopwords).map(_._1)
    //   vocab: Map term -> term index
    val vocab: Map[String, Int] = vocabArray.zipWithIndex.toMap


    // Convert documents into term count vectors
    val documents: RDD[(Long, Vector)] =
      tokenized.zipWithIndex.map { case (tokens, id) =>
        val counts = new scala.collection.mutable.HashMap[Int, Double]()
        tokens.foreach { term =>
          if (vocab.contains(term)) {
            val idx = vocab(term)
            counts(idx) = counts.getOrElse(idx, 0.0) + 1.0
          }
        }
        (id, Vectors.sparse(vocab.size, counts.toSeq))
      }
    (documents, vocab)
  }


}
