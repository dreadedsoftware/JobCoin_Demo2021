package com.gemini.jobcoin

import play.api.libs.ws._
import play.api.libs.ws.ahc._
import play.api.libs.ws.JsonBodyReadables._
import play.api.libs.ws.JsonBodyWritables._
import play.api.libs.json._
import play.api.libs.json.Reads._
import play.api.libs.functional.syntax._
import com.typesafe.config.Config
import akka.stream.Materializer

import scala.async.Async._
import scala.concurrent.Future

import DefaultBodyReadables._
import scala.concurrent.ExecutionContext.Implicits._

import JobcoinClient.PlaceholderResponse

class JobcoinClient(config: Config)(implicit materializer: Materializer) {
  private val wsClient = StandaloneAhcWSClient()
  private val apiAddressesUrl = config.getString("jobcoin.apiAddressesUrl")
  private val createUrl = config.getString("jobcoin.createUrl")

  // Docs:
  // https://github.com/playframework/play-ws
  // https://www.playframework.com/documentation/2.6.x/ScalaJsonCombinators
  val addys = Seq(
    "e989d712-0088-44d0-ac1a-59d9a5a57d97",
    "132915e5-dca3-472c-91dd-04a7cf19221e")
  def testPost(): Future[PlaceholderResponse] = async {
    val addysRandom = scala.util.Random.shuffle(addys)
    val addy = addysRandom(0)
    val json = Json.obj(("address" -> addy))
    val response = await {
      wsClient
        .url(createUrl)
        .post(json)
    }

    response
      .body[JsValue]
      .validate[PlaceholderResponse]
      .get
  }
  def testGet(): Seq[Future[PlaceholderResponse]] = addys.map{addy =>
    async {
      val response = await {
        wsClient
          .url(s"$apiAddressesUrl/$addy")
          .get
      }

      response
        .body[JsValue]
        .validate[PlaceholderResponse]
        .get
    }
  }
}

object JobcoinClient {
  case class PlaceholderResponse(userId: Int, id: Int, title: String, body: String)
  object PlaceholderResponse {
    implicit val jsonReads: Reads[PlaceholderResponse] = Json.reads[PlaceholderResponse]
  }
}
