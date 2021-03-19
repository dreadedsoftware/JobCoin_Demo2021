package com.gemini.jobcoin

import scala.io.Source
import java.net.{URL, HttpURLConnection}

object util{
  case class Rest(defaultTimeout: Int){
    // No need for fancy libraries here
    // in a more complex project of course libraries would be used to improve correctness
    def get(uri: String): String = {
      val connection = connect(uri)
      connection.setRequestMethod("GET")
      produce(connection)
    }

    def post(uri: String): String = {
      val connection = connect(uri)
      connection.setRequestMethod("POST")
      produce(connection)
    }

    def connect(uri: String): HttpURLConnection = {
      val connection = (new URL(uri)).openConnection.asInstanceOf[HttpURLConnection]
      connection.setConnectTimeout(defaultTimeout)
      connection.setReadTimeout(defaultTimeout)
      connection
    }
    def produce(connection: HttpURLConnection): String = {
      val inputStream = connection.getInputStream
      val content = Source.fromInputStream(inputStream).mkString
      if (inputStream != null) inputStream.close
      content
    }
  }
}
