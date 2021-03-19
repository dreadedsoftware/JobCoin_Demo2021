package com.gemini.jobcoin.mixer

import java.time.Duration
import java.util.concurrent.TimeUnit
import java.util.UUID
import scala.collection.immutable.HashMap
import scala.math.BigDecimal
import scala.util.{Try, Success, Failure}
import akka.actor.Actor
import akka.util.Timeout
import play.api.libs.json._

import com.gemini.jobcoin.util._

class MixerNetwork extends Actor{
  import MixerNetwork._

  def system = context.system
  def config = system.settings.config

  val _defaultTimeout = config.getInt("jobcoin.defaultTimeout")
  implicit val defaultTimeout =
    Timeout(
      _defaultTimeout,
      java.util.concurrent.TimeUnit.MILLISECONDS)
  val rest = Rest(_defaultTimeout)

  def receive = {
    case CheckBalance(address) =>
      val amount = checkBalance(address)
      sender ! MixerCreator.Transfer(Some(address), amount)
    case Transfer(source, target, amount) =>
      sender ! balanceTransfer(source, target, amount)
  }

  def balanceTransfer(source: String, target: String, amount: BigDecimal): Try[Unit] = {
    val baseUri = config.getString("jobcoin.apiTransactionsUrl")
    val uri =
      baseUri +
      s"?fromAddress=$source" +
      s"&toAddress=$target" +
      s"&amount=$amount"

    Try(rest.post(uri)) match{
      case Success(_) =>
        Success(())
      case Failure(th) =>
        println("Failed to Transfer from " + source)
        th.printStackTrace()
        Failure(th)
    }
  }

  def checkBalance(address: String): Option[BigDecimal] = {
    val baseUri = config.getString("jobcoin.apiAddressesUrl")
    val uri = baseUri + s"/$address"
    Try{
      val json = rest.get(uri)
      val _amount: String = Json.stringify((Json.parse(json) \ "balance").get)
      val amount: String = {
        if(_amount.startsWith("\"")) Some(_amount.substring(1))
        else Some(_amount)
      }.map{_amount =>
        val len = _amount.size
        if(_amount.endsWith("\"")) (_amount.substring(0, len - 1))
        else (_amount)
      }.getOrElse(
        throw new NumberFormatException(s"Could not parse ${_amount}."))
      val result = BigDecimal(amount)
      if(0 < result) Some(result)
      else None
    } match {
      case Success(amount) =>
        amount
      case Failure(th) =>
        println("Failed to inquire about " + address)
        th.printStackTrace()
        None
    }
  }
}

object MixerNetwork{
  sealed trait MixerNetworkMessage
  final case class CheckBalance(address: String) extends MixerNetworkMessage
  final case class Transfer(source: String, target: String, amount: BigDecimal) extends MixerNetworkMessage
}
