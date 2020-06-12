package org.ekstep.analytics.framework

import org.apache.spark.SparkContext
import org.apache.spark.rdd.RDD
import org.apache.spark.streaming.StreamingContext
import org.apache.spark.streaming.dstream.DStream
import org.ekstep.analytics.framework.Level.INFO
import org.ekstep.analytics.framework.exception.DataFetcherException
import org.ekstep.analytics.framework.fetcher.{AzureDataFetcher, DruidDataFetcher, S3DataFetcher}
import org.ekstep.analytics.framework.util.{JSONUtils, JobLogger}

/**
 * @author Santhosh
 */
object DataFetcher {

    implicit val className = "org.ekstep.analytics.framework.DataFetcher"
    @throws(classOf[DataFetcherException])
    def fetchBatchData[T](search: Fetcher)(implicit mf: Manifest[T], sc: SparkContext, fc: FrameworkContext): RDD[T] = {

        JobLogger.log("Fetching data", Option(Map("query" -> search)))
        if (search.queries.isEmpty && search.druidQuery.isEmpty) {
            if (search.`type`.equals("none")) return sc.emptyRDD[T]
            throw new DataFetcherException("Data fetch configuration not found")
        }
        //val date = search.queries.get.last.endDate
        val keys = search.`type`.toLowerCase() match {
            case "s3" =>
                JobLogger.log("Fetching the batch data from S3")
                S3DataFetcher.getObjectKeys(search.queries.get);
            case "azure" =>
                JobLogger.log("Fetching the batch data from AZURE")
                AzureDataFetcher.getObjectKeys(search.queries.get);
            case "local" =>
                JobLogger.log("Fetching the batch data from Local file")
                search.queries.get.map { x => x.file.getOrElse(null) }.filterNot { x => x == null };
            case "druid" =>
                JobLogger.log("Fetching the batch data from Druid")
                val data = DruidDataFetcher.getDruidData(search.druidQuery.get)
                // $COVERAGE-OFF$ 
                // Disabling scoverage as the below code cannot be covered as DruidDataFetcher is not mockable being an object and embedded druid is not available yet
                val druidDataList = data.map(f => JSONUtils.deserialize[T](f))
                return sc.parallelize(druidDataList);
                // $COVERAGE-ON$
            case _ =>
                throw new DataFetcherException("Unknown fetcher type found");
        }

        if (null == keys || keys.length == 0) {
            return sc.parallelize(Seq[T](), JobContext.parallelization);
        }
        JobLogger.log("Deserializing Input Data", None, INFO);

//        - apply filters on keys returned from AzureDataFetcher/S3DataFetcher for partitions
//        - method- getFilteredKeys(query: Query, keys: Array[String], partitions: Option[List[Int]]) : returns Array[String]
//        - if partitions is None, return input keys
//        - if partitions are specified, filter keys for given partitions
//        - filter values are created with combination of all dates and partitions list values
//        - EX: fromDate=2020-06-01, toDate=2020-06-02 partitions=[0,1], returns all the keys matching "2020-06-01-0", "2020-06-01-1", "2020-06-02-0" & "2020-06-02-1"
//        - get all dates between startDate and endDate from query: Handle for empty startDate, endDate with delta value, only endDate in the query

        val filteredKeys = search.queries.get.map{q =>
            getFilteredKeys(q, keys, q.partitions)
        }.flatMap(f => f)

        val isString = mf.runtimeClass.getName.equals("java.lang.String");
        val inputEventsCount = fc.inputEventsCount;
        sc.textFile(filteredKeys.mkString(","), JobContext.parallelization).map { line => {
            try {
                inputEventsCount.add(1);
                if (isString) line.asInstanceOf[T] else JSONUtils.deserialize[T](line);
            } catch {
                case ex: Exception =>
                    JobLogger.log(ex.getMessage, None, INFO);
                    null.asInstanceOf[T]
                }
            }
        }.filter { x => x != null };
    }

    /**
     * API to fetch the streaming data given an array of query objects
     */
    def fetchStreamData[T](sc: StreamingContext, search: Fetcher)(implicit mf: Manifest[T]): DStream[T] = {
        null;
    }

    def getFilteredKeys(query: Query, keys: Array[String], partitions: Option[List[Int]]): Array[String] = {
        if (partitions.nonEmpty) {
            val dates = CommonUtil.getQueryDates(query)
            val filterValues = dates.map{d =>
                partitions.get.map{p =>
                    d + "-" + p
                }
            }.flatMap(f => f)
            val finalKeys = keys.map{f =>
                filterValues.map{x =>
                    if(f.contains(x)) f else ""
                }
            }.flatMap(f => f)
            finalKeys.filter(f => f.nonEmpty)
        }
        else keys
    }
}