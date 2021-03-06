package com.zhuangjy.analysis

import java.io.StringWriter
import java.sql.DriverManager

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.scala.DefaultScalaModule
import com.zhuangjy.common.{JobEnum, JobType, JobTypeMap}
import com.zhuangjy.entity.Origin
import org.apache.spark.SparkContext
import org.apache.spark.rdd.JdbcRDD

import scala.collection.mutable.ArrayBuffer
import scala.collection.JavaConverters._

/**
  * Created by johnny on 16/3/8.
  */
object OriginAnalysisObj {
  val mapper = new ObjectMapper
  mapper.registerModule(DefaultScalaModule)

  def calcNormal(min: Long, max: Long, sc: SparkContext): ArrayBuffer[Origin] = {
    val rdd = new JdbcRDD(
      sc,
      () => {
        Class.forName("com.mysql.jdbc.Driver").newInstance()
        DriverManager.getConnection("jdbc:mysql://127.0.0.1:3306/jobs", "root", "")
      },
      "SELECT `origin` as origin, count(*) as count ,AVG(`salary`) as salary from job WHERE id >= ? AND id <= ? GROUP BY `origin` ",
      min, max, 1,
      r => (r.getString("origin"), r.getLong("count"), r.getFloat("salary"))
    ).cache()
    var origins: ArrayBuffer[Origin] = ArrayBuffer()
    rdd.collect().iterator.foreach(s => {
      val origin = new Origin()
      origin.setOrigin(s._1)
      origin.setCount(s._2)
      origin.setSalary(s._3)
      origins += origin
    })
    origins
  }

  def calcDetail(min: Long, max: Long, sc: SparkContext, origins: ArrayBuffer[Origin]): Unit = {
    val rdd = new JdbcRDD(
      sc,
      () => {
        Class.forName("com.mysql.jdbc.Driver").newInstance()
        DriverManager.getConnection("jdbc:mysql://127.0.0.1:3306/jobs", "root", "")
      },
      "SELECT `job_name` AS jobName , `origin` AS origin ,`job_type` as jobType FROM job WHERE id>=? AND id<=?",
      min, max, 3,
      r => (r.getString("jobName"), r.getString("origin"), r.getInt("jobType"))
    ).cache()

    origins.foreach(origin => {
      var jobTypeMap: Map[String, (Long, Map[String, Long])] = Map()
      val specificRdd = rdd.filter(_._2.toUpperCase.contains(origin.getOrigin.toUpperCase))

      for (s: Integer <- JobEnum.listAllTypeIndex().asScala) {
        val specificTypeRdd = specificRdd.filter(_._3.==(s))
        val totalCount = specificTypeRdd.count
        var count: Long = 0
        var jobMap: Map[String, Long] = Map()
        var other: String = null
        //FIXME: 其他工作选项 后端其他归纳进去
        JobEnum.listKeyWords(s).asScala.iterator.foreach(key=>{
          //考虑其他情况
          if (JobEnum.getJobNameByKeyWords(key).indexOf("其他") == -1) {
            val detailCount = specificTypeRdd.filter(_._1.toUpperCase.contains(key.toUpperCase)).count
            jobMap += (JobEnum.getJobNameByKeyWords(key) -> detailCount)
            count += detailCount
          } else {
            other = JobEnum.getJobNameByKeyWords(key)
          }
        })
        count = totalCount - count
        jobMap += (other -> count)
        jobTypeMap += (JobTypeMap.getJobTypeName(s) -> (totalCount -> jobMap))
      }
      val strWriter = new StringWriter
      mapper.writeValue(strWriter, jobTypeMap)
      origin.setDetailCount(strWriter.toString)
    })
  }
}
